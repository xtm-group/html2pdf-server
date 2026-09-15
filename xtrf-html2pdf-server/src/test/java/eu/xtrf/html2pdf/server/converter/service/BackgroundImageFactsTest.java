package eu.xtrf.html2pdf.server.converter.service;

import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.jpeg;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.png;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.pngHeaderOnly;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * XDEV-6073. The header read is what lets the pixel cap be decided before anything is decoded, so
 * these tests pin the two properties the cap depends on: that the read reports the dimensions, and
 * that it reports them without consuming the image body.
 */
public class BackgroundImageFactsTest {

    @Test
    public void should_read_dimensions_and_format_of_a_png() {
        // given
        byte[] source = png(400, 300, false);

        // when
        BackgroundImageFacts facts = BackgroundImageFacts.read(source);

        // then
        assertNotNull(facts);
        assertEquals(facts.width(), 400);
        assertEquals(facts.height(), 300);
        assertEquals(facts.pixels(), 120_000L);
        assertEquals(facts.describe(), "400x300 png");
    }

    /**
     * The guard on the fix's own cost. A reader asked for image metadata has to walk to the end of
     * the image; a reader asked for the header alone stops after the first chunk. This fixture has
     * nothing after the first chunk, so only the second of those two reads can succeed - and the
     * assertion is the returned dimensions, an observable result, not the shape of the call.
     */
    @Test
    public void should_read_the_header_without_walking_into_the_image_body() {
        // given a PNG signature and an IHDR chunk, truncated immediately after it
        byte[] headerOnly = pngHeaderOnly(4000, 4000);
        assertTrue(headerOnly.length < 64, "the fixture must be header-sized, was " + headerOnly.length);

        // when
        BackgroundImageFacts facts = BackgroundImageFacts.read(headerOnly);

        // then
        assertNotNull(facts, "reading past the header truncates and fails; the header alone must still report the dimensions");
        assertEquals(facts.pixels(), 16_000_000L);
    }

    /**
     * 65535x65535 is a legal PNG header and 4 294 836 225 pixels. In int arithmetic that product is
     * negative, so a decompression bomb would pass any pixel cap and reach the decoder.
     */
    @Test
    public void should_not_overflow_the_pixel_count_of_a_decompression_bomb_header() {
        // given
        byte[] bomb = pngHeaderOnly(65535, 65535);

        // when
        BackgroundImageFacts facts = BackgroundImageFacts.read(bomb);

        // then
        assertNotNull(facts);
        assertEquals(facts.pixels(), 4_294_836_225L);
        assertTrue(facts.pixels() > 30_000_000L, "the bomb must land above any pixel cap, not below it");
    }

    @Test
    public void should_report_jpeg_as_a_format_openpdf_never_rasterizes() {
        // given
        byte[] source = jpeg(400, 300);

        // when
        BackgroundImageFacts facts = BackgroundImageFacts.read(source);

        // then
        assertNotNull(facts);
        assertFalse(facts.decodedAsRaster(), "OpenPDF embeds JPEG compressed; capping it would blank a background for no heap reason");
    }

    @Test
    public void should_report_png_as_a_format_openpdf_rasterizes() {
        // given
        byte[] source = png(64, 64, true);

        // when
        BackgroundImageFacts facts = BackgroundImageFacts.read(source);

        // then
        assertNotNull(facts);
        assertTrue(facts.decodedAsRaster());
    }

    @Test
    public void should_return_null_for_bytes_that_carry_no_image() {
        assertNull(BackgroundImageFacts.read("not an image at all".getBytes()));
        assertNull(BackgroundImageFacts.read(new byte[0]));
    }

    /**
     * {@code ImageIO.createImageInputStream} honours {@code ImageIO.getUseCache()}, true by default,
     * and spools its input through a temporary file. This makes that dependency observable rather
     * than asserted: the ImageIO cache directory is replaced by a plain file, so any read that went
     * through {@code createImageInputStream} could not build its stream and would report nothing.
     *
     * The fixture is built before the directory is blocked, because encoding a PNG goes through
     * ImageIO's output side and would fail for a different reason.
     */
    @Test
    public void should_read_the_header_without_a_temporary_file() throws Exception {
        // given
        byte[] source = png(400, 300, false);
        boolean useCache = ImageIO.getUseCache();
        File previousCacheDirectory = ImageIO.getCacheDirectory();
        Path blocked = Files.createTempDirectory("xdev6073-imageio-cache");
        ImageIO.setUseCache(true);
        ImageIO.setCacheDirectory(blocked.toFile());
        Files.delete(blocked);
        Files.write(blocked, new byte[]{0});
        try {
            // when
            BackgroundImageFacts facts = BackgroundImageFacts.read(source);

            // then
            assertNotNull(facts, "the header read must not depend on a writable temporary directory this server never configures");
            assertEquals(facts.pixels(), 120_000L);
        } finally {
            ImageIO.setCacheDirectory(previousCacheDirectory);
            ImageIO.setUseCache(useCache);
            Files.deleteIfExists(blocked);
        }
    }

}
