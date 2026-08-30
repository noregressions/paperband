package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.render.Orientation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Page geometry's scope: {@code size}/{@code margins} belong to the book,
 * {@code orientation} belongs to a block and cascades.
 */
class PageScopeTest {

    /** A book root with the given yaml, one folder, and one card inside it. */
    private static Path book(Path dir, String rootYaml, String folderYaml) throws IOException {
        Files.writeString(dir.resolve("paperband.yaml"), rootYaml);
        Path folder = Files.createDirectories(dir.resolve("chapter"));
        if (folderYaml != null) Files.writeString(folder.resolve("paperband.yaml"), folderYaml);
        Path card = folder.resolve("card.md");
        Files.writeString(card, "# Card\n");
        return card;
    }

    @Nested
    @DisplayName("Book scope")
    class BookScope {

        @Test
        void page_block_at_the_book_root_sets_the_sheet(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\npage:\n  size: a5\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals(148.0, ctx.pageSpec().size().width(), 0.01,
                    "the book root's page.size should win over the <pageSize> base");
        }

        @Test
        void vars_page_still_works_as_the_deprecated_alias(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\nvars:\n  page:\n    size: a5\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals(148.0, ctx.pageSpec().size().width(), 0.01);
        }

        @Test
        void top_level_page_is_published_as_vars_page(@TempDir Path dir) throws IOException {
            // page.measure is read off vars.page downstream, so the two
            // spellings have to be one thing by the time the cascade is done.
            Path card = book(dir, "title: T\npage:\n  measure: 41rem\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertInstanceOf(java.util.Map.class, ctx.vars().get("page"));
            assertEquals("41rem", ((java.util.Map<?, ?>) ctx.vars().get("page")).get("measure"));
        }

        @Test
        void a_folder_may_not_resize_the_book(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n", "page:\n  size: a5\n");

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(card, "pdf", "a4"));

            assertTrue(e.getMessage().contains("page.size"), e.getMessage());
            assertTrue(e.getMessage().contains("book root"), e.getMessage());
        }

        @Test
        void a_folder_may_not_change_the_book_margins(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n",
                    "vars:\n  page:\n    margins: { top: 5, right: 5, bottom: 5, left: 5 }\n");

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(card, "pdf", "a4"));

            assertTrue(e.getMessage().contains("page.margins"), e.getMessage());
        }

        @Test
        void a_deep_folder_never_decides_the_books_sheet(@TempDir Path dir) throws IOException {
            // The regression this whole scope split exists for: geometry used to
            // be read from the merged cascade, so a folder's page block became
            // the whole book's geometry or nothing at all depending on walk
            // order. Orientation is card scope, so the BOOK's sheet must be
            // unaffected by it.
            Path card = book(dir, "title: T\n", "page:\n  orientation: landscape\n");
            ConfigLoader loader = new ConfigLoader();

            RenderContext ctx = loader.load(card, "pdf", "a4");

            assertEquals(Orientation.LANDSCAPE, ctx.pageSpec().orientation(),
                    "the card prints rotated");
            assertEquals(Orientation.PORTRAIT, loader.bookPageSpec().orientation(),
                    "but the book's own sheet stays as declared");
        }
    }

    @Nested
    @DisplayName("Card scope")
    class CardScope {

        @Test
        void orientation_cascades_to_a_folders_cards(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n", "page:\n  orientation: landscape\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals(Orientation.LANDSCAPE, ctx.pageSpec().orientation());
        }

        @Test
        void rotation_swaps_the_content_height_not_the_paper(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\npage:\n  margins: { top: 0, right: 0, bottom: 0, left: 0 }\n",
                    "page:\n  orientation: landscape\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals(210.0, ctx.pageSpec().contentHeightMm(), 0.01,
                    "a rotated A4 is 210mm tall, and the content box must say so");
            assertEquals(210.0, ctx.pageSpec().size().width(), 0.01,
                    "the paper itself is untouched — still A4");
        }

        @Test
        void a_deeper_folder_wins(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("paperband.yaml"), "title: T\n");
            Path outer = Files.createDirectories(dir.resolve("outer"));
            Files.writeString(outer.resolve("paperband.yaml"), "page:\n  orientation: landscape\n");
            Path inner = Files.createDirectories(outer.resolve("inner"));
            Files.writeString(inner.resolve("paperband.yaml"), "page:\n  orientation: portrait\n");
            Path card = inner.resolve("card.md");
            Files.writeString(card, "# Card\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals(Orientation.PORTRAIT, ctx.pageSpec().orientation(),
                    "innermost wins, like every other card-scope key");
        }

        @Test
        void a_bad_orientation_names_the_file(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n", "page:\n  orientation: sideways\n");

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(card, "pdf", "a4"));

            assertTrue(e.getMessage().contains("sideways"), e.getMessage());
            assertTrue(e.getMessage().contains("chapter"), e.getMessage());
        }
    }
}
