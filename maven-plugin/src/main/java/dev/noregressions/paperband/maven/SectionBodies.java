package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.include.PebbleIncludePreprocessor;
import dev.noregressions.paperband.layout.ContentWriter;
import dev.noregressions.paperband.layout.SectionBody;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.number.SectionNumbering;
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
     * @param blockTemplates the book's block-template resolver — the one its
     *                       cards load with, so a {@code ```type} fence renders
     *                       the same in a body as in a card; null for bundled only
     * @param log            where content-policy removals are reported, or null
     * @return rendered bodies by section id, the book's own under {@link #BOOK}
     * @throws IllegalStateException if a body exists but fails to render
     */
    static Map<String, SectionBody> render(
            RenderContext bookCtx, Path layoutsDir,
            Map<String, Map<String, Object>> providerConfig, List<Card> cards,
            String output, String target, BlockTemplates blockTemplates,
            org.apache.maven.plugin.logging.Log log) {

        Path root = bookCtx.book().bookRoot();
        if (root == null || !Files.isDirectory(root)) return Map.of();

        Map<String, SectionBody> out = new LinkedHashMap<>();

        // The book's own body first: the content root is the outermost section.
        Path bookFile = bodyFile(root);
        if (bookFile != null) {
            out.put(BOOK, renderOne(bookFile, BOOK, root, root, bookCtx, layoutsDir,
                    providerConfig, cards, output, target, blockTemplates, log));
        }

        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = bodyFile(dir);
                if (file == null) continue;
                out.put(dir.getFileName().toString(),
                        renderOne(file, dir.getFileName().toString(), dir, root, bookCtx,
                                layoutsDir, providerConfig, cards, output, target,
                                blockTemplates, log));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not scan for section bodies under " + root + ": " + e.getMessage(), e);
        }
        warnUnclaimedBodies(bookCtx, root, out, log);
        return out;
    }

    /**
     * Warn about a declared section that has no body while its folder does.
     *
     * <p>A body is found by section id, and a folder's id is its name. A
     * section declared in the POM takes its id from its title unless it sets
     * {@code <id>}, so one titled "Workshop" over {@code 04-workshop/} finds no
     * body there, and its divider prints the card list instead of the text the
     * folder has. Nothing fails, which is why it's worth saying: the fix is
     * the folder's name as the {@code <id>}.
     */
    private static void warnUnclaimedBodies(RenderContext bookCtx, Path root, Map<String, SectionBody> bodies,
                                            org.apache.maven.plugin.logging.Log log) {
        if (log == null) return;
        Path base = root.toAbsolutePath().normalize();
        for (Section section : bookCtx.book().sections()) {
            if (section.cards().isEmpty() || bodies.containsKey(section.id())) continue;
            java.util.Set<String> folders = new java.util.TreeSet<>();
            for (Path card : section.cards()) {
                Path abs = card.toAbsolutePath().normalize();
                Path rel = abs.startsWith(base) ? base.relativize(abs) : null;
                folders.add(rel == null || rel.getNameCount() < 2 ? "" : rel.getName(0).toString());
            }
            if (folders.size() != 1) continue;
            String folder = folders.iterator().next();
            if (!folder.isEmpty() && bodies.containsKey(folder) && !folder.equals(section.id())) {
                log.warn("Section '" + section.id() + "' has no _section.md of its own, but all its cards are in "
                        + folder + "/, which has one. Its divider and landing page show the card list instead."
                        + " To use that text, give the section <id>" + folder + "</id>.");
            }
        }
    }

    private static SectionBody renderOne(
            Path file, String id, Path scope, Path root, RenderContext bookCtx, Path layoutsDir,
            Map<String, Map<String, Object>> providerConfig, List<Card> cards,
            String output, String target, BlockTemplates blockTemplates,
            org.apache.maven.plugin.logging.Log log) {
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
            // Through CardLoading, as a card is: the content policy and the
            // book's block templates both apply, so a body is not a second,
            // laxer markdown dialect.
            Card card = CardLoading.load(new CardLoader(root), pre, file, null,
                    bookCtx.vars(), log, blockTemplates);
            StringBuilder html = new StringBuilder();
            appendBlocks(html, card.blocks(), new ContentWriter(blockTemplates), bookCtx.vars(), file,
                    output, target);
            Map<String, Object> fm = card.frontmatter().values();
            return new SectionBody(html.toString(), card.title(),
                    truthy(fm.get("cards")) || truthy(fm.get("sections")),
                    numbering(fm, file),
                    fm.get("part_title") == null ? null : fm.get("part_title").toString(),
                    !fm.containsKey("landing") || truthy(fm.get("landing")));
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
    private static void appendBlocks(StringBuilder sb, List<Block> blocks, ContentWriter writer,
                                     Map<String, Object> vars, Path file, String output, String target) {
        for (Block b : blocks) {
            String classes = String.join(" ", b.classes());
            sb.append("<section class=\"block");
            if (!classes.isEmpty()) sb.append(' ').append(classes);
            sb.append('"');
            // Heading attributes ({step=1}) ride on the section, as they do
            // in _block-section.html. Names are validated by CardLoader.
            for (Map.Entry<String, String> a : b.attributes().entrySet()) {
                sb.append(' ').append(a.getKey()).append("=\"")
                        .append(escape(a.getValue()).replace("\"", "&quot;")).append('"');
            }
            // Directives go on the section and its heading alike, as in
            // _block-section.html: CSS attr() can only read the heading's own.
            StringBuilder directiveAttrs = new StringBuilder();
            for (Map.Entry<String, String> d : b.directives().entrySet()) {
                directiveAttrs.append(" data-paperband-").append(d.getKey()).append("=\"")
                        .append(escape(d.getValue()).replace("\"", "&quot;")).append('"');
            }
            sb.append(directiveAttrs).append(">\n");
            if (b.heading() != null) {
                // h1 is the section's own title -- the site hero and the PDF
                // divider each print it -- so a body's headings start at h2
                // however the markdown numbered them.
                int level = Math.max(2, b.level());
                sb.append("<h").append(level).append(directiveAttrs).append('>').append(escape(b.heading()))
                        .append("</h").append(level).append(">\n");
            }
            // Written as a card's are: fences through their block templates.
            if (b.html() != null) sb.append(writer.html(b, vars, file, output, target)).append('\n');
            appendBlocks(sb, b.children(), writer, vars, file, output, target);
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

    /**
     * A section's numbering declaration: {@code part:} to share a numbering
     * group with sibling sections, {@code numbered: false} to opt out. Absent
     * keys mean {@link SectionNumbering#discovered()} — numbered, own group —
     * so an existing book's output is unchanged.
     *
     * <p>{@code part:} on an unnumbered section is contradictory rather than
     * merely redundant, and a book that wrote both almost certainly meant one
     * of them, so it stops here instead of silently picking.
     */
    private static SectionNumbering numbering(Map<String, Object> fm, Path file) {
        boolean numbered = !fm.containsKey("numbered") || truthy(fm.get("numbered"));
        String format = format(fm, file, numbered);
        Object rawPart = fm.get("part");
        if (rawPart == null) {
            if (format != null) return new SectionNumbering(true, null, format);
            return numbered ? SectionNumbering.discovered() : SectionNumbering.unnumbered();
        }
        if (!numbered) {
            throw new IllegalStateException("Section body " + file
                    + " declares both `numbered: false` and `part: " + rawPart
                    + "`. An unnumbered section has no part — drop one of them.");
        }
        Integer part = asInt(rawPart);
        if (part == null || part < 0) {
            throw new IllegalStateException("Section body " + file + " declares `part: "
                    + rawPart + "`, which is not a non-negative whole number.");
        }
        return new SectionNumbering(true, part, format);
    }

    /**
     * {@code numbering: "Scenario {n}"}: how the section's numbers read, or
     * null when it doesn't say. It has to place the number, so a format with
     * no {@code {n}} is a mistake rather than a constant label; and it numbers
     * the section, so it can't sit beside {@code numbered: false}.
     */
    private static String format(Map<String, Object> fm, Path file, boolean numbered) {
        Object raw = fm.get("numbering");
        if (raw == null) return null;
        String format = String.valueOf(raw).trim();
        if (!format.contains("{n}")) {
            throw new IllegalStateException("Section body " + file + " declares `numbering: " + raw
                    + "`, which has no {n} for the number. Write it as, say, `numbering: \"Scenario {n}\"`.");
        }
        if (!numbered) {
            throw new IllegalStateException("Section body " + file + " declares both `numbered: false` and"
                    + " `numbering: " + raw + "`. A section with a numbering format is numbered; drop one of them.");
        }
        return format;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Yaml truthiness, matching the rest of the pipeline: true/yes/1, or a real boolean. */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }
}
