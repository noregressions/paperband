package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.SlotPlacementException;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a multi-file static HTML site from a book directory: an index, a
 * landing page per axis value and per section (or declared part), and a page
 * per card with prev/next navigation.
 *
 * <p>The site shares the book's css chain and theme with the PDF target, so
 * one book renders both without a second set of config. Add a site-specific
 * stylesheet to the chain for the sticky nav, card grid and hero panels.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;site&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;site&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;input&gt;${project.basedir}/guide&lt;/input&gt;
 *     &lt;outputDirectory&gt;${project.build.directory}/site&lt;/outputDirectory&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 */
@Mojo(name = "site", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class SiteMojo extends AbstractPaperbandMojo {

    /** Input book directory. Mutually exclusive with {@link #book}. */
    @Parameter(property = "paperband.input")
    private java.io.File input;

    /**
     * Build the site for a POM-declared book instead of a directory tree — the
     * same {@code <book>} element {@code build} takes, so one declaration feeds
     * both the PDF and the site.
     */
    @Parameter
    private BookLayout book;

    /** Output directory for the generated site. */
    @Parameter(property = "paperband.outputDirectory", required = true)
    private java.io.File outputDirectory;

    /**
     * Clear the {@code cards/} subtree before writing, so a card removed from
     * the book stops being served from a stale page.
     */
    @Parameter(property = "paperband.clean", defaultValue = "false")
    private boolean clean;

    /**
     * Build target the config cascade sees, defaulting to {@code web} rather
     * than the {@code pdf-a4} every other goal uses: target-scoped content
     * ({@code where:} predicates, {@code targets:} lists) is usually written to
     * distinguish exactly this.
     *
     * <p>Named {@code siteTarget} rather than sharing {@code <target>} with the
     * inherited parameter. Two fields in one mojo bound to the same property is
     * a trap: {@code <target>} would set the inherited field, which this goal
     * never reads, so the element would look broken while a {@code -D} override
     * quietly set both.
     */
    @Parameter(property = "paperband.siteTarget", defaultValue = "web")
    private String siteTarget;

    /** Book-level vars the POM's {@code <book>} declared, for the config cascade. */
    private java.util.Map<String, Object> declaredVars() {
        if (book == null || book.getVars().isEmpty()) return java.util.Map.of();
        return new java.util.LinkedHashMap<>(book.getVars());
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("site")) return;
        try {
            build();
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Paperband site build failed: " + e.getMessage(), e);
        }
    }

    private void build() throws Exception {
        if (input != null && book != null) {
            throw new MojoExecutionException("Configure either <input> or <book>, not both.");
        }
        Path output = resolve(outputDirectory);

        BookBuild.PlannedBook plan = null;
        Path declaredRoot = null;
        Path walkFrom = input == null ? null : resolve(input);
        if (book != null) {
            declaredRoot = book.getRoot() != null ? resolve(book.getRoot()) : basedir();
            if (book.declaresCardSelection()) {
                try {
                    plan = new BookBuild.PlannedBook(declaredRoot, book.toSpecs());
                } catch (IllegalArgumentException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            } else {
                walkFrom = declaredRoot;   // config declared, structure from the tree
            }
        }
        BookSource.Resolved source = BookSource.of(walkFrom, plan, siteTarget, getLog());
        Path bookDir = source.root();
        List<Path> cardFiles = source.cardFiles();

        // Book root captured once (from the first card); the preprocessor is
        // rebuilt per card since it binds that card's vars at construction.
        ConfigLoader configLoader = new ConfigLoader();
        CardLoader cardLoader = new CardLoader();
        Map<String, Map<String, Object>> providerConfig = includeProviderConfig();
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(
                    cardFile, siteTarget, pageSize, resolveMargins(), declaredRoot, declaredVars());
            if (bookCtx == null) bookCtx = ctx;
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), providerConfig, ctx.vars());
            cards.add(CardLoading.load(cardLoader, preprocessor, cardFile, ctx.book().cardSchema()));
        }

        CardLoading.requireUniqueIds(cards, bookDir);

        // Book-level config and parts declared in the POM, applied exactly as
        // in a build: the site's title, landing pages and nav then match the
        // PDF's cover and dividers.
        if (book != null && book.declaresBookConfig()) {
            bookCtx = bookCtx.withBook(book.mergeInto(bookCtx.book(), bookCtx.book().bookRoot()));
        }
        if (!source.parts().isEmpty()) {
            bookCtx = bookCtx.withBook(bookCtx.book().withParts(source.parts()));
        }

        ThemeBundle theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDirPath());
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheetPaths());
        Map<String, String> pages;
        try {
            pages = layout.renderSite(cards, contexts, bookCtx);
        } catch (SlotPlacementException e) {
            // A structural check, not a crash: a slot-using layout couldn't
            // place every block.
            throw new MojoFailureException(e.getMessage(), e);
        }

        Files.createDirectories(output);
        Path cardsDir = output.resolve("cards");
        if (clean && Files.isDirectory(cardsDir)) {
            try (var walk = Files.walk(cardsDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // Best effort: a leftover file is not worth failing the build.
                            }
                        });
            }
        }
        Files.createDirectories(cardsDir);

        // Page keys are output-relative paths; refuse any that would resolve
        // outside the output directory (defence in depth — card ids are
        // already validated at load time).
        Path outputRoot = output.toAbsolutePath().normalize();
        int written = 0;
        for (Map.Entry<String, String> entry : pages.entrySet()) {
            Path pageFile = output.resolve(entry.getKey()).normalize();
            if (!pageFile.toAbsolutePath().startsWith(outputRoot)) {
                throw new MojoExecutionException(
                        "refusing to write site page outside output directory: "
                                + entry.getKey() + " -> " + pageFile);
            }
            Files.createDirectories(pageFile.getParent());
            Files.writeString(pageFile, entry.getValue(), StandardCharsets.UTF_8);
            written++;
        }

        getLog().info("Built site " + bookDir + " -> " + output
                + " (" + written + " pages, " + cards.size() + " cards)");
    }
}
