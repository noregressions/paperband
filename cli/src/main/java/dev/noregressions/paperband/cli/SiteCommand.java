package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Build a multi-file static HTML site from a book directory.
 *
 * <p>The site target emits:
 * <ul>
 *   <li>{@code index.html} — landing page with stats and tier grid</li>
 *   <li>{@code tier{N}.html} — one per tier value declared in the book root yaml</li>
 *   <li>{@code cards/&lt;id&gt;.html} — one per card, with prev/next navigation</li>
 * </ul>
 *
 * <p>Card slugs come from {@link Card#id()} (markdown filename basename).
 * The site CSS chain is taken from {@link RenderContext#cssChain()} — same as
 * the PDF target. Add a {@code site.css} to the book's css chain to get
 * site-specific styling (sticky nav, card grid, hero panels).
 *
 * <pre>
 * paperband site guide /tmp/site
 * </pre>
 */
@Command(
        name = "site",
        mixinStandardHelpOptions = true,
        description = "Build a multi-file static HTML site from a book directory.")
public final class SiteCommand implements Callable<Integer> {

    @Option(
            names = {"-t", "--target"},
            description = "Build target. Default: ${DEFAULT-VALUE}",
            defaultValue = "web")
    String target;

    @Option(
            names = {"--clean"},
            description = "Clear the output directory before writing (cards/ subtree only).")
    boolean clean;

    @Option(
            names = {"--theme"},
            description = "Apply a named theme. Use `paperband themes` to list what's available. "
                    + "User themes resolved via --theme-dir take priority over built-ins of the same name. "
                    + "Overrides any `theme:` declared in the book's paperband.yaml.")
    String themeName;

    @Option(
            names = {"--theme-dir"},
            description = "Directory of user themes (each in its own subfolder with a manifest.txt). "
                    + "Looked up before classpath built-ins.")
    Path themeDir;

    @Option(
            names = {"--external-include-dir"},
            paramLabel = "<dir>",
            description = "Permit {{#include}} directives to read files under this directory, even "
                    + "though it is outside the book root. Repeatable. Off by default; name only "
                    + "directories you trust, since any card can then read files beneath them.")
    List<Path> externalIncludeDirs;

    @Option(
            names = {"--external-include-file"},
            paramLabel = "<file>",
            description = "Permit {{#include}} directives to read this specific file, even though it "
                    + "is outside the book root. Repeatable. Narrower than --external-include-dir.")
    List<Path> externalIncludeFiles;

    @Parameters(index = "0", description = "Input book directory.")
    Path input;

    @Parameters(index = "1", description = "Output directory.")
    Path output;

    @Override
    public Integer call() throws Exception {
        if (!Files.isDirectory(input)) {
            System.err.println("site: input must be a directory: " + input);
            return 2;
        }

        BookWalker walker = new BookWalker(target);
        List<Path> cardFiles = walker.walk(input);
        if (cardFiles.isEmpty()) {
            System.err.println("site: no cards found under " + input);
            return 2;
        }

        ConfigLoader configLoader = new ConfigLoader();

        // Book root is captured once (from the first card); the preprocessor
        // is rebuilt per card since it binds that card's vars at construction
        // time — fragment resolution and vars/conditionals now run in a
        // single Pebble pass (see PebbleIncludePreprocessor for why they
        // can't be split into separate passes).
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        CardLoader cardLoader = new CardLoader();
        Map<String, Map<String, Object>> providerConfig =
                BuildCommand.includeProviderConfig(externalIncludeDirs, externalIncludeFiles);
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(cardFile, target, "A4");
            if (bookCtx == null) {
                bookCtx = ctx;
            }
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), providerConfig, ctx.vars());
            cards.add(BuildCommand.loadCard(cardLoader, preprocessor, cardFile,
                    ctx.book().cardSchema()));
        }

        String effectiveTheme = (themeName != null && !themeName.isBlank())
                ? themeName
                : bookCtx.book().theme();
        ThemeBundle theme = ThemeResolver.resolve(effectiveTheme, themeDir);
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        Map<String, String> pages;
        try {
            pages = layout.renderSite(cards, contexts, bookCtx);
        } catch (dev.noregressions.paperband.layout.SlotPlacementException e) {
            // Same exit-code convention as `build`: 4 = structural check
            // failed (slot-using layout couldn't place every block).
            System.err.println(e.getMessage());
            return 4;
        }

        // Output prep.
        Files.createDirectories(output);
        Path cardsDir = output.resolve("cards");
        if (clean && Files.isDirectory(cardsDir)) {
            try (var walk = Files.walk(cardsDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        Files.createDirectories(cardsDir);

        // Write everything. Page keys are output-relative paths; refuse any
        // that would resolve outside the output directory (defence in depth —
        // card ids are already validated at load time).
        Path outputRoot = output.toAbsolutePath().normalize();
        int written = 0;
        for (var entry : pages.entrySet()) {
            Path target = output.resolve(entry.getKey()).normalize();
            if (!target.toAbsolutePath().startsWith(outputRoot)) {
                throw new IOException(
                        "refusing to write site page outside output directory: "
                                + entry.getKey() + " -> " + target);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            written++;
        }

        System.out.println("Built site " + input + " -> " + output
                + " (" + written + " pages, "
                + cards.size() + " cards)");
        return 0;
    }
}
