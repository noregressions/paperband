package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Sidebar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The site sidebar as book-scope config.
 *
 * <p>It was three {@code vars} entries, which put a whole-site structural
 * switch on the per-card channel: the site read only the copy belonging to
 * whichever card was walked first, so a folder that set them either did nothing
 * or changed the entire site depending on walk order.
 */
@DisplayName("sidebar scope")
class SidebarScopeTest {

    private static Path book(Path dir, String rootYaml, String folderYaml) throws IOException {
        Files.writeString(dir.resolve("paperband.yaml"), rootYaml);
        Path folder = Files.createDirectories(dir.resolve("chapter"));
        if (folderYaml != null) Files.writeString(folder.resolve("paperband.yaml"), folderYaml);
        Path card = folder.resolve("card.md");
        Files.writeString(card, "# Card\n");
        return card;
    }

    private static Sidebar sidebarOf(Path card) {
        RenderContext ctx = new ConfigLoader().load(card, "web", "a4");
        return ctx.book().sidebar();
    }

    @Nested
    @DisplayName("Declaring it")
    class Declaring {

        @Test
        void absent_means_no_sidebar(@TempDir Path dir) throws IOException {
            assertEquals(Sidebar.NONE, sidebarOf(book(dir, "title: T\n", null)));
        }

        @Test
        void a_bare_boolean_is_the_shorthand(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nsidebar: true\n", null));

            assertTrue(s.enabled());
            assertFalse(s.collapsed(), "the sidebar itself starts open");
            assertTrue(s.sectionsCollapsed(), "but its section lists start closed");
        }

        @Test
        void false_turns_it_off(@TempDir Path dir) throws IOException {
            assertFalse(sidebarOf(book(dir, "title: T\nsidebar: false\n", null)).enabled());
        }

        @Test
        void the_map_form_opts_in_by_being_present(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nsidebar:\n  collapsed: true\n", null));

            assertTrue(s.enabled(), "declaring the map at all means you want one");
            assertTrue(s.collapsed());
        }

        @Test
        void the_map_form_can_still_disable(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nsidebar:\n  enabled: false\n", null));

            assertFalse(s.enabled());
        }

        @Test
        void sections_can_ship_expanded(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nsidebar:\n  sectionsCollapsed: false\n", null));

            assertTrue(s.enabled());
            assertFalse(s.sectionsCollapsed());
        }

        @Test
        void an_unknown_key_is_rejected(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\nsidebar:\n  colapsed: true\n", null);

            ConfigParseException e = assertThrows(ConfigParseException.class, () -> sidebarOf(card));

            assertTrue(e.getMessage().contains("colapsed"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("Scope")
    class Scope {

        @Test
        void a_folder_may_not_declare_it(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n", "sidebar: true\n");

            ConfigParseException e = assertThrows(ConfigParseException.class, () -> sidebarOf(card));

            assertTrue(e.getMessage().contains("book root"), e.getMessage());
            assertTrue(e.getMessage().contains("chapter"), "names the file: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("Deprecated vars spelling")
    class Vars {

        @Test
        void vars_sidebar_still_works(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nvars:\n  sidebar: true\n", null));

            assertTrue(s.enabled());
        }

        @Test
        void the_old_collapse_vars_still_work(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nvars:\n  sidebar: true\n"
                    + "  sidebar_collapsed: true\n  sidebar_sections_collapsed: false\n", null));

            assertTrue(s.enabled());
            assertTrue(s.collapsed());
            assertFalse(s.sectionsCollapsed());
        }

        @Test
        void the_top_level_key_wins_over_the_vars_spelling(@TempDir Path dir) throws IOException {
            Sidebar s = sidebarOf(book(dir, "title: T\nsidebar: false\nvars:\n  sidebar: true\n", null));

            assertFalse(s.enabled(), "the declaration beats the deprecated alias");
        }

        @Test
        void yes_is_truthy(@TempDir Path dir) throws IOException {
            // SnakeYAML's 1.2 schema reads `yes` as the string "yes", not a
            // boolean, so the truthiness rule has to cover it.
            assertTrue(sidebarOf(book(dir, "title: T\nsidebar: yes\n", null)).enabled());
        }

        @Test
        void one_is_truthy(@TempDir Path dir) throws IOException {
            assertTrue(sidebarOf(book(dir, "title: T\nsidebar: 1\n", null)).enabled());
        }
    }
}
