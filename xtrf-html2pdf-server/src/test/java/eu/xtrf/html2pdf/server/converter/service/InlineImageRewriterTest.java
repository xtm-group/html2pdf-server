package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import eu.xtrf.html2pdf.server.converter.service.InlineImageRewriter.PreparedStyles;
import org.testng.annotations.Test;

import java.util.Base64;

import static eu.xtrf.html2pdf.server.converter.service.BackgroundImageTestFixtures.png;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * XDEV-6073. The stylesheet XTRF sends carries the theme background inline as a data URI
 * (xtrf-commons DocumentTemplateToHtmlResolverServiceImpl.buildStylesWithBackgroundImage), and that
 * base64 text is the larger of the two heap terms a background costs. These tests pin what the
 * rewriter does to it and, just as importantly, what it leaves alone.
 */
public class InlineImageRewriterTest {

    private static final String RESOURCE_PATH = "/tmp/f829a2090fea6";

    private static InlineImageRewriter rewriter(boolean enabled) {
        return new InlineImageRewriter(new BackgroundImageOptimizer(enabled, 12_000_000L, 8_700_000L, 30_000_000L, 16));
    }

    /**
     * Mirrors the production shape exactly, down to the fixed {@code image/gif} media type XTRF
     * writes whatever the real format is.
     */
    private static String themeCss(byte[]... backgrounds) {
        StringBuilder css = new StringBuilder("* {font-family: 'Roboto'; font-size: 11px;}\n");
        for (byte[] background : backgrounds) {
            css.append("@page {\n")
                    .append("  background-image: url(data:image/gif;base64,")
                    .append(Base64.getEncoder().encodeToString(background))
                    .append(");\n")
                    .append("  background-repeat: no-repeat;\n")
                    .append("}\n");
        }
        return css.toString();
    }

    @Test
    public void should_lift_an_inline_background_out_of_the_stylesheet() {
        // given
        byte[] background = png(64, 48, false);
        String css = themeCss(background);

        // when
        PreparedStyles prepared = rewriter(true).rewrite(css, RESOURCE_PATH);

        // then
        assertEquals(prepared.backgrounds().size(), 1);
        assertFalse(prepared.css().contains("data:"), "the base64 payload must not survive into the stylesheet handed to the renderer");
        assertTrue(prepared.css().contains("url(" + InlineImageRewriter.SENTINEL_PREFIX + "0" + InlineImageRewriter.SENTINEL_SUFFIX + ")"),
                "the sentinel must be a plain relative file name: a custom URI scheme is dropped silently by Flying Saucer");
        assertTrue(prepared.css().contains("background-repeat: no-repeat;"), "the surrounding declarations must be untouched");
        assertTrue(prepared.css().length() < css.length() / 2, "lifting the payload out must make the stylesheet smaller");
    }

    @Test
    public void should_decode_the_payload_byte_for_byte() {
        // given
        byte[] background = png(64, 48, true);

        // when
        PreparedStyles prepared = rewriter(true).rewrite(themeCss(background), RESOURCE_PATH);

        // then
        PreparedBackground served = prepared.backgrounds().values().iterator().next();
        assertEquals(served.verdict(), PreparedBackground.Verdict.UNCHANGED);
        assertEquals(served.bytes(), background, "a streaming base64 decode must be byte-exact, not off by a pad character");
    }

    @Test
    public void should_give_every_background_its_own_sentinel() {
        // given
        String css = themeCss(png(64, 48, false), png(32, 24, false));

        // when
        PreparedStyles prepared = rewriter(true).rewrite(css, RESOURCE_PATH);

        // then
        assertEquals(prepared.backgrounds().size(), 2);
        assertEquals(prepared.backgrounds().keySet().size(), 2, "two backgrounds must not collide on one sentinel name");
        prepared.backgrounds().keySet().forEach(name ->
                assertTrue(prepared.css().contains("url(" + name + ")"), "the stylesheet must reference " + name));
    }

    @Test
    public void should_handle_a_quoted_data_uri() {
        // given
        byte[] background = png(64, 48, false);
        String css = "@page { background-image: url('data:image/gif;base64,"
                + Base64.getEncoder().encodeToString(background) + "'); }";

        // when
        PreparedStyles prepared = rewriter(true).rewrite(css, RESOURCE_PATH);

        // then
        assertEquals(prepared.backgrounds().size(), 1);
        assertEquals(prepared.backgrounds().values().iterator().next().bytes(), background);
    }

    @Test
    public void should_return_the_callers_own_stylesheet_when_there_is_nothing_to_lift() {
        // given
        String css = "* {font-family: 'Roboto';} .logo { background-image: url(logo.png); }";

        // when
        PreparedStyles prepared = rewriter(true).rewrite(css, RESOURCE_PATH);

        // then
        assertSame(prepared.css(), css, "a render with no inline background must allocate no second copy of the stylesheet");
        assertTrue(prepared.backgrounds().isEmpty());
    }

    /**
     * The rollback lever. With the flag off the caller's stylesheet is handed on untouched and the
     * background map is empty, so the renderer takes exactly the path it took before this change -
     * on the same binary.
     */
    @Test
    public void should_leave_the_stylesheet_alone_when_the_feature_is_disabled() {
        // given
        String css = themeCss(png(64, 48, false));

        // when
        PreparedStyles prepared = rewriter(false).rewrite(css, RESOURCE_PATH);

        // then
        assertSame(prepared.css(), css);
        assertTrue(prepared.backgrounds().isEmpty());
        assertTrue(prepared.css().contains("data:image/gif;base64,"), "disabled means the data URI reaches the renderer exactly as it did before the fix");
    }

    @Test
    public void should_survive_a_stylesheet_that_is_absent_or_malformed() {
        // given
        InlineImageRewriter rewriter = rewriter(true);

        // when
        PreparedStyles absent = rewriter.rewrite(null, RESOURCE_PATH);
        PreparedStyles unterminated = rewriter.rewrite("@page { background-image: url(data:image/gif;base64,QQ", RESOURCE_PATH);
        PreparedStyles undecodable = rewriter.rewrite("@page { background-image: url(data:image/gif;base64,)}", RESOURCE_PATH);

        // then
        assertNull(absent.css());
        assertTrue(absent.backgrounds().isEmpty());
        assertNotNull(unterminated.css());
        assertTrue(unterminated.backgrounds().isEmpty(), "an unterminated url() must be left where it is, not half-rewritten");
        assertNotNull(undecodable.css());
    }

    /**
     * CSS function names are case-insensitive and Flying Saucer's lexer is generated with
     * {@code %ignorecase}, so a hand-edited theme that writes {@code URL(} or {@code Url(} still puts
     * its background through the rasterizer. The rewriter must lift those too, or the guard is
     * bypassed by a change of case.
     */
    @Test
    public void should_lift_a_background_whose_url_token_is_not_lowercase() {
        // given
        byte[] upper = png(64, 48, false);
        byte[] mixed = png(32, 24, false);
        String css = "@page { background-image: URL(data:image/gif;base64," + Base64.getEncoder().encodeToString(upper) + "); }\n"
                + "body { background-image: Url('data:image/gif;base64," + Base64.getEncoder().encodeToString(mixed) + "'); }";

        // when
        PreparedStyles prepared = rewriter(true).rewrite(css, RESOURCE_PATH);

        // then
        assertEquals(prepared.backgrounds().size(), 2);
        assertFalse(prepared.css().contains("data:"), "an upper- or mixed-case url( token must not let the payload through to the renderer");
        assertTrue(prepared.css().contains("url(" + InlineImageRewriter.SENTINEL_PREFIX + "0" + InlineImageRewriter.SENTINEL_SUFFIX + ")"));
        assertTrue(prepared.css().contains("url(" + InlineImageRewriter.SENTINEL_PREFIX + "1" + InlineImageRewriter.SENTINEL_SUFFIX + ")"));
        assertEquals(prepared.backgrounds().get(InlineImageRewriter.SENTINEL_PREFIX + "0" + InlineImageRewriter.SENTINEL_SUFFIX).bytes(), upper);
        assertEquals(prepared.backgrounds().get(InlineImageRewriter.SENTINEL_PREFIX + "1" + InlineImageRewriter.SENTINEL_SUFFIX).bytes(), mixed);
    }

}
