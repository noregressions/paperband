package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cheat-sheet mode: {@code vars.cheatsheet} makes a card its steps. */
class CheatSheetTest {

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

    private static String render(Map<String, Object> vars) {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new LayoutEngine().render(card(), new RenderContext(book, List.of(), vars, null, "pdf", "A4"));
    }

    @Nested
    @DisplayName("Switching it on")
    class Enabled {

        @Test
        void by_boolean_or_string() {
            assertTrue(CheatSheet.enabled(Map.of("cheatsheet", true)));
            assertTrue(CheatSheet.enabled(Map.of("cheatsheet", "true")));
        }

        @Test
        void off_when_absent_or_false() {
            assertFalse(CheatSheet.enabled(Map.of()));
            assertFalse(CheatSheet.enabled(null));
            assertFalse(CheatSheet.enabled(Map.of("cheatsheet", false)));
            assertFalse(CheatSheet.enabled(Map.of("cheatsheet", "false")));
        }

        @Test
        void the_selector_defaults_and_is_set_by_its_own_key() {
            assertEquals(CheatSheet.DEFAULT_SELECT, CheatSheet.model(Map.of("cheatsheet", true)).get("select"));
            assertEquals("p.b", CheatSheet.model(Map.of("cheatsheet", "true", "cheatsheetSelect", "p.b")).get("select"));
            assertNull(CheatSheet.model(Map.of()));
        }

        @Test
        void a_selector_without_the_mode_turns_nothing_on() {
            // A folder's paperband.yaml can say what steps keep; only the
            // cheat-sheet build's own execution switches the mode on.
            assertFalse(CheatSheet.enabled(Map.of("cheatsheetSelect", "p.b")));
            assertNull(CheatSheet.model(Map.of("cheatsheetSelect", "p.b")));
        }

        @Test
        void a_map_fails_naming_the_two_keys_to_use() {
            LayoutException e = assertThrows(LayoutException.class,
                    () -> CheatSheet.enabled(Map.of("cheatsheet", Map.of("select", "p"))));
            assertTrue(e.getMessage().contains("cheatsheet: true"), e.getMessage());
            assertTrue(e.getMessage().contains("cheatsheetSelect"), e.getMessage());
        }

        @Test
        void a_card_has_steps_when_any_block_at_any_depth_does() {
            assertTrue(CheatSheet.hasSteps(card()));
            Card none = new Card("x", Path.of("x.md"), new Frontmatter(Map.of()), "X",
                    List.of(block("A", 2, "<p>a</p>", List.of(block("B", 3, "<p>b</p>", List.of(), null)), null)));
            assertFalse(CheatSheet.hasSteps(none));
        }
    }

    @Nested
    @DisplayName("Rendering a card")
    class Rendering {

        @Test
        void off_the_card_renders_in_full() {
            String html = render(Map.of());
            assertFalse(html.contains("cheatsheet-card"), html);
            assertTrue(html.contains("Background."), html);
        }

        @Test
        void on_the_card_is_its_steps_under_its_title() {
            String html = render(Map.of("cheatsheet", true));
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
            String html = render(Map.of("cheatsheet", true));
            int install = html.indexOf("<h3>Step 1 Install</h3>");
            int check = html.indexOf("<h3>Step 1 Check Java</h3>");
            int build = html.indexOf("<h3>Step 2 Build</h3>");
            assertTrue(install < check && check < build, html);
            assertTrue(html.contains("cheatsheet-step cheatsheet-depth-1\">\n    <h3>Step 1 Check Java</h3>")
                    || html.matches("(?s).*cheatsheet-depth-1\">\\s*<h3>Step 1 Check Java</h3>.*"), html);
        }

        @Test
        void the_books_selector_decides_what_each_step_contributes() {
            String html = render(Map.of("cheatsheet", true, "cheatsheetSelect", "pre"));
            assertTrue(html.contains("java -version"), html);
            assertFalse(html.contains("Install a JDK."), html);
        }
    }
}
