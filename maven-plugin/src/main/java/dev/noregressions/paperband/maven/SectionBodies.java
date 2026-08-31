package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.include.PebbleIncludePreprocessor;
import dev.noregressions.paperband.layout.SectionBody;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.pebble.LenientMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds and renders the markdown a section writes for itself.
 *
 * <p>A landing template is layout; what a section has to say is writing, and
 * making an author express the second as the first is the wrong tool. A
 * {@code _section.md} in a section's folder <em>is</em> that section's content —
 * on the site's landing page and on the PDF's divider alike.
 *
 * <p>The content root's own {@code _section.md} is the book's: the root is the
 * outermost section, so it needs no second filename and no second rule. It is
 * keyed by {@link #BOOK} here.
 *
 * <p>Shared by {@code build} and {@code site} rather than living in either.
 * Both targets render the same prose from the same file, and the markdown tells
 * them apart itself — {@code output} is {@code "print"} or {@code "site"}, so a
 * body can say "see the pages that follow" in one and "browse the list below"
 * in the other. That is the whole reason this is one mechanism and not two.
 */
final class SectionBodies {

    private SectionBodies() {}

    /** The key the book's own body (the content root's {@code _section.md}) is stored under. */
    static final String BOOK = "";

    /** The filenames a folder may use for its own content, in precedence order. */
    private static final List<String> FILENAMES = List.of("_section.md", "README.md");

    /**
     * Render every section body in the book, keyed by section id.
     *
     * <p>{@code README.md} works alongside {@code _section.md} because card
     * discovery already skips readmes (see {@code CardFiles.isCard}): a readme
     * is documentation about a directory, which is exactly what this is.
     * Neither is loaded as a card, so card counts are unaffected.
     *
     * <p>Only folder-backed sections can have one. An axis value is a label
     * spanning the whole book with no directory of its own.
     *
     * @param bookCtx        the resolved book context, for the cascade's vars
     * @param layoutsDir     the book's templates directory, or null
     * @param providerConfig include-provider configuration
     * @param cards          every card in the book, in walk order
     * @param output         {@code "print"} or {@code "site"} — what the markdown branches on
     * @param target         the raw build target, e.g. {@code pdf-a4} or {@code web}
     * @return rendered bodies by section id, the book's own under {@link #BOOK}
     * @throws IllegalStateException if a body exists but fails to render
     */
    static Map<String, SectionBody> render(
            RenderContext bookCtx, Path layoutsDir,
            Map<String, Map<String, Object>> providerConfig, List<Card> cards,
            String output, String target) {

        Path root = bookCtx.book().bookRoot();
        if (root == null || !Files.isDirectory(root)) return Map.of();

        Map<String, SectionBody> out = new LinkedHashMap<>();

        // The book's own body first: the content root is the outermost section.
        Path bookFile = bodyFile(root);
        if (bookFile != null) {
            out.put(BOOK, renderOne(bookFile, BOOK, root, root, bookCtx, layoutsDir,
                    providerConfig, cards, output, target));
        }

        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = bodyFile(dir);
                if (file == null) continue;
                out.put(dir.getFileName().toString(),
                        renderOne(file, dir.getFileName().toString(), dir, root, bookCtx,
                                layoutsDir, providerConfig, cards, output, target));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not scan for section bodies under " + root + ": " + e.getMessage(), e);
        }
        return out;
    }

    private static SectionBody renderOne(
            Path file, String id, Path scope, Path root, RenderContext bookCtx, Path layoutsDir,
            Map<String, Map<String, Object>> providerConfig, List<Card> cards,
            String output, String target) {
        try {
            MarkdownPreprocessor pre = Includes.defaultPreprocessor(
                    root, layoutsDir, providerConfig, bookCtx.vars());
            if (pre instanceof PebbleIncludePreprocessor pip) {
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("section", sectionModel(id, scope, cards));
                // Which output is being built. `output` is the one to branch on
                // — `target` is the raw build target and a book may rename it.
                model.put("output", output);
                model.put("target", target);
                pip.setExtraModel(model);
            }
            Card card = new CardLoader(pre, root).load(file);
            StringBuilder html = new StringBuilder();
            appendBlocks(html, card.blocks());
            Map<String, Object> fm = card.frontmatter().values();
            return new SectionBody(html.toString(), card.title(),
                    truthy(fm.get("cards")) || truthy(fm.get("sections")));
        } catch (RuntimeException e) {
            // The body is prose, not structure: a broken one should say so and
            // stop, exactly as a broken card would.
            throw new IllegalStateException(
                    "Section body " + file + " failed to render: " + e.getMessage(), e);
        }
    }

    /** The body file in {@code dir}, or null — {@code _section.md} beats {@code README.md}. */
    private static Path bodyFile(Path dir) {
        for (String name : FILENAMES) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * What {@code section} means inside the markdown: the id, and the cards
     * under {@code scope} in book order. For the book's own body that is every
     * card; for a folder's, the cards in it.
     */
    private static Map<String, Object> sectionModel(String id, Path scope, List<Card> cards) {
        Path dir = scope.toAbsolutePath().normalize();
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Card c : cards) {
            if (c.source() == null) continue;
            if (!c.source().toAbsolutePath().normalize().startsWith(dir)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("title", c.title());
            m.put("url", "cards/" + c.id() + ".html");
            m.put("anchor", "#card-" + c.id());
            Map<String, Object> fm = c.frontmatter().values();
            m.put("oneliner", fm.get("oneliner"));
            m.put("frontmatter", LenientMap.of(fm));
            mine.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("count", mine.size());
        out.put("cards", mine);
        return out;
    }

    /**
     * Flatten a card's block tree back to HTML, in document order, keeping the
     * {@code <section class="block ...">} wrappers a card page gets from
     * {@code _block-section.html}.
     *
     * <p>The wrappers are not decoration. Every theme states its prose through
     * them — {@code section.block > h2}'s marker and spacing,
     * {@code section.block p}'s measure, the {@code .watch-out} / {@code .check}
     * callout boxes — because a card's body is the only place they used to
     * appear. A body flattened to bare {@code <h2>} and {@code <p>} therefore
     * came out in the browser's defaults while the chapter beside it came out
     * in the theme's, which is the one thing a section body must not do: it is
     * the same writing, on the same page, as the cards it introduces.
     */
    private static void appendBlocks(StringBuilder sb, List<Block> blocks) {
        for (Block b : blocks) {
            String classes = String.join(" ", b.classes());
            sb.append("<section class=\"block");
            if (!classes.isEmpty()) sb.append(' ').append(classes);
            sb.append("\">\n");
            if (b.heading() != null) {
                // h1 is the section's own title -- the site hero and the PDF
                // divider each print it -- so a body's headings start at h2
                // however the markdown numbered them.
                int level = Math.max(2, b.level());
                sb.append("<h").append(level).append('>').append(escape(b.heading()))
                        .append("</h").append(level).append(">\n");
            }
            if (b.html() != null) sb.append(b.html()).append('\n');
            appendBlocks(sb, b.children());
            sb.append("</section>\n");
        }
    }

    /**
     * Heading text is plain text (jsoup's {@code Element.text()}, entities
     * already resolved), so it is escaped on the way back into markup —
     * the same thing Pebble does for {@code _block-section.html}'s
     * {@code {{ block.heading }}}.
     */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Yaml truthiness, matching the rest of the pipeline: true/yes/1, or a real boolean. */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }
}
