package eu.xtrf.html2pdf.server.converter.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * The facts about an inline background image that decide how OpenPDF will treat it, read through
 * ImageIO so that no image format parsing is maintained here. Only the header is read - the width,
 * the height and the format name together cost tens of bytes off the front of the source - so this
 * is safe to point at an image far too large to hold in memory.
 */
class BackgroundImageFacts {

    private final int width;
    private final int height;
    private final String formatName;

    private BackgroundImageFacts(int width, int height, String formatName) {
        this.width = width;
        this.height = height;
        this.formatName = formatName;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * Computed in long because a 65535x65535 header overflows int and would make a decompression
     * bomb look small enough to pass any pixel cap.
     */
    long pixels() {
        return (long) width * height;
    }

    /**
     * OpenPDF rasterizes PNG, GIF, BMP and TIFF through ImageIO.read and so pays one full raster for
     * the source, while JPEG and JPEG2000 are embedded as compressed bytes and cost nothing per
     * pixel (openpdf-1.3.11 com/lowagie/text/ImageLoader.java:135-196). Capping a format it never
     * rasterizes would blank a legitimate background for no heap reason.
     */
    boolean decodedAsRaster() {
        return !("jpeg".equals(formatName) || "jpg".equals(formatName) || "jpeg2000".equals(formatName));
    }

    String describe() {
        return width + "x" + height + " " + formatName;
    }

    /**
     * Returns null when the bytes carry no image this JVM can read; callers must then pass the
     * original through unchanged, because an image we cannot inspect is one we cannot judge.
     *
     * The stream is constructed rather than obtained from {@code ImageIO.createImageInputStream},
     * which honours {@code ImageIO.getUseCache()} - true by default - and would spool every
     * background through a temporary file, making a render depend on a writable temp directory this
     * server does not configure.
     *
     * Metadata is ignored, and that is what keeps the read O(1): a reader asked for image metadata
     * must scan to the end of the image, and over an in-memory stream every scanned byte is retained
     * in heap until the stream closes. Ignoring it leaves this read costing the header alone, which
     * is the whole point of deciding the pixel cap before anything is decoded.
     */
    static BackgroundImageFacts read(byte[] imageBytes) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return fromReader(reader);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static BackgroundImageFacts fromReader(ImageReader reader) throws IOException {
        return new BackgroundImageFacts(reader.getWidth(0), reader.getHeight(0),
                reader.getFormatName().toLowerCase(Locale.ROOT));
    }

}
