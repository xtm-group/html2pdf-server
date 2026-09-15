package eu.xtrf.html2pdf.server.converter.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.IOException;

@Component
public class RendererProviderImpl implements RendererProvider {
    private final FontService fontService;
    private final InlineImageRewriter inlineImageRewriter;

    @Autowired
    public RendererProviderImpl(FontService fontService, InlineImageRewriter inlineImageRewriter) {
        this.fontService = fontService;
        this.inlineImageRewriter = inlineImageRewriter;
    }

    @Override
    public ITextRenderer prepareRenderer(String resourcePath, String systemDomain, String styleCss, boolean allowResourcesFromDiskAndExternalDomainForGeneratingDocs) throws IOException {
        ITextRenderer renderer = new ITextRenderer();
        SharedContext sharedContext = renderer.getSharedContext();
        sharedContext.setPrint(true);
        sharedContext.setInteractive(false);
        InlineImageRewriter.PreparedStyles prepared = inlineImageRewriter.rewrite(styleCss, resourcePath);
        sharedContext.setUserAgentCallback(new ConverterOpenPdfUserAgent(renderer.getOutputDevice(), sharedContext, resourcePath, systemDomain, prepared.css(),
                prepared.backgrounds(), allowResourcesFromDiskAndExternalDomainForGeneratingDocs));
        sharedContext.getTextRenderer().setSmoothingThreshold(0);

        fontService.loadFontsToRendererFromResources(renderer, resourcePath);
        return renderer;
    }
}
