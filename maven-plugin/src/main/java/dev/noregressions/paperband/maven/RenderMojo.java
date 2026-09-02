package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.maven.pdf.WatermarkApplier;
import dev.noregressions.paperband.model.Watermark;
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
 *
 * <p>There is no book here, so nothing declares a watermark: this goal takes
 * one on the command line instead. That matters for the round trip it exists
 * for — the HTML a build emits carries its watermark as a screen-only overlay,
 * because the PDF's copy is stamped after rendering, so re-rendering that file
 * without {@code -Dpaperband.watermark} would quietly produce an unmarked PDF.
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

    /**
     * The watermark to stamp, as a block — the POM spelling of the
     * {@code vars.watermark} map:
     *
     * <pre>
     * &lt;watermark&gt;
     *   &lt;text&gt;REVIEW COPY&lt;/text&gt;
     *   &lt;color&gt;#aa0000&lt;/color&gt;
     *   &lt;opacity&gt;0.15&lt;/opacity&gt;
     * &lt;/watermark&gt;
     * </pre>
     *
     * <p>Replaces a {@code vars.watermark} declared in the book's yaml. The flat
     * {@code <watermarkColor>} family below still layers over it, since those
     * carry the {@code -D} properties — see {@link WatermarkConfig}.
     */
    @Parameter
    private WatermarkConfig watermark;

    /** Stamp this text across the rendered pages (e.g. {@code DRAFT}). */
    @Parameter(property = "paperband.watermark")
    private String watermarkText;

    /** Stamp this image instead of text; a path relative to the HTML file. */
    @Parameter(property = "paperband.watermarkImage")
    private String watermarkImage;

    /** Watermark fill colour as {@code #RRGGBB}. */
    @Parameter(property = "paperband.watermarkColor")
    private String watermarkColor;

    /** Watermark fill alpha, 0 to 1. */
    @Parameter(property = "paperband.watermarkOpacity")
    private Float watermarkOpacity;

    /** Watermark rotation in degrees. */
    @Parameter(property = "paperband.watermarkAngle")
    private Float watermarkAngle;

    /** Watermark font size in points; a ceiling unless {@code <watermarkFit>} is false. */
    @Parameter(property = "paperband.watermarkFontSize")
    private Integer watermarkFontSize;

    /** Set the watermark in Helvetica-Bold rather than Helvetica. */
    @Parameter(property = "paperband.watermarkBold")
    private Boolean watermarkBold;

    /** For an image watermark, its width as a fraction of the page width. */
    @Parameter(property = "paperband.watermarkScale")
    private Float watermarkScale;

    /** Shrink the watermark until it fits the page instead of letting it overflow. */
    @Parameter(property = "paperband.watermarkFit")
    private Boolean watermarkFit;

    /** Draw the watermark underneath the page content rather than over it. */
    @Parameter(property = "paperband.watermarkBehind")
    private Boolean watermarkBehind;

    /** Repeat the watermark across each page instead of centring one stamp. */
    @Parameter(property = "paperband.watermarkTile")
    private Boolean watermarkTile;

    /** Which pages to stamp: {@code all}, {@code first}, or {@code except-cover}. */
    @Parameter(property = "paperband.watermarkPages")
    private String watermarkPages;

    /** TrueType font file to set the watermark in, relative to the HTML file. */
    @Parameter(property = "paperband.watermarkFont")
    private String watermarkFont;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("render")) return;

        Path htmlFile = resolve(input);
        Path pdfFile = resolve(output);
        if (!Files.isRegularFile(htmlFile)) {
            throw new MojoExecutionException("<input> HTML file not found: " + htmlFile);
        }

        // Resolved before rendering so a bad -Dpaperband.watermarkPages fails
        // in a second rather than after a full Chromium round trip.
        Watermark watermark = watermark();

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
            if (watermark != null && WatermarkApplier.apply(pdfFile, watermark,
                    htmlFile.toAbsolutePath().getParent(), getLog()::warn)) {
                getLog().info("Applied watermark: " + watermark.describe());
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Render failed: " + e.getMessage(), e);
        }

        getLog().info("Rendered " + htmlFile + " -> " + pdfFile + " using " + htmlToPdf.name());
    }

    /**
     * The watermark the command line asked for, or null for none.
     *
     * <p>Unlike a book build there is no yaml to fall back on, so the knob
     * parameters alone produce nothing: text or an image has to be named.
     *
     * @return the spec, or null
     * @throws MojoExecutionException if a parameter carries an unusable value
     */
    private Watermark watermark() throws MojoExecutionException {
        Watermark base = Watermarks.base(watermark, watermarkText, watermarkImage);
        if (base == null) return null;
        return base.withOverrides(Watermarks.overrides(watermark,
                watermarkColor, watermarkOpacity, watermarkAngle, watermarkFontSize, watermarkBold,
                watermarkScale, watermarkFit, watermarkBehind, watermarkTile, watermarkPages,
                watermarkFont));
    }
}
