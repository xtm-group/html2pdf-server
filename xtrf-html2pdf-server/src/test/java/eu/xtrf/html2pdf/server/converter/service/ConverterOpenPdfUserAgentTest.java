package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.exception.ProcessingFailureException;
import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.util.Map;
import java.util.stream.Collectors;

import static eu.xtrf.test.assertions.ExceptionAssertions.assertException;
import static eu.xtrf.test.assertions.ExceptionAssertions.catchException;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ConverterOpenPdfUserAgentTest {

    String resourcePath = "c/system\\temp/f829a2090fea6";
    String sampleStyleCss = "* {font-family: 'Roboto'; font-size: 11px;}";
    ConverterOpenPdfUserAgent converterOpenPdfUserAgent = new ConverterOpenPdfUserAgent(null, null, resourcePath, "xtrf.test.domain", sampleStyleCss, Map.of(), false);

    byte[] preparedBytes = "prepared-background-bytes".getBytes();
    String sentinelFileName = InlineImageRewriter.SENTINEL_PREFIX + "0" + InlineImageRewriter.SENTINEL_SUFFIX;
    ConverterOpenPdfUserAgent agentWithBackground = new ConverterOpenPdfUserAgent(null, null, resourcePath, "xtrf.test.domain", sampleStyleCss,
            Map.of(sentinelFileName, new PreparedBackground(preparedBytes, 400, 300, PreparedBackground.Verdict.DOWNSCALED)), false);

    @Test
    public void should_throw_exception_because_external_resource_url_is_not_allowed() {
        // given
        String uri = "https://example.com/file.jpg";

        // when
        Exception exception = catchException(() -> converterOpenPdfUserAgent.resolveAndOpenStream(uri));

        // then
        assertException(ProcessingFailureException.class, exception);
        assertLinesMatch(singletonList(".* leads to an unauthorized source."), singletonList(exception.getMessage()));
    }

    @Test
    public void should_not_throw_unauthorized_source_exception_because_localhost_resource_url_is_allowed() {
        // given
        String uri = "http://localhost/file.jpg";

        // when
        Exception exception = catchException(() -> converterOpenPdfUserAgent.resolveAndOpenStream(uri));

        // then (we do not expect unauthorized source only not existing file)
        assertTrue(exception.getCause() instanceof FileNotFoundException || exception.getCause() instanceof ConnectException);
    }

    @Test
    public void should_throw_exception_because_local_file_url_is_not_allowed() {
        // given
        String uri = "file:" + this.getClass().getClassLoader().getResource("test_file.txt").getPath();

        // when
        Exception exception = catchException(() -> converterOpenPdfUserAgent.resolveAndOpenStream(uri));

        // then
        assertException(ProcessingFailureException.class, exception);
        assertLinesMatch(singletonList(".* leads to an unauthorized source."), singletonList(exception.getMessage()));
    }

    @Test
    public void should_not_throw_unauthorized_source_exception_because_system_tmp_file_resource_url_is_allowed() {
        // given
        String uri = "file:" + File.separator + resourcePath + File.separator + "styles.css";

        // when
        InputStream inputStream = converterOpenPdfUserAgent.resolveAndOpenStream(uri);

        // then
        String result = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
        assertEquals(result, sampleStyleCss);
    }

    @Test
    public void should_serve_a_prepared_background_from_memory_for_its_resolved_uri() throws Exception {
        // given
        String uri = "file:" + File.separator + resourcePath + File.separator + sentinelFileName;

        // when
        InputStream inputStream = agentWithBackground.resolveAndOpenStream(uri);

        // then
        assertEquals(IOUtils.toByteArray(inputStream), preparedBytes);
    }

    @Test
    public void should_serve_a_prepared_background_for_the_relative_name_written_into_the_stylesheet() throws Exception {
        // when
        InputStream inputStream = agentWithBackground.resolveAndOpenStream(sentinelFileName);

        // then (the renderer asks under both the relative name and the resolved URI)
        assertEquals(IOUtils.toByteArray(inputStream), preparedBytes);
    }

    /**
     * XDEV-6073. The guard on the prepared-background branch sitting above the authorized-source
     * check. It matches the exact URIs this render registered; a prefix, suffix or contains match
     * would let any remote URL shaped like a sentinel skip the check entirely.
     */
    @Test
    public void should_still_reject_an_external_url_that_merely_looks_like_a_prepared_background() {
        // given
        String uri = "https://evil.example.com/" + sentinelFileName;

        // when
        Exception exception = catchException(() -> agentWithBackground.resolveAndOpenStream(uri));

        // then
        assertException(ProcessingFailureException.class, exception);
        assertLinesMatch(singletonList(".* leads to an unauthorized source."), singletonList(exception.getMessage()));
    }

    @Test
    public void should_still_reject_an_external_url_when_no_background_was_prepared() {
        // given
        String uri = "https://evil.example.com/" + sentinelFileName;

        // when
        Exception exception = catchException(() -> converterOpenPdfUserAgent.resolveAndOpenStream(uri));

        // then
        assertException(ProcessingFailureException.class, exception);
        assertLinesMatch(singletonList(".* leads to an unauthorized source."), singletonList(exception.getMessage()));
    }
}