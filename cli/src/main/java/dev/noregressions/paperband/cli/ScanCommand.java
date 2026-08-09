package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Loads a markdown card and prints its parsed structure plus the resolved
 * config cascade. End-to-end demonstration of the cards + config modules.
 *
 * <pre>paperband scan path/to/card.md</pre>
 */
@Command(
        name = "scan",
        mixinStandardHelpOptions = true,
        description = "Parse a markdown card and print its structure + resolved config.")
public final class ScanCommand implements Callable<Integer> {

    @Option(
            names = {"-t", "--target"},
            description = "Build target the predicate context will use. Default: ${DEFAULT-VALUE}",
            defaultValue = "pdf-a4")
    String target;

    @Option(
            names = {"-s", "--size"},
            description = "Page size the predicate context will use. Default: ${DEFAULT-VALUE}",
            defaultValue = "A4")
    String size;

    @Parameters(index = "0", description = "Markdown card file.")
    Path input;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(input)) {
            System.err.println("File not found: " + input);
            return 2;
        }

        RenderContext ctx = new ConfigLoader().load(input, target, size);
        Card card = new CardLoader().load(input);

        printCard(card);
        System.out.println();
        printContext(ctx);
        return 0;
    }

    private static void printCard(Card card) {
        System.out.println("=== CARD ===");
        System.out.println("id     : " + card.id());
        System.out.println("source : " + card.source());
        System.out.println("title  : " + (card.title() == null ? "<none>" : card.title()));
        System.out.println("frontmatter:");
        if (card.frontmatter().values().isEmpty()) {
            System.out.println("  <empty>");
        } else {
            for (Map.Entry<String, Object> e : card.frontmatter().values().entrySet()) {
                System.out.println("  " + e.getKey() + " = " + e.getValue());
            }
        }
        System.out.println("blocks (" + card.blocks().size() + " top-level):");
        printBlocks(card.blocks(), 1, "  ");
    }

    /** Recursively dumps a block tree, indenting one level per nesting depth. */
    private static void printBlocks(List<Block> blocks, int startIndex, String indent) {
        int i = startIndex;
        for (Block b : blocks) {
            System.out.printf("%s[%d] %s (level %d)%n", indent, i++, b.kind(), b.level());
            System.out.println(indent + "    heading : " + (b.heading() == null ? "<intro>" : b.heading()));
            System.out.println(indent + "    id      : " + (b.id() == null ? "<none>" : b.id()));
            System.out.println(indent + "    classes : " + b.classes());
            System.out.println(indent + "    html    : " + summarise(b.html()));
            if (!b.children().isEmpty()) {
                System.out.println(indent + "    children (" + b.children().size() + "):");
                printBlocks(b.children(), 1, indent + "      ");
            }
        }
    }

    private static void printContext(RenderContext ctx) {
        System.out.println("=== CONTEXT ===");
        System.out.println("book root : " + ctx.book().bookRoot());
        System.out.println("title     : " + (ctx.book().title() == null ? "<none>" : ctx.book().title()));
        System.out.println("target    : " + ctx.target());
        System.out.println("size      : " + ctx.size());
        System.out.println("layout    : " + (ctx.layout() == null ? "<none>" : ctx.layout()));
        System.out.println("css chain (" + ctx.cssChain().size() + "):");
        for (Path p : ctx.cssChain()) {
            System.out.println("  - " + p);
        }
        System.out.println("vars (" + ctx.vars().size() + "):");
        for (Map.Entry<String, Object> e : ctx.vars().entrySet()) {
            System.out.println("  " + e.getKey() + " = " + e.getValue());
        }
        System.out.println("axes (" + ctx.book().axes().size() + "):");
        for (Axis a : ctx.book().axes()) {
            System.out.println("  - " + a.name() + " (\"" + a.title() + "\")");
            for (AxisValue v : a.values()) {
                System.out.println("      " + v.id() + " : " + v.label());
            }
        }
    }

    private static String summarise(String html) {
        if (html == null) return "<null>";
        String compact = html.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 80) return compact;
        return compact.substring(0, 77) + "...";
    }
}
