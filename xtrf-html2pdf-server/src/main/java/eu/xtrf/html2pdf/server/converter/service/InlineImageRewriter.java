package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CharSequenceReader;
import org.apache.commons.io.input.ReaderInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Lifts inline {@code data:} background images out of the theme stylesheet and replaces each with a
 * sentinel file name the user agent serves from memory.
 *
 * The base64 text of a data URI is the larger of the two heap terms a background costs: Flying
 * Saucer copies it into the parsed stylesheet, the CSS declaration and the decoded image, so the
 * payload is never copied into a String here - it is decoded straight out of the caller's own
 * character sequence.
 */
@Slf4j
@Component
class InlineImageRewriter {

    static final String SENTINEL_PREFIX = "xtrf-background-";
    static final String SENTINEL_SUFFIX = ".png";

    private static final String URL_TOKEN = "url(";
    private static final String DATA_SCHEME = "data:";
    private static final String BASE64_MARKER = ";base64,";
    private static final String UNKNOWN_CORRELATION_ID = "unknown";

    private final BackgroundImageOptimizer backgroundImageOptimizer;

    @Autowired
    InlineImageRewriter(BackgroundImageOptimizer backgroundImageOptimizer) {
        this.backgroundImageOptimizer = backgroundImageOptimizer;
    }

    /**
     * Returns the caller's own stylesheet instance, and no backgrounds, whenever nothing was
     * rewritten - so a render that carries no inline background allocates nothing extra.
     */
    PreparedStyles rewrite(String css, String resourcePath) {
        if (css == null || !backgroundImageOptimizer.isEnabled()) {
            return new PreparedStyles(css, Map.of());
        }
        String correlationId = correlationId(resourcePath);
        StringBuilder rewritten = null;
        Map<String, PreparedBackground> backgrounds = null;
        int copiedUpTo = 0;
        int searchFrom = 0;
        while (true) {
            int urlAt = css.indexOf(URL_TOKEN, searchFrom);
            if (urlAt < 0) {
                break;
            }
            int valueAt = skipWhitespace(css, urlAt + URL_TOKEN.length());
            char quote = quoteAt(css, valueAt);
            int schemeAt = quote == 0 ? valueAt : valueAt + 1;
            int closeAt = css.indexOf(')', schemeAt);
            if (closeAt < 0) {
                break;
            }
            searchFrom = closeAt + 1;
            if (!css.startsWith(DATA_SCHEME, schemeAt)) {
                continue;
            }
            int markerAt = css.indexOf(BASE64_MARKER, schemeAt);
            if (markerAt < 0 || markerAt > closeAt) {
                continue;
            }
            int payloadFrom = markerAt + BASE64_MARKER.length();
            int payloadTo = trimEnd(css, payloadFrom, closeAt, quote);
            byte[] decoded = decodeBase64(css, payloadFrom, payloadTo, correlationId);
            if (decoded == null) {
                continue;
            }
            if (rewritten == null) {
                rewritten = new StringBuilder(css.length() - (payloadTo - payloadFrom));
                backgrounds = new HashMap<>();
            }
            String fileName = SENTINEL_PREFIX + backgrounds.size() + SENTINEL_SUFFIX;
            backgrounds.put(fileName, backgroundImageOptimizer.optimize(decoded, correlationId));
            rewritten.append(css, copiedUpTo, urlAt).append(URL_TOKEN).append(fileName).append(')');
            copiedUpTo = closeAt + 1;
        }
        if (rewritten == null) {
            return new PreparedStyles(css, Map.of());
        }
        rewritten.append(css, copiedUpTo, css.length());
        return new PreparedStyles(rewritten.toString(), Map.copyOf(backgrounds));
    }

    /**
     * Decodes the payload region in place. {@code CharSequenceReader} takes a view bounded by index
     * rather than a substring, so the base64 text is never copied into a second String.
     */
    private byte[] decodeBase64(String css, int from, int to, String correlationId) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream((to - from) / 4 * 3 + 3);
        try (InputStream base64 = Base64.getMimeDecoder().wrap(ReaderInputStream.builder()
                .setReader(new CharSequenceReader(css, from, to))
                .setCharset(StandardCharsets.US_ASCII)
                .get())) {
            IOUtils.copy(base64, decoded);
            return decoded.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("[{}] An inline background image could not be decoded; leaving it in the stylesheet unchanged.", correlationId, e);
            return null;
        }
    }

    private static int skipWhitespace(String css, int from) {
        int at = from;
        while (at < css.length() && Character.isWhitespace(css.charAt(at))) {
            at++;
        }
        return at;
    }

    private static char quoteAt(String css, int at) {
        if (at >= css.length()) {
            return 0;
        }
        char candidate = css.charAt(at);
        return candidate == '\'' || candidate == '"' ? candidate : 0;
    }

    private static int trimEnd(String css, int from, int to, char quote) {
        int at = to;
        while (at > from) {
            char candidate = css.charAt(at - 1);
            if (candidate != quote && !Character.isWhitespace(candidate)) {
                break;
            }
            at--;
        }
        return at;
    }

    /**
     * The resources directory is named after the request hash, so its last segment makes a warning
     * traceable to one conversion without changing any signature on the render path.
     */
    private static String correlationId(String resourcePath) {
        if (resourcePath == null) {
            return UNKNOWN_CORRELATION_ID;
        }
        String normalised = resourcePath.replace('\\', '/');
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        String segment = normalised.substring(normalised.lastIndexOf('/') + 1);
        return segment.isEmpty() ? UNKNOWN_CORRELATION_ID : segment;
    }

    /**
     * The stylesheet to hand to the renderer, and the prepared images it must serve, keyed by the
     * sentinel file name written into that stylesheet.
     */
    record PreparedStyles(String css, Map<String, PreparedBackground> backgrounds) {
    }

}
