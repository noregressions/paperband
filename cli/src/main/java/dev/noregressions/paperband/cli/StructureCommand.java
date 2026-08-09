package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Dump the document structure as an indented text outline — no rendering,
 * no Playwright, just the loaded card model.
 *
 * <p>For a book directory this shows exactly what {@code build} would
 * assemble, in order: cover, axis divider pages (one line per axis a card is
 * first-of-value for), folder-based sections (the axis-less fallback), each
 * card with its resolved axis values and source file, that card's H2+ block
 * tree, and the back page. Because pagewright's model is a flat ordered walk
 * with dividers <em>derived</em> from it (not a nested tree), interleaved
 * axis values produce repeated DIVIDER lines here — matching the repeated
 * divider pages the PDF would get.
 *
 * <p>For a single {@code .md}/{@code .yaml} card it shows just that card's
 * block tree.
 *
 * <pre>
 * pagewright structure path/to/book                 # print to stdout
 * pagewright structure path/to/book structure.txt   # write to a file
 * pagewright structure path/to/card.md
 * </pre>
 */
@Command(
        name = "structure",
        mixinStandardHelpOptions = true,
        description = "Dump the document structure (cover, dividers, sections, cards, blocks) "
                + "as an indented text outline, without rendering anything.")
public final class StructureCommand implements Callable<Integer> {

    @Option(
            names = {"-t", "--target"},
            description = "Build target (affects the config cascade, same as `build`). Default: ${DEFAULT-VALUE}",
            defaultValue = "pdf-a4")
    String target;

    @Option(
            names = {"-s", "--page-size"},
            description = "Page size. Values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}",
            defaultValue = "A4")
    RenderCommand.PageSizeOption pageSize;

    @Option(
            names = {"--external-include-dir"},
            paramLabel = "<dir>",
            description = "Permit {{#include}} directives to read files under this directory "
                    + "(same as `build`; includes can add headings, so they affect the outline).")
    List<Path> externalIncludeDirs;

    @Option(
            names = {"--external-include-file"},
            paramLabel = "<file>",
            description = "Permit {{#include}} directives to read this specific file (same as `build`).")
    List<Path> externalIncludeFiles;

    @Parameters(index = "0", description = "Input markdown/yaml card file or book directory.")
    Path input;

    @Parameters(
            index = "1",
            arity = "0..1",
            description = "Optional output text file. Prints to stdout when omitted.")
    Path output;

    @Override
    public Integer call() throws Exception {
        String text;
        if (Files.isRegularFile(input)) {
            text = describeSingle();
        } else if (Files.isDirectory(input)) {
            text = describeBook();
            if (text == null) return 2;
        } else {
            System.err.println("Input not found: " + input);
            return 2;
        }
        if (output == null) {
            System.out.print(text);
        } else {
            Files.writeString(output, text, StandardCharsets.UTF_8);
            System.out.println("Wrote structure -> " + output);
        }
        return 0;
    }

    private String describeSingle() {
        RenderContext ctx = new ConfigLoader().load(input, target, pageSize.slug());
        MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                ctx.book().bookRoot(),
                BuildCommand.includeProviderConfig(externalIncludeDirs, externalIncludeFiles),
                ctx.vars());
        Card card = BuildCommand.loadCard(new CardLoader(), preprocessor, input, ctx.book().cardSchema());
        return LayoutEngine.describeCard(card);
    }

    /**
     * Load the book exactly the way {@link BuildCommand#buildBook()} does —
     * same walker, same per-card config cascade, same per-card preprocessor —
     * then describe it instead of rendering it, so the outline can't drift
     * from what a real build would assemble.
     */
    private String describeBook() {
        BookWalker walker = new BookWalker(target);
        List<Path> cardFiles = walker.walk(input);
        if (cardFiles.isEmpty()) {
            System.err.println("No cards found under " + input);
            return null;
        }

        ConfigLoader configLoader = new ConfigLoader();
        CardLoader cardLoader = new CardLoader();
        Map<String, Map<String, Object>> providerConfig =
                BuildCommand.includeProviderConfig(externalIncludeDirs, externalIncludeFiles);

        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(cardFile, target, pageSize.slug());
            if (bookCtx == null) bookCtx = ctx;
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), providerConfig, ctx.vars());
            cards.add(BuildCommand.loadCard(cardLoader, preprocessor, cardFile, ctx.book().cardSchema()));
        }
        return LayoutEngine.describeBook(cards, contexts, bookCtx);
    }
}
