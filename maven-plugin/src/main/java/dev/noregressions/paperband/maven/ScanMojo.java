package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parses one card and prints what Paperband made of it: the frontmatter it
 * read, the block tree it built, and the config the cascade resolved around it
 * (book root, css chain, vars, axes).
 *
 * <p>The authoring diagnostic for "why did my card render like that" — a
 * heading that didn't become the block you expected, or a var that resolved to
 * something you didn't set.
 *
 * <pre>mvn paperband:scan -Dpaperband.input=guide/03-configuration/01-book-config.md</pre>
 */
@Mojo(name = "scan", requiresProject = false, threadSafe = true)
public class ScanMojo extends AbstractPaperbandMojo {

    /** The markdown or yaml card to inspect. */
    @Parameter(property = "paperband.input", required = true)
    private java.io.File input;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("scan")) return;

        Path cardFile = resolve(input);
        if (!Files.isRegularFile(cardFile)) {
            throw new MojoExecutionException("<input> card file not found: " + cardFile);
        }

        RenderContext ctx = new ConfigLoader().load(cardFile, target, pageSize, resolveMargins());
        Card card = new CardLoader().load(cardFile);

        printCard(card);
        getLog().info("");
        printContext(ctx);
    }

    private void printCard(Card card) {
        getLog().info("=== CARD ===");
        getLog().info("id     : " + card.id());
        getLog().info("source : " + card.source());
        getLog().info("title  : " + (card.title() == null ? "<none>" : card.title()));
        getLog().info("frontmatter:");
        if (card.frontmatter().values().isEmpty()) {
            getLog().info("  <empty>");
        } else {
            for (Map.Entry<String, Object> e : card.frontmatter().values().entrySet()) {
                getLog().info("  " + e.getKey() + " = " + e.getValue());
            }
        }
        getLog().info("blocks (" + card.blocks().size() + " top-level):");
        printBlocks(card.blocks(), 1, "  ");
    }

    /** Recursively dumps a block tree, indenting one level per nesting depth. */
    private void printBlocks(List<Block> blocks, int startIndex, String indent) {
        int i = startIndex;
        for (Block b : blocks) {
            getLog().info(String.format("%s[%d] %s (level %d)", indent, i++, b.kind(), b.level()));
            getLog().info(indent + "    heading : " + (b.heading() == null ? "<intro>" : b.heading()));
            getLog().info(indent + "    id      : " + (b.id() == null ? "<none>" : b.id()));
            getLog().info(indent + "    classes : " + b.classes());
            getLog().info(indent + "    html    : " + summarise(b.html()));
            if (!b.children().isEmpty()) {
                getLog().info(indent + "    children (" + b.children().size() + "):");
                printBlocks(b.children(), 1, indent + "      ");
            }
        }
    }

    private void printContext(RenderContext ctx) {
        getLog().info("=== CONTEXT ===");
        getLog().info("book root : " + ctx.book().bookRoot());
        getLog().info("title     : " + (ctx.book().title() == null ? "<none>" : ctx.book().title()));
        getLog().info("target    : " + ctx.target());
        getLog().info("size      : " + ctx.size());
        getLog().info("layout    : " + (ctx.layout() == null ? "<none>" : ctx.layout()));
        getLog().info("css chain (" + ctx.cssChain().size() + "):");
        for (Path p : ctx.cssChain()) {
            getLog().info("  - " + p);
        }
        getLog().info("vars (" + ctx.vars().size() + "):");
        for (Map.Entry<String, Object> e : ctx.vars().entrySet()) {
            getLog().info("  " + e.getKey() + " = " + e.getValue());
        }
        getLog().info("axes (" + ctx.book().axes().size() + "):");
        for (Axis a : ctx.book().axes()) {
            getLog().info("  - " + a.name() + " (\"" + a.title() + "\")");
            for (AxisValue v : a.values()) {
                getLog().info("      " + v.id() + " : " + v.label());
            }
        }
    }

    private static String summarise(String html) {
        if (html == null) return "<null>";
        String compact = html.replaceAll("\\s+", " ").trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 77) + "...";
    }
}
