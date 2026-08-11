package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.CardParseException;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.cards.YamlCardTranspiler;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.PdfMetadata;
import dev.noregressions.paperband.render.RendererRegistry;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a PDF from a single Paperband markdown card, or an entire book
 * (a directory tree of cards), as part of a Maven build.
 *
 * <p>This is a Maven-flavoured front end over the same library modules the
 * {@code paperband} CLI's {@code build} command uses ({@code core},
 * {@code cards}, {@code config}, {@code layout}, {@code include},
 * {@code render-playwright}) — see that command's javadoc in the
 * {@code cli} module for the full single-card / book pipeline this mirrors.
 *
 * <p>Deliberately narrower than the CLI command for now:
 * <ul>
 *   <li>No watermarking — {@code Watermark}/{@code WatermarkApplier} live
 *       only in the {@code cli} module today, not a shared library module
 *       this plugin depends on.</li>
 *   <li>No {@code --select} / edition support (multi-edition publishing).</li>
 *   <li>No page-count enforcement or reporting.</li>
 *   <li>No debug HTML emission, no {@code --external-include-*} escape
 *       hatches.</li>
 * </ul>
 * Promote whichever of those you need down into a library module and this
 * goal can pick them up the same way it picked up everything else.
 *
 * <p>Example:
 * <pre>{@code
 * <plugin>
 *   <groupId>dev.noregressions.paperband</groupId>
 *   <artifactId>paperband-maven-plugin</artifactId>
 *   <version>0.0.1</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>build</goal></goals>
 *       <configuration>
 *         <input>${project.basedir}/guide</input>
 *         <output>${project.build.directory}/guide.pdf</output>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 * or invoked directly: {@code mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=guide.pdf}
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class BuildMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** Input markdown file (single card) or directory (book). Relative paths resolve against the module's basedir. */
    @Parameter(property = "paperband.input", required = true)
    private java.io.File input;

    /** Output PDF file. Relative paths resolve against the module's basedir. */
    @Parameter(property = "paperband.output", required = true)
    private java.io.File output;

    /** Renderer name. See {@code paperband renderers} in the CLI to list what's on the classpath. */
    @Parameter(property = "paperband.renderer", defaultValue = "playwright")
    private String renderer;

    /** Build target, e.g. {@code pdf-a4}, {@code pdf-6x9}. */
    @Parameter(property = "paperband.target", defaultValue = "pdf-a4")
    private String target;

    /** Page size slug, e.g. {@code a4}, {@code letter}, {@code 6x9}. */
    @Parameter(property = "paperband.pageSize", defaultValue = "a4")
    private String pageSize;

    /** Layout template name override. Defaults to the context layout, or 'card'/'book' if unset. */
    @Parameter(property = "paperband.layout")
    private String layoutOverride;

    /** Named theme to apply. Overrides any {@code theme:} declared in the book's {@code paperband.yaml}. */
    @Parameter(property = "paperband.theme")
    private String themeName;

    /** Directory of user themes, checked before classpath built-ins. */
    @Parameter(property = "paperband.themeDir")
    private java.io.File themeDir;

    /** Skip this goal without failing the build. */
    @Parameter(property = "paperband.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("paperband:build skipped (paperband.skip=true)");
            return;
        }

        Path inputPath = resolve(input);
        Path outputPath = resolve(output);

        try {
            if (Files.isRegularFile(inputPath)) {
                buildSingle(inputPath, outputPath);
            } else if (Files.isDirectory(inputPath)) {
                buildBook(inputPath, outputPath);
            } else {
                throw new MojoExecutionException("Input not found: " + inputPath);
            }
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Paperband build failed: " + e.getMessage(), e);
        }
    }

    private Path resolve(java.io.File f) {
        Path p = f.toPath();
        if (p.isAbsolute() || project == null) return p;
        return project.getBasedir().toPath().resolve(p);
    }

    // ---- single-card path (mirrors BuildCommand.buildSingle) ----

    private void buildSingle(Path input, Path output) throws Exception {
        RenderContext ctx = new ConfigLoader().load(input, target, pageSize);
        MarkdownPreprocessor preprocessor =
                Includes.defaultPreprocessor(ctx.book().bookRoot(), Map.of(), ctx.vars());
        Card card = loadCard(new CardLoader(), preprocessor, input, ctx.book().cardSchema());

        ThemeBundle theme = ThemeResolver.resolve(resolveThemeName(ctx), themeDirPath());
        LayoutEngine layout = new LayoutEngine(ctx.book().bookRoot(), theme);
        String html = (layoutOverride != null)
                ? layout.render(card, ctx, layoutOverride)
                : layout.render(card, ctx);

        HtmlToPdfRenderer htmlToPdfRenderer = resolveRenderer();

        URI baseUri = input.toAbsolutePath().getParent().toUri();
        PdfMetadata metadata = card.title() != null ? PdfMetadata.of(card.title()) : PdfMetadata.empty();
        HtmlInput htmlInput = new HtmlInput(html, baseUri, ctx.pageSpec(), metadata);

        ensureParentDir(output);
        htmlToPdfRenderer.render(htmlInput, output);

        getLog().info("Built " + input + " -> " + output
                + " (renderer=" + htmlToPdfRenderer.name()
                + ", target=" + target
                + ", size=" + pageSize
                + ", blocks=" + card.blocks().size() + ")");
    }

    // ---- book / multi-card path (mirrors BuildCommand.buildBook, minus --select/editions/page-checks) ----

    private void buildBook(Path input, Path output) throws Exception {
        BookWalker walker = new BookWalker(target);
        List<Path> cardFiles = walker.walk(input);
        if (cardFiles.isEmpty()) {
            throw new MojoFailureException("No cards found under " + input);
        }
        getLog().info("Found " + cardFiles.size() + " cards under " + input);

        ConfigLoader configLoader = new ConfigLoader();
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        CardLoader cardLoader = new CardLoader();
        int totalBlocks = 0;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(cardFile, target, pageSize);
            if (bookCtx == null) {
                bookCtx = ctx; // capture book-root css/title once, same as BuildCommand
            }
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor =
                    Includes.defaultPreprocessor(bookCtx.book().bookRoot(), Map.of(), ctx.vars());
            Card card = loadCard(cardLoader, preprocessor, cardFile, ctx.book().cardSchema());
            cards.add(card);
            totalBlocks += card.blocks().size();
        }

        ThemeBundle theme = ThemeResolver.resolve(resolveThemeName(bookCtx), themeDirPath());
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        String html = (layoutOverride != null)
                ? layout.renderBook(cards, contexts, bookCtx, layoutOverride)
                : layout.renderBook(cards, contexts, bookCtx);

        HtmlToPdfRenderer htmlToPdfRenderer = resolveRenderer();

        URI baseUri = input.toAbsolutePath().toUri();
        String bookTitle = bookCtx.book().title();
        PdfMetadata metadata = bookTitle != null ? PdfMetadata.of(bookTitle) : PdfMetadata.empty();
        String footerHtml = layout.renderFooter(bookCtx);
        String headerHtml = layout.renderHeader(bookCtx);
        HtmlInput htmlInput = new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata, footerHtml, headerHtml);

        ensureParentDir(output);
        htmlToPdfRenderer.render(htmlInput, output);

        getLog().info("Built book " + input + " -> " + output
                + " (renderer=" + htmlToPdfRenderer.name()
                + ", target=" + target
                + ", size=" + pageSize
                + ", cards=" + cards.size()
                + ", blocks=" + totalBlocks + ")");
    }

    // ---- helpers ----

    private static void ensureParentDir(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Path themeDirPath() {
        return themeDir == null ? null : themeDir.toPath();
    }

    private String resolveThemeName(RenderContext ctx) {
        if (themeName != null && !themeName.isBlank()) return themeName;
        if (ctx != null && ctx.book() != null) return ctx.book().theme();
        return null;
    }

    private HtmlToPdfRenderer resolveRenderer() throws MojoExecutionException {
        RendererRegistry registry = RendererRegistry.discover();
        return registry.get(renderer).orElseThrow(() -> new MojoExecutionException(
                "Unknown renderer: " + renderer + ". Available: "
                        + registry.all().stream().map(HtmlToPdfRenderer::name).toList()));
    }

    /**
     * Mirrors {@code BuildCommand.loadCard}: read → (optional yaml-card
     * transpile) → preprocess (includes/vars/conditionals) → parse.
     */
    private static Card loadCard(
            CardLoader cardLoader, MarkdownPreprocessor preprocessor, Path cardFile, CardSchema cardSchema) {
        String source;
        try {
            source = Files.readString(cardFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CardParseException("Failed to read " + cardFile, e);
        }
        if (YamlCardTranspiler.isYamlCard(cardFile)) {
            source = new YamlCardTranspiler().transpile(cardFile, source, cardSchema);
        }
        if (preprocessor != null) {
            source = preprocessor.process(source, cardFile);
        }
        return cardLoader.parse(cardFile, source);
    }
}
