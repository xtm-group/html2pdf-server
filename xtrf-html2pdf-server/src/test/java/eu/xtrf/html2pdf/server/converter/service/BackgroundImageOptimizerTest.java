package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground.Verdict;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.decode;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.dimensionsOf;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.jpeg;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.png;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.pngHeaderOnly;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * XDEV-6073. The tier table and its two boundaries, the cache policy, and the two runtime properties
 * the fix promises: that nothing is written to disk and that a hostile property value degrades.
 *
 * Thresholds are passed to the constructor rather than set as system properties, so every case runs
 * against a 400x300 fixture (120 000 pixels) and no test has to build a 30-megapixel image.
 */
public class BackgroundImageOptimizerTest {

    private static final long PIXELS = 120_000L;
    private static final long TARGET = 30_000L;
    private static final String CORRELATION_ID = "xdev-6073";

    private static BackgroundImageOptimizer optimizer(long optimizeAbovePixels, long maxPixels) {
        return new BackgroundImageOptimizer(true, optimizeAbovePixels, TARGET, maxPixels, 16);
    }

    @Test
    public void should_leave_an_image_below_the_optimisation_threshold_untouched() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED);
        assertSame(prepared.bytes(), source, "an untouched image must be served back as the caller's own array");
        assertEquals(prepared.originalWidth(), 400);
        assertEquals(prepared.originalHeight(), 300);
    }

    @Test
    public void should_leave_an_image_exactly_at_the_optimisation_threshold_untouched() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "the optimisation boundary is inclusive");
    }

    @Test
    public void should_downscale_an_image_one_pixel_above_the_optimisation_threshold() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.DOWNSCALED);
        int[] served = dimensionsOf(prepared.bytes());
        assertTrue((long) served[0] * served[1] <= TARGET,
                "served raster " + served[0] + "x" + served[1] + " must be at or below the target of " + TARGET);
        assertTrue((long) served[0] * served[1] < PIXELS, "a downscale that does not shrink the raster is not a downscale");
        assertEquals(prepared.originalWidth(), 400, "the theme's own geometry must survive the downscale");
        assertEquals(prepared.originalHeight(), 300);
    }

    @Test
    public void should_keep_transparency_when_it_downscales() {
        // given a fixture whose left half is fully transparent
        byte[] source = png(400, 300, true);

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, 1_000_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.DOWNSCALED);
        BufferedImage served = decode(prepared.bytes());
        assertTrue(served.getColorModel().hasAlpha(), "a transparent background must not be flattened onto a colour");
        int left = served.getRGB(served.getWidth() / 4, served.getHeight() / 2) >>> 24;
        int right = served.getRGB(served.getWidth() * 3 / 4, served.getHeight() / 2) >>> 24;
        assertEquals(left, 0, "a pixel that was transparent must still be transparent");
        assertEquals(right, 255, "a pixel that was opaque must still be opaque");
    }

    @Test
    public void should_not_substitute_an_image_exactly_at_the_hard_cap() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, PIXELS).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "the hard cap is exclusive: substitution starts one pixel above it");
    }

    @Test
    public void should_substitute_a_blank_background_above_the_hard_cap() {
        // given
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground prepared = optimizer(PIXELS + 1, PIXELS - 1).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.SUBSTITUTED);
        assertNotSame(prepared.bytes(), source);
        int[] served = dimensionsOf(prepared.bytes());
        assertEquals(served[0], BackgroundImageOptimizer.BLANK_WIDTH);
        assertEquals(served[1], BackgroundImageOptimizer.BLANK_HEIGHT);
        assertFalse(decode(prepared.bytes()).getColorModel().hasAlpha(), "the substitute is opaque white, not a transparent hole");
        assertEquals(prepared.originalWidth(), 400, "the page must not move because the background was replaced");
        assertEquals(prepared.originalHeight(), 300);
    }

    /**
     * A 65535x65535 header is 4.29 billion pixels and 33 bytes of input. It must be rejected from the
     * header alone - the whole point of reading the header before anything is decoded.
     */
    @Test
    public void should_substitute_a_decompression_bomb_it_never_decodes() {
        // given
        byte[] bomb = pngHeaderOnly(65535, 65535);

        // when
        PreparedBackground prepared = optimizer(12_000_000L, 30_000_000L).optimize(bomb, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.SUBSTITUTED);
        assertEquals(prepared.originalWidth(), 65535);
    }

    @Test
    public void should_leave_a_jpeg_above_the_hard_cap_untouched() {
        // given
        byte[] source = jpeg(400, 300);

        // when
        PreparedBackground prepared = optimizer(1_000L, 1_000L).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNCHANGED, "OpenPDF embeds JPEG compressed, so capping it costs a background and saves no heap");
        assertSame(prepared.bytes(), source);
    }

    @Test
    public void should_pass_an_unreadable_image_through_unchanged() {
        // given
        byte[] source = "this is not an image".getBytes();

        // when
        PreparedBackground prepared = optimizer(PIXELS - 1, PIXELS - 1).optimize(source, CORRELATION_ID);

        // then
        assertEquals(prepared.verdict(), Verdict.UNREADABLE);
        assertSame(prepared.bytes(), source, "an image we cannot judge is one we must not change");
    }

    @Test
    public void should_serve_one_transform_to_every_render_of_the_same_background() {
        // given two equal byte arrays that are not the same object
        BackgroundImageOptimizer optimizer = optimizer(PIXELS - 1, 1_000_000L);
        byte[] first = png(400, 300, false);
        byte[] second = first.clone();

        // when
        PreparedBackground one = optimizer.optimize(first, CORRELATION_ID);
        PreparedBackground two = optimizer.optimize(second, CORRELATION_ID);

        // then
        assertEquals(one.verdict(), Verdict.DOWNSCALED);
        assertSame(two, one, "a second render of the same background must not pay the transform again");
    }

    @Test
    public void should_not_retain_an_image_it_did_not_transform() {
        // given
        BackgroundImageOptimizer optimizer = optimizer(PIXELS + 1, 1_000_000L);
        byte[] source = png(400, 300, false);

        // when
        PreparedBackground one = optimizer.optimize(source, CORRELATION_ID);
        PreparedBackground two = optimizer.optimize(source.clone(), CORRELATION_ID);

        // then
        assertEquals(one.verdict(), Verdict.UNCHANGED);
        assertNotSame(two, one, "caching an untouched image would pin a full-size source for the life of the JVM");
    }

    @Test
    public void should_evict_the_eldest_entry_when_the_cache_is_full() {
        // given a cache with room for one transformed background
        BackgroundImageOptimizer optimizer = new BackgroundImageOptimizer(true, PIXELS - 1, TARGET, 1_000_000L, 1);
        byte[] first = png(400, 300, false);
        byte[] second = png(400, 300, true);

        // when
        PreparedBackground firstRun = optimizer.optimize(first, CORRELATION_ID);
        optimizer.optimize(second, CORRELATION_ID);
        PreparedBackground firstAgain = optimizer.optimize(first.clone(), CORRELATION_ID);

        // then
        assertEquals(firstRun.verdict(), Verdict.DOWNSCALED);
        assertNotSame(firstAgain, firstRun, "the cache is bounded, so the eldest transform must be gone");
    }

    @Test
    public void should_hand_every_concurrent_render_of_one_cold_background_the_same_result() throws Exception {
        // given
        int threads = 12;
        BackgroundImageOptimizer optimizer = optimizer(PIXELS - 1, 1_000_000L);
        byte[] source = png(400, 300, false);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<PreparedBackground> results = Collections.synchronizedList(new ArrayList<>());

        // when
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    results.add(optimizer.optimize(source.clone(), CORRELATION_ID));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "every render thread must finish");

        // then
        assertEquals(results.size(), threads);
        Set<PreparedBackground> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(results);
        assertEquals(distinct.size(), 1, threads + " concurrent cold renders must pay one transform, not " + distinct.size());
    }

    @Test
    public void should_degrade_rather_than_throw_on_a_hostile_cache_size() {
        // given
        byte[] source = png(400, 300, false);

        // when
        Map<Integer, Verdict> verdicts = new java.util.LinkedHashMap<>();
        for (int maxCacheEntries : new int[]{0, -5}) {
            verdicts.put(maxCacheEntries,
                    new BackgroundImageOptimizer(true, PIXELS - 1, TARGET, 1_000_000L, maxCacheEntries)
                            .optimize(source, CORRELATION_ID).verdict());
        }

        // then
        assertEquals(verdicts.size(), 2, "both hostile values must have been exercised");
        verdicts.forEach((maxCacheEntries, verdict) ->
                assertEquals(verdict, Verdict.DOWNSCALED, "maxCacheEntries=" + maxCacheEntries + " must still render"));
    }

    @Test
    public void should_report_itself_disabled_when_the_feature_flag_is_off() {
        assertFalse(new BackgroundImageOptimizer(false, PIXELS - 1, TARGET, 1_000_000L, 16).isEnabled());
        assertTrue(optimizer(PIXELS - 1, 1_000_000L).isEnabled());
    }

}
