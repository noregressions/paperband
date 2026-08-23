package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.CardParseException;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.cards.YamlCardTranspiler;
import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.Part;
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
 *
 * <p>In place of {@code <input>}, a book's structure can be declared in the
 * POM itself and its cards selected by glob — see {@link BookLayout}:
 *
 * <pre>
 * &lt;configuration&gt;
 *   &lt;output&gt;${project.build.directory}/traces.pdf&lt;/output&gt;
 *   &lt;book&gt;
 *     &lt;parts&gt;
 *       &lt;part&gt;
 *         &lt;title&gt;Execution Traces&lt;/title&gt;
 *         &lt;includes&gt;&lt;include&gt;services/&#42;/TRACE.md&lt;/include&gt;&lt;/includes&gt;
 *       &lt;/part&gt;
 *     &lt;/parts&gt;
 *   &lt;/book&gt;
 * &lt;/configuration&gt;
 * </pre>
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class BuildMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Input markdown file (single card) or directory (book). Relative paths
     * resolve against the module's basedir. Mutually exclusive with
     * {@link #book} — exactly one of the two says what to build.
     */
    @Parameter(property = "paperband.input")
    private java.io.File input;

    /**
     * A book whose structure is declared here rather than inferred from a
     * directory tree: an ordered list of titled parts, each selecting its
     * cards by glob. See {@link BookLayout} for the element's shape.
     *
     * <p>Use this instead of {@code <input>} when the book's shape doesn't
     * match the disk — one card pulled out of each of many sibling
     * directories, several folders' worth of cards fronted by a single
     * divider, a card list assembled from patterns rather than filename
     * order. Where {@code <input>} points at a tree and lets
     * {@code BookWalker} and the {@code paperband.yaml} sequencing keys decide
     * the rest, this states the answer outright.
     */
    @Parameter
    private BookLayout book;

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

    /**
     * Page margins, as a CSS-style shorthand: one to four lengths, optionally
     * with a unit ({@code mm} by default, or {@code cm}/{@code in}/{@code pt}).
     *
     * <pre>
     * &lt;margins&gt;0&lt;/margins&gt;              full bleed — see below
     * &lt;margins&gt;18mm&lt;/margins&gt;           18mm on every edge
     * &lt;margins&gt;20mm 15mm&lt;/margins&gt;      vertical, horizontal
     * &lt;margins&gt;20 15 25 15&lt;/margins&gt;    top, right, bottom, left
     * </pre>
     *
     * <p>Unset, the page size preset's own margins apply (20mm for A4, zero
     * for A5). Like {@link #pageSize}, this seeds the <em>base</em> geometry,
     * so a {@code vars.page.margins} block in the book's yaml still wins.
     *
     * <p>{@code 0} is what a theme whose ground is the paper needs: Chromium
     * paints nothing into a PDF page margin, so any margin shows as a white
     * border around every page, and zero is the only way a coloured ground
     * reaches the trim edge. The bundled full-bleed themes supply their own
     * insets in that case — see the Themes guide.
     */
    @Parameter(property = "paperband.margins")
    private String margins;

    /** Layout template name override. Defaults to the context layout, or 'card'/'book' if unset. */
    // alias: the POM element every doc and example uses is <layout>, matching
    // the -Dpaperband.layout property; without it Maven silently ignores
    // <layout> (it warns, but a warning in a resource-phase build is easy to
    // miss) and the build runs with no override at all.
    @Parameter(property = "paperband.layout", alias = "layout")
    private String layoutOverride;

    /** Named theme to apply. Overrides any {@code theme:} declared in the book's {@code paperband.yaml}. */
    // alias: <theme>, as documented and as the property is named. See the
    // note on layoutOverride above.
    @Parameter(property = "paperband.theme", alias = "theme")
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

        if (input != null && book != null) {
            throw new MojoExecutionException(
                    "Configure either <input> or <book>, not both: <input> walks a directory tree, "
                            + "<book> declares the structure and selects cards by glob");
        }
        if (input == null && book == null) {
            throw new MojoExecutionException(
                    "Nothing to build: configure <input> (a card file or book directory) or <book> "
                            + "(a declared book layout)");
        }

        Path outputPath = resolve(output);

        try {
            if (book != null) {
                buildPlannedBook(outputPath);
                return;
            }
            Path inputPath = resolve(input);
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

    /**
     * The {@code <margins>} shorthand as real {@link Margins}, or null when
     * the POM declares none.
     *
     * @throws MojoExecutionException if the shorthand is malformed — a typo in
     *         page geometry should fail the build, not silently render at the
     *         preset's margins
     */
    private Margins resolveMargins() throws MojoExecutionException {
        try {
            return Margins.parse(margins);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("<margins>: " + e.getMessage(), e);
        }
    }

    private Path resolve(java.io.File f) {
        Path p = f.toPath();
        if (p.isAbsolute() || project == null) return p;
        return project.getBasedir().toPath().resolve(p);
    }

    // ---- single-card path (mirrors BuildCommand.buildSingle) ----

    private void buildSingle(Path input, Path output) throws Exception {
        RenderContext ctx = new ConfigLoader().load(input, target, pageSize, resolveMargins());
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
                + (margins == null ? "" : ", margins=" + margins)
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
        renderBook(input, cardFiles, List.of(), output);
    }

    /**
     * Book from a declared {@code <book>} layout: resolve the patterns into an
     * ordered card list plus the parts grouping it, then render exactly as a
     * walked book does. The only difference downstream is that the parts came
     * from the POM rather than the root {@code paperband.yaml}, and that they
     * claim individual cards instead of whole folders.
     */
    private void buildPlannedBook(Path output) throws Exception {
        Path root = book.getRoot() != null
                ? resolve(book.getRoot())
                : (project == null ? Path.of("") : project.getBasedir().toPath());
        if (!Files.isDirectory(root)) {
            throw new MojoExecutionException("<book><root> is not a directory: " + root);
        }

        List<BookPlan.PartSpec> specs;
        try {
            specs = book.toSpecs();
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        BookPlan.Plan plan = BookPlan.resolve(root, specs, target);
        if (plan.cards().isEmpty()) {
            throw new MojoFailureException("No cards matched the <book> patterns under " + root);
        }
        getLog().info("Planned " + plan.cards().size() + " cards in "
                + plan.parts().size() + (plan.parts().size() == 1 ? " part under " : " parts under ") + root);
        for (Part part : plan.parts()) {
            getLog().debug("  part " + part.id() + " (" + part.title() + "): "
                    + part.cards().size() + " cards");
        }
        renderBook(root, plan.cards(), plan.parts(), output);
    }

    /**
     * Load every card in {@code cardFiles}, in order, and render them as one
     * book PDF.
     *
     * @param bookRoot      directory the build was rooted at — the PDF's base URI
     *                      for relative asset references
     * @param cardFiles     ordered card files, however they were selected
     * @param declaredParts parts to declare on the book, replacing anything the
     *                      root {@code paperband.yaml} declared; empty to leave
     *                      the yaml's own {@code parts:} (or lack of them) alone
     */
    private void renderBook(Path bookRoot, List<Path> cardFiles, List<Part> declaredParts, Path output)
            throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        CardLoader cardLoader = new CardLoader();
        int totalBlocks = 0;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(cardFile, target, pageSize, resolveMargins());
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

        // Declared parts replace whatever the root yaml said: two sources for
        // one book's top-level structure can only disagree, and the POM is the
        // one the user just edited.
        if (!declaredParts.isEmpty()) {
            if (!bookCtx.book().parts().isEmpty()) {
                getLog().warn("<book><parts> overrides the 'parts:' declared in "
                        + bookCtx.book().bookRoot().resolve("paperband.yaml"));
            }
            bookCtx = bookCtx.withBook(bookCtx.book().withParts(declaredParts));
        }

        ThemeBundle theme = ThemeResolver.resolve(resolveThemeName(bookCtx), themeDirPath());
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        String html = (layoutOverride != null)
                ? layout.renderBook(cards, contexts, bookCtx, layoutOverride)
                : layout.renderBook(cards, contexts, bookCtx);

        HtmlToPdfRenderer htmlToPdfRenderer = resolveRenderer();

        URI baseUri = bookRoot.toAbsolutePath().toUri();
        String bookTitle = bookCtx.book().title();
        PdfMetadata metadata = bookTitle != null ? PdfMetadata.of(bookTitle) : PdfMetadata.empty();
        String footerHtml = layout.renderFooter(bookCtx);
        String headerHtml = layout.renderHeader(bookCtx);
        HtmlInput htmlInput = new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata, footerHtml, headerHtml);

        ensureParentDir(output);
        htmlToPdfRenderer.render(htmlInput, output);

        getLog().info("Built book " + bookRoot + " -> " + output
                + " (renderer=" + htmlToPdfRenderer.name()
                + ", target=" + target
                + ", size=" + pageSize
                + (margins == null ? "" : ", margins=" + margins)
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
