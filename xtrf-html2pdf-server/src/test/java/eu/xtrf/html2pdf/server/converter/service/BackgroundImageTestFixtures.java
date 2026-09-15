package eu.xtrf.html2pdf.server.converter.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * Image fixtures for the background-optimisation tests, generated in the JVM rather than committed
 * as binaries: the repository carries no PNG today and a customer letterhead may never enter it.
 *
 * Deliberately not named {@code *Test}, so the surefire include patterns never treat it as a suite.
 */
final class BackgroundImageTestFixtures {

    private BackgroundImageTestFixtures() {
    }

    /**
     * A flat-colour image, which compresses to a few kilobytes at any size. Use it whenever the
     * assertion is about dimensions or verdicts, never when it is about how much of the source a
     * read consumes - for that see {@link #noisePng}.
     */
    static byte[] png(int width, int height, boolean withAlpha) {
        BufferedImage image = new BufferedImage(width, height, withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, withAlpha && x < width / 2 ? 0x00000000 : 0xFF3366CC);
            }
        }
        return encode(image, "png");
    }

    /**
     * Random pixels, so the PNG cannot be compressed. A read that scans the whole image therefore
     * costs the whole source, which is what the header-read guarantee has to be measured against.
     */
    static byte[] noisePng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260915L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return encode(image, "png");
    }

    static byte[] jpeg(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    /**
     * A PNG signature and a valid IHDR chunk, and nothing after it - no palette, no IDAT, no IEND.
     *
     * Two properties make this the sharpest fixture in the set. It states dimensions no machine can
     * hold, so it is the only way to exercise the pixel arithmetic of a decompression bomb; and any
     * reader that walks past the header to collect metadata hits the truncation and fails, so a read
     * that still reports the dimensions has provably read the header alone.
     */
    static byte[] pngHeaderOnly(int width, int height) {
        try {
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            DataOutputStream chunkOut = new DataOutputStream(chunk);
            chunkOut.writeBytes("IHDR");
            chunkOut.writeInt(width);
            chunkOut.writeInt(height);
            chunkOut.writeByte(8);
            chunkOut.writeByte(2);
            chunkOut.writeByte(0);
            chunkOut.writeByte(0);
            chunkOut.writeByte(0);
            byte[] chunkBytes = chunk.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(chunkBytes);

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            DataOutputStream out = new DataOutputStream(png);
            out.writeInt(chunkBytes.length - "IHDR".length());
            out.write(chunkBytes);
            out.writeInt((int) crc.getValue());
            return png.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage decode(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new IllegalArgumentException("fixture bytes carry no readable image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads dimensions from the header only, so a test can size an image far larger than the fixture
     * it was asked to compare against without decoding either.
     */
    static int[] dimensionsOf(byte[] imageBytes) {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("fixture bytes carry no readable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, encoded)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
            return encoded.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
