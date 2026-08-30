package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dumps the document structure — cover, dividers, sections, cards in
 * order, and each card's block tree — as an indented outline, without
 * rendering anything.
 *
 * <p>The way to check what a declaration actually produced: which cards a
 * {@code <book><sections>} pattern claimed, what order they come in, where the
 * dividers land. Because the model is a flat ordered walk with dividers
 * <em>derived</em> from it, interleaved axis values produce repeated DIVIDER
 * lines here — matching the repeated divider pages the PDF would get.
 *
 * <pre>
 * mvn paperband:structure -Dpaperband.input=guide
 * mvn paperband:structure -Dpaperband.input=guide -Dpaperband.output=structure.txt
 * </pre>
 */
// A default phase so a bound <execution> actually runs: without one Maven
// silently does nothing for an execution that names no phase itself.
@Mojo(name = "structure", defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
        requiresProject = false, threadSafe = true)
public class StructureMojo extends AbstractPaperbandMojo {

    /** Card file or book directory to describe. Mutually exclusive with {@link #book}. */
    @Parameter(property = "paperband.input")
    private java.io.File input;

    /**
     * Describe a POM-declared book instead of a directory tree — the same
     * {@code <book>} element {@code build} takes, so the outline shows exactly
     * which cards the patterns claimed, in which section, in what order.
     */
    @Parameter
    private BookLayout book;

    /**
     * Optional text file to write the outline to. Logs it when omitted.
     *
     * <p>Deliberately not {@code <output>}: goals share the plugin-level
     * {@code <configuration>}, and {@code <output>} there is the PDF the
     * {@code build} goal writes. Reusing the name would let this goal overwrite
     * a book with its own outline.
     */
    @Parameter(property = "paperband.outputFile")
    private java.io.File outputFile;

    /** Book-level vars the POM's {@code <book>} declared, for the config cascade. */
    private java.util.Map<String, Object> declaredVars() {
        return book == null ? java.util.Map.of() : book.declaredVars();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("structure")) return;
        checkBookDeclaration(book);
        try {
            describe();
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Paperband structure dump failed: " + e.getMessage(), e);
        }
    }

    private void describe() throws Exception {
        if (input != null && book != null) {
            throw new MojoExecutionException("Configure either <input> or <book>, not both.");
        }
        Geography geo = geography();
        Path contentRoot = null;
        Path legacyConventional = null;
        if (content != null) {
            if (input != null) {
                throw new MojoExecutionException(
                        "Configure either <content> or <input>, not both.");
            }
            contentRoot = resolve(content);
        } else if (input == null && book == null) {
            contentRoot = geo.content();
            if (contentRoot == null) legacyConventional = geo.home();
            if (contentRoot == null && legacyConventional == null) {
                throw new MojoExecutionException("Configure <content>, <input> or <book> — "
                        + "nothing to describe. (Or lay the book out at src/main/paperband, "
                        + "which needs none of them.)");
            }
        }
        String text = geo.describe() + "\n";
        if (book != null) {
            Path root = bookRoot(book, geo);
            text += describeBook(book.declaresCardSelection()
                    ? plannedBook()
                    : BookSource.walk(root, target, getLog()), root);
        } else if (contentRoot != null) {
            text += describeBook(BookSource.walkContent(contentRoot, target, getLog()), null);
        } else {
            Path in = legacyConventional != null ? legacyConventional : resolve(input);
            if (Files.isRegularFile(in)) {
                text += describeSingle(in);
            } else if (Files.isDirectory(in)) {
                text += describeBook(BookSource.walk(in, target, getLog()), null);
            } else {
                throw new MojoExecutionException("<input> not found: " + in);
            }
        }

        if (outputFile == null) {
            for (String line : text.split("\n", -1)) {
                getLog().info(line);
            }
        } else {
            Path out = resolve(outputFile);
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, text, StandardCharsets.UTF_8);
            getLog().info("Wrote structure -> " + out);
        }
    }

    private String describeSingle(Path cardFile) throws MojoExecutionException {
        RenderContext ctx = new ConfigLoader().load(cardFile, target, pageSize, resolveMargins());
        MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                ctx.book().bookRoot(), includeProviderConfig(), ctx.vars());
        Card card = CardLoading.load(new CardLoader(), preprocessor, cardFile, ctx.book().cardSchema(),
                ctx.vars(), getLog());
        return LayoutEngine.describeCard(card);
    }

    /** The {@code <book>} element as a resolved card list plus its sections. */
    private BookSource.Resolved plannedBook() throws MojoExecutionException, MojoFailureException {
        List<dev.noregressions.paperband.config.BookPlan.SectionSpec> specs;
        try {
            specs = book.toSpecs();
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        Path root = bookRoot(book, geography());
        return BookSource.plan(root, specs, book.tocAfterSpec(), book.pageMarkers(), target, getLog());
    }

    /**
     * Load the book exactly the way {@code build} does — same card source, same
     * per-card config cascade, same per-card preprocessor — then describe it
     * instead of rendering it, so the outline can't drift from what a real
     * build would assemble.
     */
    private String describeBook(BookSource.Resolved source, Path declaredRoot)
            throws MojoExecutionException, MojoFailureException {
        List<Path> cardFiles = source.cardFiles();
        ConfigLoader configLoader = new ConfigLoader();
        CardLoader cardLoader = null;
        BlockTemplates blockTemplates = null;      // see BookBuild: needs the book root
        Map<String, Map<String, Object>> providerConfig = includeProviderConfig();
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(
                    cardFile, target, pageSize, resolveMargins(), declaredRoot,
                    geography().home(), declaredVars());
            if (bookCtx == null) bookCtx = ctx;
            if (cardLoader == null) cardLoader = new CardLoader(bookCtx.book().bookRoot());
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), providerConfig, ctx.vars());
            if (blockTemplates == null) {
                blockTemplates = new BlockTemplates(null,
                        geography().layouts() != null ? geography().layouts()
                                : bookCtx.book().bookRoot().resolve("layouts"));
            }
            cards.add(CardLoading.load(cardLoader, preprocessor, cardFile, ctx.book().cardSchema(),
                    ctx.vars(), getLog(), blockTemplates));
        }
        CardLoading.requireUniqueIds(cards, bookCtx.book().bookRoot());

        // Book-level config and sections declared in the POM, exactly as in a
        // build — otherwise the outline would describe a different book from
        // the one the PDF gets.
        if (book != null && book.declaresBookConfig()) {
            bookCtx = bookCtx.withBook(book.mergeInto(bookCtx.book(), bookCtx.book().bookRoot()));
        }
        if (!source.sections().isEmpty()) {
            bookCtx = bookCtx.withBook(bookCtx.book().withSections(source.sections()));
        }
        return LayoutEngine.describeBook(cards, contexts, bookCtx);
    }
}
