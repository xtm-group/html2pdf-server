package eu.xtrf.html2pdf.server.converter.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounds the source pixels OpenPDF rasterizes for a page background, so that an image a user is
 * permitted to upload cannot decide how much heap a render costs.
 *
 * Unlike its package neighbours, this bean is a long-lived singleton: {@code RendererProviderImpl}
 * builds a fresh renderer and a fresh user agent for every render, so a per-render cache would be
 * re-paid once per concurrent request. The cache is bounded by the bytes it holds, not by an entry
 * count - a downscaled background is a re-encoded PNG of up to {@code targetPixels}, so a count alone
 * would still let a handful of entries claim hundreds of megabytes - and it holds only the
 * backgrounds this class transformed, never a source image the caller already owns.
 */
@Slf4j
@Component
class BackgroundImageOptimizer {

    static final String ENABLED_PROPERTY = "eu.xtrf.html2pdf.image.backgroundOptimizationEnabled";
    static final String OPTIMIZE_ABOVE_PROPERTY = "eu.xtrf.html2pdf.image.optimizeAbovePixels";
    static final String TARGET_PIXELS_PROPERTY = "eu.xtrf.html2pdf.image.targetPixels";
    static final String MAX_PIXELS_PROPERTY = "eu.xtrf.html2pdf.image.maxPixels";
    static final String MAX_CACHE_BYTES_PROPERTY = "eu.xtrf.html2pdf.image.maxCacheBytes";

    static final long DEFAULT_OPTIMIZE_ABOVE_PIXELS = 12_000_000L;
    static final long DEFAULT_TARGET_PIXELS = 8_700_000L;
    static final long DEFAULT_MAX_PIXELS = 30_000_000L;
    static final long DEFAULT_MAX_CACHE_BYTES = 64L * 1024 * 1024;

    static final int BLANK_WIDTH = 1200;
    static final int BLANK_HEIGHT = 1800;

    private static final String PNG_FORMAT = "png";

    private final boolean enabled;
    private final long optimizeAbovePixels;
    private final long targetPixels;
    private final long maxPixels;
    private final ByteBoundedCache cache;
    private byte[] blank;

    BackgroundImageOptimizer() {
        this(Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true")),
                Long.getLong(OPTIMIZE_ABOVE_PROPERTY, DEFAULT_OPTIMIZE_ABOVE_PIXELS),
                Long.getLong(TARGET_PIXELS_PROPERTY, DEFAULT_TARGET_PIXELS),
                Long.getLong(MAX_PIXELS_PROPERTY, DEFAULT_MAX_PIXELS),
                Long.getLong(MAX_CACHE_BYTES_PROPERTY, DEFAULT_MAX_CACHE_BYTES));
    }

    BackgroundImageOptimizer(boolean enabled, long optimizeAbovePixels, long targetPixels, long maxPixels, long maxCacheBytes) {
        this.enabled = enabled;
        this.optimizeAbovePixels = optimizeAbovePixels;
        this.targetPixels = targetPixels;
        this.maxPixels = maxPixels;
        this.cache = new ByteBoundedCache(maxCacheBytes);
    }

    /**
     * The single gate for the whole feature. {@code InlineImageRewriter} asks before it touches the
     * stylesheet, so that a disabled optimizer leaves the caller's CSS string untouched and the
     * render behaves exactly as it did before this change on the same binary.
     */
    boolean isEnabled() {
        return enabled;
    }

    /** Bytes of transformed backgrounds currently held, for diagnostics and tests. */
    long cachedBytes() {
        return cache.heldBytes();
    }

    /**
     * Keyed on a digest of the source rather than on the bytes, so the cache never holds a second
     * reference to an image the caller already owns. Both the hit and the header read are lock-free;
     * only the two tiers that transform an image take the lock, so a render of an ordinary
     * background never queues behind another render's downscale.
     */
    PreparedBackground optimize(byte[] source, String correlationId) {
        String key = DigestUtils.sha256Hex(source);
        PreparedBackground cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        return decide(key, source, correlationId);
    }

    /**
     * The order is the fix: the pixel cap is decided from the header alone and always precedes any
     * decode, because re-encoding an image first exhausts the heap during the transform itself.
     */
    private PreparedBackground decide(String key, byte[] source, String correlationId) {
        BackgroundImageFacts facts = BackgroundImageFacts.read(source);
        if (facts == null) {
            log.warn("[{}] Background image of {} bytes could not be inspected; passing it to the renderer unchanged.", correlationId, source.length);
            return new PreparedBackground(source, 0, 0, PreparedBackground.Verdict.UNREADABLE);
        }
        if (!facts.decodedAsRaster()) {
            return unchanged(source, facts);
        }
        if (facts.pixels() > maxPixels) {
            return transformOnce(key, () -> substituted(source, facts, correlationId));
        }
        if (facts.pixels() <= optimizeAbovePixels) {
            return unchanged(source, facts);
        }
        return transformOnce(key, () -> downscaled(source, facts, correlationId));
    }

    /**
     * Runs a transform at most once per distinct source: the first caller decodes while holding the
     * lock, the others wait and take its result, so K concurrent cold renders of one background pay
     * one decode rather than K. Only a transformed background is stored - a verdict that serves the
     * caller's own array back would pin a full-size source image for the life of the JVM and buy
     * back nothing but a header read.
     *
     * The lock is on this bean, so transforms of DIFFERENT backgrounds serialize too, and that is
     * deliberate: it caps the heap held by concurrent decodes at one raster instead of K, which is
     * the heap bound this class exists to give. Renders that need no transform never reach here.
     */
    private synchronized PreparedBackground transformOnce(String key, Supplier<PreparedBackground> transform) {
        PreparedBackground cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        PreparedBackground prepared = transform.get();
        if (prepared.verdict() != PreparedBackground.Verdict.UNCHANGED) {
            cache.put(key, prepared);
        }
        return prepared;
    }

    private PreparedBackground unchanged(byte[] source, BackgroundImageFacts facts) {
        return new PreparedBackground(source, facts.width(), facts.height(), PreparedBackground.Verdict.UNCHANGED);
    }

    private PreparedBackground substituted(byte[] source, BackgroundImageFacts facts, String correlationId) {
        byte[] substitute = blankBackground();
        if (substitute == null) {
            log.warn("[{}] Background image {} exceeds the {} pixel limit but no blank substitute could be built, so it is rendered unchanged and may exhaust the heap.",
                    correlationId, facts.describe(), maxPixels);
            return unchanged(source, facts);
        }
        log.warn("[{}] Background image {} of {} bytes is {} pixels, above the {} pixel limit set by {}; substituting a blank page background because decoding it would exhaust the heap.",
                correlationId, facts.describe(), source.length, facts.pixels(), maxPixels, MAX_PIXELS_PROPERTY);
        return new PreparedBackground(substitute, facts.width(), facts.height(), PreparedBackground.Verdict.SUBSTITUTED);
    }

    /**
     * Decodes through ImageIO source subsampling rather than reading the full raster and scaling it
     * afterwards: the destination raster is allocated at the subsampled size, so peak heap follows
     * the target pixels rather than the source pixels. The step is rounded up so the result is
     * always at or below the target, never above it, and PNG goes in and out so subsampling keeps
     * the source colour model and its alpha channel.
     */
    private PreparedBackground downscaled(byte[] source, BackgroundImageFacts facts, String correlationId) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return unchanged(source, facts);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int step = Math.max(1, (int) Math.ceil(Math.sqrt((double) facts.pixels() / targetPixels)));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage small = reader.read(0, param);
                ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
                ImageIO.write(small, PNG_FORMAT, rewritten);
                log.info("[{}] Background image {} is above the {} pixel optimisation threshold set by {}; serving it at {}x{} instead.",
                        correlationId, facts.describe(), optimizeAbovePixels, OPTIMIZE_ABOVE_PROPERTY, small.getWidth(), small.getHeight());
                return new PreparedBackground(rewritten.toByteArray(), facts.width(), facts.height(), PreparedBackground.Verdict.DOWNSCALED);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[{}] Background image {} could not be downscaled; using it unchanged.", correlationId, facts.describe(), e);
            return unchanged(source, facts);
        }
    }

    /**
     * Stand-in for an image too large to hand to ImageIO at all. Generated rather than shipped as a
     * resource: opaque white at page proportions, so it neither carries an alpha channel nor tiles
     * across the page the way a 1x1 image would.
     */
    private synchronized byte[] blankBackground() {
        if (blank == null) {
            blank = renderBlank();
        }
        return blank;
    }

    private byte[] renderBlank() {
        try {
            BufferedImage image = new BufferedImage(BLANK_WIDTH, BLANK_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, BLANK_WIDTH, BLANK_HEIGHT);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(image, PNG_FORMAT, encoded);
            return encoded.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not create a blank background substitute.", e);
            return null;
        }
    }

    /**
     * An access-ordered map whose bound is the bytes of the PNGs it holds, not their number. A
     * downscaled background is a re-encoded PNG of up to {@code targetPixels}, close to 35 MB at the
     * default when the content does not compress, so a count of entries would still let a handful of
     * them claim hundreds of megabytes of heap. A put evicts from the least recently used end until
     * the total fits again; an entry larger than the whole budget is never stored, because it would
     * evict everything and buy back nothing.
     *
     * Every method holds the cache's own monitor, so the access-order relinking inside {@code get}
     * never runs concurrently with a {@code put}. A budget of zero or less caches nothing and still
     * serves every render.
     */
    static final class ByteBoundedCache {

        private final long maxBytes;
        private final Map<String, PreparedBackground> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long heldBytes;

        ByteBoundedCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized PreparedBackground get(String key) {
            return entries.get(key);
        }

        synchronized void put(String key, PreparedBackground value) {
            long size = value.bytes().length;
            if (size > maxBytes) {
                return;
            }
            PreparedBackground previous = entries.put(key, value);
            if (previous != null) {
                heldBytes -= previous.bytes().length;
            }
            heldBytes += size;
            Iterator<PreparedBackground> leastRecentlyUsedFirst = entries.values().iterator();
            while (heldBytes > maxBytes && leastRecentlyUsedFirst.hasNext()) {
                heldBytes -= leastRecentlyUsedFirst.next().bytes().length;
                leastRecentlyUsedFirst.remove();
            }
        }

        synchronized long heldBytes() {
            return heldBytes;
        }

    }

    /**
     * The bytes to serve to the renderer, together with the dimensions of the image the theme asked
     * for. The original dimensions are what the user agent restores on the laid-out image, so that
     * a downscaled or substituted background does not move the page.
     */
    record PreparedBackground(byte[] bytes, int originalWidth, int originalHeight, Verdict verdict) {

        enum Verdict {
            UNCHANGED, DOWNSCALED, SUBSTITUTED, UNREADABLE
        }

    }

}
