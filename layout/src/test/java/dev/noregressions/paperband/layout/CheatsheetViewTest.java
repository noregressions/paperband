package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled cheatsheet view: a card is its steps, and a card with none is
 * left out -- decided in the view's templates, not in Java.
 */
class CheatsheetViewTest {

    private static Block block(String heading, int level, String html, List<Block> children, String step) {
        return new Block(Block.Kind.HEADING_SECTION, null, Set.of(), heading, level, html, children, Map.of(),
                step == null ? Map.of() : Map.of("step", step));
    }

    /** Intro, an unstepped aside, a step with a nested step, and a second step. */
    private static Card card() {
        Block check = block("Step 1 Check Java", 3,
                "<p class=\"instructions\">Check it.</p><pre class=\"command\"><code>java -version</code></pre>",
                List.of(), "1");
        Block install = block("Step 1 Install", 2,
                "<p>Background.</p><p class=\"instructions\">Install a JDK.</p>", List.of(check), "1");
        Block aside = block("Before you start", 2, "<p class=\"instructions\">Not a step.</p>", List.of(), null);
        Block build = block("Step 2 Build", 2,
                "<p class=\"instructions\">Build it.</p><pre class=\"console\"><code>$ mvn package</code></pre>",
                List.of(), "2");
        Block intro = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0, "<p>Intro.</p>", List.of());
        return new Card("setup", Path.of("setup.md"), new Frontmatter(Map.of()), "Setting Up",
                List.of(intro, aside, install, build));
    }

    private static RenderContext ctx(Map<String, Object> vars) {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), vars, null, "pdf", "A4");
    }

    private static String render(String view, Map<String, Object> vars) {
        LayoutEngine engine = new LayoutEngine();
        engine.setView(view);
        return engine.render(card(), ctx(vars));
    }

    /** One stepped block whose content is read as nodes, as a loaded card's is. */
    private static Card noded() {
        String html = "<p>Why.</p><p class=\"instructions\">Check the version.</p>"
                + "<pre><code class=\"language-command\">java -version\n</code></pre>";
        Block check = new Block(Block.Kind.HEADING_SECTION, null, Set.of("check"), "Step 1 Check", 2, html,
                List.of(), Map.of(), Map.of("step", "1"),
                dev.noregressions.paperband.cards.ContentNodes.of(html));
        return new Card("setup", Path.of("setup.md"), new Frontmatter(Map.of()), "Setting Up", List.of(check));
    }

    private static Card stepless() {
        return new Card("notes", Path.of("notes.md"), new Frontmatter(Map.of()), "Notes",
                List.of(block("Aside", 2, "<p>Just prose.</p>", List.of(), null)));
    }

    @Nested
    @DisplayName("Naming a view")
    class Naming {

        @Test
        void no_view_renders_the_card_in_full() {
            String html = render(null, Map.of());
            assertFalse(html.contains("cheatsheet-card"), html);
            assertTrue(html.contains("Background."), html);
        }

        @Test
        void a_view_nobody_ships_fails_naming_it() {
            LayoutException e = assertThrows(LayoutException.class, () -> new LayoutEngine().setView("cheatshet"));
            assertTrue(e.getMessage().contains("no view 'cheatshet'"), e.getMessage());
            assertTrue(e.getMessage().contains("cheatshet/keep.html"), e.getMessage());
        }

        @Test
        void a_view_name_is_one_folder() {
            assertThrows(LayoutException.class, () -> new LayoutEngine().setView("../cheatsheet"));
        }
    }

    @Nested
    @DisplayName("Which cards it keeps")
    class Keeping {

        @Test
        void the_cheatsheet_view_keeps_the_cards_with_steps() {
            LayoutEngine engine = new LayoutEngine();
            engine.setView("cheatsheet");
            assertEquals(List.of(true, false),
                    engine.keeps(List.of(card(), stepless()), List.of(ctx(Map.of()), ctx(Map.of())), "print"));
        }

        @Test
        void the_default_view_keeps_every_card() {
            assertEquals(List.of(true, true), new LayoutEngine().keeps(List.of(card(), stepless()),
                    List.of(ctx(Map.of()), ctx(Map.of())), "print"));
        }

        @Test
        void a_books_own_keep_rule_overrides_the_bundled_one(@TempDir Path book) throws IOException {
            Files.createDirectories(book.resolve("layouts/cheatsheet"));
            Files.writeString(book.resolve("layouts/cheatsheet/keep.html"), "{{ card.id == 'notes' }}");
            LayoutEngine engine = new LayoutEngine(book);
            engine.setView("cheatsheet");
            assertEquals(List.of(false, true),
                    engine.keeps(List.of(card(), stepless()), List.of(ctx(Map.of()), ctx(Map.of())), "print"));
        }

        @Test
        void a_keep_rule_that_prints_anything_else_fails_naming_the_card(@TempDir Path book) throws IOException {
            Files.createDirectories(book.resolve("layouts/handout"));
            Files.writeString(book.resolve("layouts/handout/keep.html"), "maybe");
            LayoutEngine engine = new LayoutEngine(book);
            engine.setView("handout");
            LayoutException e = assertThrows(LayoutException.class,
                    () -> engine.keeps(List.of(card()), List.of(ctx(Map.of())), "print"));
            assertTrue(e.getMessage().contains("'maybe' for card setup"), e.getMessage());
        }

        @Test
        void a_new_view_needs_only_its_keep_rule_and_falls_back_to_the_defaults(@TempDir Path book) throws IOException {
            Files.createDirectories(book.resolve("layouts/handout"));
            Files.writeString(book.resolve("layouts/handout/keep.html"), "true");
            LayoutEngine engine = new LayoutEngine(book);
            engine.setView("handout");
            String html = engine.render(card(), ctx(Map.of()));
            assertTrue(html.contains("Background."), "the default card body: " + html);
        }
    }

    @Nested
    @DisplayName("Rendering a card")
    class Rendering {

        @Test
        void the_card_is_its_steps_under_its_title() {
            String html = render("cheatsheet", Map.of());
            assertTrue(html.contains("<article class=\"cheatsheet-card\" id=\"card-setup\">"), html);
            assertTrue(html.contains("cheatsheet-card-title\">Setting Up</h2>"), html);
            assertTrue(html.contains("<h3>Step 1 Install</h3>"), html);
            assertTrue(html.contains("Install a JDK."), html);
            assertTrue(html.contains("<h3>Step 2 Build</h3>"), html);
            assertTrue(html.contains("$ mvn package"), html);
            assertFalse(html.contains("Background."), "only the selected parts: " + html);
            assertFalse(html.contains("Not a step."), "an unstepped block has no entry: " + html);
            assertFalse(html.contains("Intro."), html);
            assertFalse(html.contains("<article class=\"card"), "not the full card body: " + html);
        }

        @Test
        void a_nested_step_is_marked_one_level_deeper_and_keeps_its_order() {
            String html = render("cheatsheet", Map.of());
            int install = html.indexOf("<h3>Step 1 Install</h3>");
            int check = html.indexOf("<h3>Step 1 Check Java</h3>");
            int build = html.indexOf("<h3>Step 2 Build</h3>");
            assertTrue(install < check && check < build, html);
            assertTrue(html.matches("(?s).*cheatsheet-depth-1\"[^>]*>\\s*<h3>Step 1 Check Java</h3>.*"), html);
        }

        @Test
        void the_sites_rail_lists_the_steps_and_each_step_has_its_anchor() {
            LayoutEngine engine = new LayoutEngine();
            engine.setView("cheatsheet");
            String page = engine.renderSite(List.of(card()), List.of(ctx(Map.of())), ctx(Map.of()))
                    .get("cards/setup.html");
            int rail = page.indexOf("class=\"page-rail\"");
            assertTrue(rail >= 0, "the site page has a rail: " + page);
            String nav = page.substring(rail, page.indexOf("</nav>", rail));
            assertTrue(nav.contains("<a href=\"#install\">Step 1 Install</a>"), nav);
            assertTrue(nav.contains("<a href=\"#check-java\">Step 1 Check Java</a>"), nav);
            assertFalse(nav.contains("Before you start"), "not a heading the page doesn't carry: " + nav);
            assertTrue(page.contains("id=\"install\""), "the link lands on the step");
        }

        @Test
        void the_cards_selector_decides_what_each_step_contributes() {
            String html = render("cheatsheet", Map.of("cheatsheetSelect", "pre"));
            assertTrue(html.contains("java -version"), html);
            assertFalse(html.contains("Install a JDK."), html);
        }

        @Test
        void the_how_tos_table_example_writes_each_step_from_its_nodes(@TempDir Path book) throws IOException {
            // The example in Make a Cheat Sheet, verbatim.
            Files.createDirectories(book.resolve("layouts/cheatsheet"));
            Files.writeString(book.resolve("layouts/cheatsheet/_card-body.html"), "<article class=\"cheatsheet-card\" id=\"card-{{ card.id }}\">\n  <h2 class=\"cheatsheet-card-title\">{{ card.title }}</h2>\n  <table class=\"steps\">\n  {% for s in card.steps %}\n    {% set what = s.block | find('.instructions') | first %}\n    {% set cmd = s.block | find('pre.command') | first %}\n    <tr class=\"depth-{{ s.depth }}\">\n      <th>{{ s.block.heading }}</th>\n      <td>{% if what is not null %}{{ what.text }}{% endif %}</td>\n      <td>{% if cmd is not null %}{{ cmd | html | raw }}{% endif %}</td>\n    </tr>\n  {% endfor %}\n  </table>\n</article>");
            LayoutEngine engine = new LayoutEngine(book);
            engine.setView("cheatsheet");
            String html = engine.render(noded(), ctx(Map.of()));
            assertTrue(html.contains("<th>Step 1 Check</th>"), html);
            assertTrue(html.contains("<td>Check the version.</td>"), html);
            assertTrue(html.contains("<td><pre class=\"command\"><code class=\"language-bash\">java -version"),
                    "the command as its block template writes it: " + html);
        }

        @Test
        void an_empty_dividers_template_in_the_view_drops_the_dividers_from_the_cheat_sheet_only(
                @TempDir Path book) throws IOException {
            Files.createDirectories(book.resolve("layouts/cheatsheet"));
            Files.writeString(book.resolve("layouts/cheatsheet/dividers.html"), "");
            Path lab = book.resolve("route/lab.md");
            Card card = new Card("lab", lab, new Frontmatter(Map.of()), "Lab", card().blocks());
            dev.noregressions.paperband.model.Section route = new dev.noregressions.paperband.model.Section(
                    "route", "The Route", List.of(), null, List.of(lab));
            BookConfig config = new BookConfig(book, "Book", List.of(), List.of(), Map.of(), List.of(),
                    null, null, null, null, null, null, null, List.of(route));
            RenderContext rc = new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4");

            LayoutEngine full = new LayoutEngine(book);
            assertTrue(full.renderBook(List.of(card), List.of(rc), rc).contains("id=\"section-divider-route\""),
                    "the full guide keeps its divider");
            LayoutEngine cheat = new LayoutEngine(book);
            cheat.setView("cheatsheet");
            assertFalse(cheat.renderBook(List.of(card), List.of(rc), rc).contains("id=\"section-divider-route\""),
                    "the view's own dividers.html prints none");
        }

        @Test
        void a_books_own_view_body_overrides_the_bundled_one(@TempDir Path book) throws IOException {
            Files.createDirectories(book.resolve("layouts/cheatsheet"));
            Files.writeString(book.resolve("layouts/cheatsheet/_card-body.html"),
                    "<article id=\"card-{{ card.id }}\">{{ card.steps | length }} steps</article>");
            LayoutEngine engine = new LayoutEngine(book);
            engine.setView("cheatsheet");
            String html = engine.render(card(), ctx(Map.of()));
            assertTrue(html.contains("3 steps"), html);
        }
    }
}
