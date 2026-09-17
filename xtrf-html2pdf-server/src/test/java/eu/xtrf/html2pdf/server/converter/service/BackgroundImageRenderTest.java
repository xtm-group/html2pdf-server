package eu.xtrf.html2pdf.server.converter.service;

import com.lowagie.text.pdf.PRIndirectReference;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import eu.xtrf.html2pdf.server.converter.dto.ConvertDocumentRequestDto;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.resource.ImageResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.png;
import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.pngHeaderOnly;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * XDEV-6073, acceptance criteria 1-3, at the layer the crash happens on: the real
 * {@link Html2PdfConverterServiceImpl}, the real {@link RendererProviderImpl}, a real Flying Saucer
 * {@code ITextRenderer} and real OpenPDF, producing a real PDF that is then parsed back.
 *
 * Nothing here is mocked, and nothing asserts on a log line or on source text - every assertion is
 * made against the image XObjects OpenPDF actually wrote into the document, which is the only place
 * the size of the raster it decoded becomes observable.
 *
 * The page count is 15, the number the QA reproduction used ("a project with 15 or more language
 * combinations"). The stylesheet is LINKED, never inlined in a style block: the rewriter only ever
 * sees the linked stylesheet, so an inlined fixture would let every assertion below pass green while
 * testing nothing.
 */
public class BackgroundImageRenderTest {

    private static final int PAGES = 15;
    private static final String SENTINEL = InlineImageRewriter.SENTINEL_PREFIX + "0" + InlineImageRewriter.SENTINEL_SUFFIX;
    private static final String SYSTEM_DOMAIN = "xtrf.test.domain";

    /** 400x300 = 120 000 pixels, the unit the three thresholds below are expressed against. */
    private static final long PIXELS = 120_000L;
    private static final long TARGET = 30_000L;

    private Path tempDirectory;

    @BeforeClass
    public void createTempDirectory() throws IOException {
        tempDirectory = Files.createTempDirectory("xdev6073-render");
    }

    @AfterClass
    public void removeTempDirectory() throws IOException {
        if (tempDirectory != null) {
            try (Stream<Path> paths = Files.walk(tempDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    // ---------------------------------------------------------------- acceptance criterion 2

    @Test
    public void should_render_the_theme_background_into_the_document() throws Exception {
        // given
        byte[] background = png(400, 300, false);

        // when
        PdfFacts facts = render(optimizer(true, PIXELS + 1, 1_000_000L), themeCss(background, "no-repeat"));

        // then
        facts.assertEveryPageWasScanned();
        assertEquals(facts.images.size(), 1, "the background must be written once for the whole document, not once per page");
        assertEquals(facts.images.get(0), new int[]{400, 300}, "an ordinary background must reach the page at its own resolution");
    }

    /**
     * The control that gives the assertion above its meaning. A malformed {@code url()} is dropped by
     * Flying Saucer in silence, so "the render succeeded" proves nothing on its own - only the
     * difference between this row and the one above does.
     */
    @Test
    public void should_render_no_image_at_all_when_the_theme_carries_no_background() throws Exception {
        // when
        PdfFacts facts = render(optimizer(true, PIXELS + 1, 1_000_000L), themeCssWithoutBackground());

        // then
        facts.assertEveryPageWasScanned();
        assertEquals(facts.images.size(), 0, "with no background declared there must be no image in the PDF");
    }

    // ---------------------------------------------------------------- acceptance criterion 1

    /**
     * The fail-before / pass-after pair, on one binary, toggled by the feature's own property.
     *
     * With the optimizer off, {@link InlineImageRewriter} hands the caller's stylesheet back
     * untouched and the data URI travels to OpenPDF exactly as it did before this change, so OpenPDF
     * rasterizes every source pixel - the mechanism of the heap exhaustion. With it on, the raster
     * OpenPDF is given is bounded by the target.
     */
    @Test
    public void should_bound_the_raster_openpdf_decodes_for_an_oversized_background() throws Exception {
        // given
        byte[] background = png(400, 300, false);
        String css = themeCss(background, "no-repeat");

        // when
        PdfFacts before = render(optimizer(false, PIXELS - 1, 1_000_000L), css);
        PdfFacts after = render(optimizer(true, PIXELS - 1, 1_000_000L), css);

        // then
        before.assertEveryPageWasScanned();
        after.assertEveryPageWasScanned();
        assertEquals(before.images.size(), 1);
        assertEquals(after.images.size(), 1);
        assertEquals(before.images.get(0), new int[]{400, 300}, "with the fix off OpenPDF must still decode the full source raster");
        long servedAfter = (long) after.images.get(0)[0] * after.images.get(0)[1];
        assertTrue(servedAfter <= TARGET,
                "with the fix on the served raster must be at or below " + TARGET + " pixels, was " + after.images.get(0)[0] + "x" + after.images.get(0)[1]);
        assertTrue(servedAfter * 3 < PIXELS, "the served raster must be materially smaller than the source, not a rounding away from it");
    }

    @Test
    public void should_substitute_a_blank_background_above_the_hard_cap() throws Exception {
        // given
        byte[] background = png(400, 300, false);

        // when
        PdfFacts facts = render(optimizer(true, PIXELS + 1, PIXELS - 1), themeCss(background, "no-repeat"));

        // then
        facts.assertEveryPageWasScanned();
        assertEquals(facts.images.size(), 1, "the page still carries a background, it is simply a blank one");
        assertEquals(facts.images.get(0), new int[]{BackgroundImageOptimizer.BLANK_WIDTH, BackgroundImageOptimizer.BLANK_HEIGHT});
    }

    /**
     * A 65535x65535 header is 33 bytes of input and 4.29 billion pixels. It has to be refused from
     * the header, because handing it to the decoder is the decompression bomb itself.
     */
    @Test
    public void should_render_a_decompression_bomb_header_without_decoding_it() throws Exception {
        // when
        PdfFacts facts = render(optimizer(true, 12_000_000L, 30_000_000L), themeCss(pngHeaderOnly(65535, 65535), "no-repeat"));

        // then
        facts.assertEveryPageWasScanned();
        assertEquals(facts.images.size(), 1);
        assertEquals(facts.images.get(0), new int[]{BackgroundImageOptimizer.BLANK_WIDTH, BackgroundImageOptimizer.BLANK_HEIGHT});
    }

    @Test
    public void should_render_a_repeating_background_exactly_as_it_renders_a_static_one() throws Exception {
        // given
        byte[] background = png(400, 300, false);

        // when
        PdfFacts repeated = render(optimizer(true, PIXELS + 1, 1_000_000L), themeCss(background, "repeat"));
        PdfFacts once = render(optimizer(true, PIXELS + 1, 1_000_000L), themeCss(background, "no-repeat"));

        // then
        repeated.assertEveryPageWasScanned();
        assertEquals(repeated.images.size(), once.images.size(), "background-repeat must not change how many rasters are written");
        assertEquals(repeated.images.get(0), once.images.get(0), "background-repeat must not change the raster that is written");
    }

    /**
     * The no-regression control on the case the product documents as normal: a background the fix
     * decides to leave alone must produce the same document whether the feature is on or off.
     */
    @Test
    public void should_produce_the_same_document_with_the_feature_on_and_off_for_an_ordinary_background() throws Exception {
        // given
        byte[] background = png(400, 300, false);
        String css = themeCss(background, "no-repeat");

        // when
        PdfFacts off = render(optimizer(false, PIXELS + 1, 1_000_000L), css);
        PdfFacts on = render(optimizer(true, PIXELS + 1, 1_000_000L), css);

        // then
        assertEquals(on.images.size(), off.images.size());
        assertEquals(on.images.get(0), off.images.get(0));
        assertEquals(on.pages, off.pages);
        assertEquals(on.byteLength, off.byteLength, "an untouched background must not change the document by a single byte of length");
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Bytes and geometry are a contract pair: the user agent serves a possibly smaller raster and has
     * to restore the size the theme asked for, or a downscaled background silently moves the page.
     *
     * The agent is taken from a renderer built by the real provider, never hand-constructed -
     * scaling reads {@code SharedContext.getDotsPerPixel()} on both sides of the override.
     */
    @Test
    public void should_restore_the_themes_own_geometry_on_a_downscaled_background() throws Exception {
        // given
        byte[] background = png(400, 300, false);
        Path resources = Files.createTempDirectory(tempDirectory, "geometry");
        RendererProviderImpl provider = new RendererProviderImpl(new FontServiceImpl(),
                new InlineImageRewriter(optimizer(true, PIXELS - 1, 1_000_000L)));

        // when
        ITextRenderer renderer = provider.prepareRenderer(resources.toString(), SYSTEM_DOMAIN, themeCss(background, "no-repeat"), false);
        renderer.setDocumentFromString(xhtml(theme().replace("#DOCUMENT_CONTENT", document())), resources.toUri().toURL().toString());
        ConverterOpenPdfUserAgent agent = (ConverterOpenPdfUserAgent) renderer.getSharedContext().getUserAgentCallback();
        ImageResource resource = agent.getImageResource(SENTINEL);

        // then
        assertNotNull(resource.getImage(), "the downscaled raster must still be decodable by the renderer");
        float dotsPerPixel = renderer.getSharedContext().getDotsPerPixel();
        assertEquals(resource.getImage().getWidth(), Math.round(400 * dotsPerPixel), "the laid-out width must follow the theme's image, not the served one");
        assertEquals(resource.getImage().getHeight(), Math.round(300 * dotsPerPixel));
    }

    // ---------------------------------------------------------------- fixtures and plumbing

    private static BackgroundImageOptimizer optimizer(boolean enabled, long optimizeAbovePixels, long maxPixels) {
        return new BackgroundImageOptimizer(enabled, optimizeAbovePixels, TARGET, maxPixels, BackgroundImageOptimizer.DEFAULT_MAX_CACHE_BYTES);
    }

    /**
     * The shape xtrf-commons builds, reproduced rather than invented: an {@code @page} rule whose
     * background is a data URI always labelled {@code image/gif} whatever the real format is
     * (DocumentTemplateToHtmlResolverServiceImpl.buildStylesWithBackgroundImage).
     */
    private static String themeCss(byte[] background, String repeat) {
        return themeCssWithoutBackground()
                + "@page {\n"
                + "  background-image: url(data:image/gif;base64," + Base64.getEncoder().encodeToString(background) + ");\n"
                + "  background-repeat: " + repeat + ";\n"
                + "}\n";
    }

    private static String themeCssWithoutBackground() {
        return "@page { size: A4; margin: 1cm; }\nbody { font-size: 11px; }\n.page { page-break-before: always; }\n";
    }

    /** Mirrors the wrapper xtrf-commons emits, including the linked stylesheet. */
    private static String theme() {
        return "<html><head><title>XDEV-6073</title><link rel=\"stylesheet\" href =\"styles.css\"></head>"
                + "<body><main class=\"main-content\">#DOCUMENT_CONTENT</main></body></html>";
    }

    /**
     * The same normalisation {@code Html2PdfConverterServiceImpl} applies before handing a document
     * to the renderer, so the one test that drives the renderer directly sees the same markup the
     * rest of them reach through the service.
     */
    private static String xhtml(String html) {
        org.jsoup.nodes.Document parsed = org.jsoup.Jsoup.parse(html, "UTF-8");
        parsed.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        return parsed.html();
    }

    private static String document() {
        StringBuilder content = new StringBuilder();
        for (int page = 0; page < PAGES; page++) {
            content.append("<div class=\"").append(page == 0 ? "first" : "page").append("\">Language combination ").append(page + 1).append("</div>");
        }
        return content.toString();
    }

    private PdfFacts render(BackgroundImageOptimizer optimizer, String css) throws Exception {
        FontServiceImpl fontService = new FontServiceImpl();
        Html2PdfConverterServiceImpl service = new Html2PdfConverterServiceImpl(
                new RendererProviderImpl(fontService, new InlineImageRewriter(optimizer)), fontService);
        ConvertDocumentRequestDto request = ConvertDocumentRequestDto.builder()
                .themeContent(theme())
                .documentContent(document())
                .styles(css)
                .clientId("xdev-6073")
                .systemDomain(SYSTEM_DOMAIN)
                .tempDirectoryPath(tempDirectory.toString())
                .build();

        File pdf = service.generatePdfToFile(request);
        try {
            return PdfFacts.of(Files.readAllBytes(pdf.toPath()));
        } finally {
            Files.deleteIfExists(pdf.toPath());
        }
    }

    /**
     * Every image XObject the document actually contains, deduplicated by object number so a raster
     * referenced from fifteen pages counts once, together with the page count the scan covered.
     */
    private static final class PdfFacts {

        private final int pages;
        private final int pagesScanned;
        private final int byteLength;
        private final List<int[]> images;

        private PdfFacts(int pages, int pagesScanned, int byteLength, List<int[]> images) {
            this.pages = pages;
            this.pagesScanned = pagesScanned;
            this.byteLength = byteLength;
            this.images = images;
        }

        static PdfFacts of(byte[] pdfBytes) throws IOException {
            PdfReader reader = new PdfReader(pdfBytes);
            try {
                Map<Integer, int[]> byObjectNumber = new LinkedHashMap<>();
                int scanned = 0;
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    collectImages(reader.getPageN(page), byObjectNumber);
                    scanned++;
                }
                return new PdfFacts(reader.getNumberOfPages(), scanned, pdfBytes.length, new ArrayList<>(byObjectNumber.values()));
            } finally {
                reader.close();
            }
        }

        private static void collectImages(PdfDictionary page, Map<Integer, int[]> byObjectNumber) {
            PdfDictionary resources = page == null ? null : page.getAsDict(PdfName.RESOURCES);
            PdfDictionary xObjects = resources == null ? null : resources.getAsDict(PdfName.XOBJECT);
            if (xObjects == null) {
                return;
            }
            int anonymous = -1;
            for (PdfName name : xObjects.getKeys()) {
                PdfObject reference = xObjects.get(name);
                PdfObject resolved = PdfReader.getPdfObject(reference);
                if (!(resolved instanceof PdfDictionary) || !PdfName.IMAGE.equals(((PdfDictionary) resolved).getAsName(PdfName.SUBTYPE))) {
                    continue;
                }
                PdfDictionary image = (PdfDictionary) resolved;
                int key = reference instanceof PRIndirectReference ? ((PRIndirectReference) reference).getNumber() : anonymous--;
                byObjectNumber.put(key, new int[]{image.getAsNumber(PdfName.WIDTH).intValue(), image.getAsNumber(PdfName.HEIGHT).intValue()});
            }
        }

        /**
         * The coverage self-assertion for this scan. A narrowing that inspected no page would let
         * every image assertion above pass vacuously, so the narrowing states its own coverage.
         */
        void assertEveryPageWasScanned() {
            assertEquals(pagesScanned, pages, "the scan must cover every page of the document");
            assertEquals(pages, PAGES, "the document must carry the page count the QA reproduction used");
            assertTrue(pages >= 1, "a document with no page proves nothing");
        }

    }

}
