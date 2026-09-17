package eu.xtrf.html2pdf.server.converter.service;

import eu.xtrf.html2pdf.server.converter.exception.ProcessingFailureException;
import eu.xtrf.html2pdf.server.converter.service.BackgroundImageOptimizer.PreparedBackground;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.xhtmlrenderer.extend.FSImage;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextUserAgent;
import org.xhtmlrenderer.resource.ImageResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

@Slf4j
public class ConverterOpenPdfUserAgent extends ITextUserAgent {

    private static final String STYLES_CSS_FILE_NAME = "styles.css";

    private final String resourcePath;
    private final String systemDomain;
    private final String styleCss;
    private final String styleCssUri;
    private final Map<String, PreparedBackground> backgroundsByUri;
    private final boolean allowResourcesFromDiskAndExternalDomainForGeneratingDocs;

    public ConverterOpenPdfUserAgent(ITextOutputDevice outputDevice, SharedContext sharedContext, String resourcePath, String systemDomain, String styleCss,
                                     Map<String, PreparedBackground> backgrounds, boolean allowResourcesFromDiskAndExternalDomainForGeneratingDocs) {
        super(outputDevice);
        setSharedContext(sharedContext);
        this.resourcePath = resourcePath;
        this.systemDomain = systemDomain;
        this.styleCss = styleCss;
        this.styleCssUri = buildExpectedStylesCssUri(resourcePath);
        this.backgroundsByUri = indexByUri(resourcePath, backgrounds);
        this.allowResourcesFromDiskAndExternalDomainForGeneratingDocs = allowResourcesFromDiskAndExternalDomainForGeneratingDocs;
    }

    /**
     * Restores the size the theme asked for on the laid-out image. A background arrives as an
     * {@code @page} rule with no {@code background-size}, so Flying Saucer takes both the painted
     * size and the repeat pitch from the image's own pixel dimensions - a served image that was
     * downscaled or substituted would otherwise move the page. {@code super} has already returned a
     * clone, and scaling only writes the plain width and height, so no raster is touched here.
     */
    @Override
    public ImageResource getImageResource(String uri) {
        ImageResource resource = super.getImageResource(uri);
        PreparedBackground prepared = preparedBackgroundFor(uri);
        if (prepared == null) {
            return resource;
        }
        FSImage image = resource.getImage();
        if (image == null) {
            log.warn("Background image {} could not be decoded by the renderer; the page renders without it.", removeResourceSubPath(uri));
            return resource;
        }
        if (prepared.originalWidth() > 0 && prepared.originalHeight() > 0) {
            float dotsPerPixel = getSharedContext().getDotsPerPixel();
            image.scale(Math.round(prepared.originalWidth() * dotsPerPixel), Math.round(prepared.originalHeight() * dotsPerPixel));
        }
        return resource;
    }

    @Override
    protected InputStream resolveAndOpenStream(String uri) {
        if (isStylesCssFileUri(uri)) {
            return IOUtils.toInputStream(styleCss, Charset.defaultCharset());
        }
        PreparedBackground prepared = preparedBackgroundFor(uri);
        if (prepared != null) {
            return new ByteArrayInputStream(prepared.bytes());
        }
        try {
            URL url = new URL(uri);
            if (!isAllowedSource(url)) {
                throw new ProcessingFailureException(format("URL %s leads to an unauthorized source.", removeResourceSubPath(uri)));
            }
            return url.openStream();
        } catch (MalformedURLException e) {
            throw new ProcessingFailureException(format("URL %s malformed.", removeResourceSubPath(uri)));
        } catch (IOException e) {
            throw new ProcessingFailureException(format("IOException when trying to read from %s", removeResourceSubPath(uri)), e);
        }
    }

    private String removeResourceSubPath(String path) {
        return path.replace(resourcePath, "");
    }

    private boolean isAllowedSource(URL url) {
        if (allowResourcesFromDiskAndExternalDomainForGeneratingDocs) {
            return true;
        }
        if (!isValidProtocol(url)) {
            return false;
        }
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        if (host.equals(systemDomain)) {
            return true;
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(host);
            return inetAddress.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private boolean isValidProtocol(URL url) {
        String protocol = url.getProtocol();
        return "http".equals(protocol) || "https".equals(protocol);
    }

    private boolean isStylesCssFileUri(String uri) {
        return uri != null && normalise(uri).equals(styleCssUri);
    }

    /**
     * Exact map lookup only. A prefix or suffix match would let a remote URL shaped like a sentinel
     * skip the authorized-source check below.
     */
    private PreparedBackground preparedBackgroundFor(String uri) {
        return uri == null ? null : backgroundsByUri.get(normalise(uri));
    }

    /**
     * Flying Saucer asks for a prepared background under two different strings: getImageResource
     * resolves the URI inside itself, so an override is handed the relative sentinel name, while
     * resolveAndOpenStream is called later with the already-resolved absolute URI. Both are
     * registered, because both callers must hit without ever relaxing the exact match.
     */
    private static Map<String, PreparedBackground> indexByUri(String resourcePath, Map<String, PreparedBackground> backgrounds) {
        if (backgrounds == null || backgrounds.isEmpty()) {
            return Map.of();
        }
        Map<String, PreparedBackground> byUri = new HashMap<>();
        backgrounds.forEach((fileName, prepared) -> {
            byUri.put(fileName, prepared);
            byUri.put(buildExpectedResourceUri(resourcePath, fileName), prepared);
        });
        return Collections.unmodifiableMap(byUri);
    }

    private String buildExpectedStylesCssUri(String originalResourcePath) {
        return buildExpectedResourceUri(originalResourcePath, STYLES_CSS_FILE_NAME);
    }

    private static String buildExpectedResourceUri(String originalResourcePath, String fileName) {
        String resourceUri = normalise(originalResourcePath);
        StringBuilder path = new StringBuilder();
        if (resourceUri.startsWith("/")) {
            path.append("file:").append(resourceUri);
        } else {
            path.append("file:/").append(resourceUri);
        }
        if (resourceUri.endsWith("/")) {
            return path.append(fileName).toString();
        }
        return path.append("/").append(fileName).toString();
    }

    private static String normalise(String uri) {
        return uri.replace("\\", "/");
    }
}
