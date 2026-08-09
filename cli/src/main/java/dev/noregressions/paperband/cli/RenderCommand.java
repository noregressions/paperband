package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.PageSpec;
import dev.noregressions.paperband.render.PdfMetadata;
import dev.noregressions.paperband.render.RendererRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * One-shot HTML&rarr;PDF render. Useful for smoke-testing a renderer.
 *
 * <pre>paperband render --renderer playwright input.html output.pdf</pre>
 */
@Command(
        name = "render",
        mixinStandardHelpOptions = true,
        description = "Render a single HTML file to PDF using the chosen renderer.")
public final class RenderCommand implements Callable<Integer> {

    @Option(
            names = {"-r", "--renderer"},
            description = "Renderer name. Use `paperband renderers` to list. Default: ${DEFAULT-VALUE}",
            defaultValue = "playwright")
    String rendererName;

    @Option(
            names = {"-s", "--page-size"},
            description = "Page size. Values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}",
            defaultValue = "A4")
    PageSizeOption pageSize;

    @Option(
            names = {"--title"},
            description = "PDF title metadata.")
    String title;

    @Parameters(index = "0", description = "Input HTML file.")
    Path input;

    @Parameters(index = "1", description = "Output PDF file.")
    Path output;

    @Override
    public Integer call() throws Exception {
        RendererRegistry registry = RendererRegistry.discover();

        HtmlToPdfRenderer renderer = registry.get(rendererName).orElse(null);
        if (renderer == null) {
            System.err.println("Unknown renderer: " + rendererName);
            System.err.println("Available: " + registry.all().stream()
                    .map(HtmlToPdfRenderer::name).toList());
            return 2;
        }

        if (!Files.isRegularFile(input)) {
            System.err.println("Input file not found: " + input);
            return 2;
        }

        String html = Files.readString(input);
        URI baseUri = input.toAbsolutePath().getParent().toUri();

        PageSpec spec = PageSpec.forSizeName(pageSize.slug());

        PdfMetadata metadata = title != null ? PdfMetadata.of(title) : PdfMetadata.empty();
        HtmlInput htmlInput = new HtmlInput(html, baseUri, spec, metadata);

        renderer.render(htmlInput, output);
        System.out.println("Rendered " + input + " -> " + output
                + " using " + renderer.name());
        return 0;
    }

    /**
     * Page-size CLI enum. {@link #slug()} returns the form passed through to
     * the RenderContext / templates / CSS (e.g. {@code 6x9}, not
     * {@code BOOKLET_6X9}) — used as a class hook on {@code <html>} so themes
     * can branch on the chosen page size.
     */
    enum PageSizeOption {
        A4("a4"),
        A5("a5"),
        LETTER("letter"),
        BOOKLET_6X9("6x9");

        private final String slug;
        PageSizeOption(String slug) { this.slug = slug; }
        public String slug() { return slug; }
    }
}
