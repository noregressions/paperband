package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.PageSpec;
import dev.noregressions.paperband.render.PdfMetadata;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders one HTML file straight to PDF, with no card parsing, config cascade
 * or layout in the way — the renderer on its own.
 *
 * <p>Useful for checking what the renderer does with a page, and for turning a
 * {@code build}-emitted HTML file (see {@code <emitHtml>}) back into a PDF
 * after hand-editing it.
 *
 * <pre>mvn paperband:render -Dpaperband.input=page.html -Dpaperband.output=page.pdf</pre>
 */
@Mojo(name = "render", requiresProject = false, threadSafe = true)
public class RenderMojo extends AbstractPaperbandMojo {

    /** Input HTML file. */
    @Parameter(property = "paperband.input", required = true)
    private java.io.File input;

    /** Output PDF file. */
    @Parameter(property = "paperband.output", required = true)
    private java.io.File output;

    /** PDF title metadata. */
    @Parameter(property = "paperband.title")
    private String title;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("render")) return;

        Path htmlFile = resolve(input);
        Path pdfFile = resolve(output);
        if (!Files.isRegularFile(htmlFile)) {
            throw new MojoExecutionException("<input> HTML file not found: " + htmlFile);
        }

        HtmlToPdfRenderer htmlToPdf = Renderers.require(renderer);
        // Page geometry comes from the size preset plus any <margins>: there
        // is no book here to carry a vars.page block.
        PageSpec base = PageSpec.forSizeName(pageSize);
        var declared = resolveMargins();
        PageSpec spec = declared == null
                ? base
                : new PageSpec(base.size(), declared, base.orientation());

        try {
            String html = Files.readString(htmlFile);
            URI baseUri = htmlFile.toAbsolutePath().getParent().toUri();
            PdfMetadata metadata = title != null ? PdfMetadata.of(title) : PdfMetadata.empty();
            Path parent = pdfFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            htmlToPdf.render(new HtmlInput(html, baseUri, spec, metadata), pdfFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Render failed: " + e.getMessage(), e);
        }

        getLog().info("Rendered " + htmlFile + " -> " + pdfFile + " using " + htmlToPdf.name());
    }
}
