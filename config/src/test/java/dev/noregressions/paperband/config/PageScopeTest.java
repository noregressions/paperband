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

    /**
     * What a card says its size is. The name is not decoration: the print
     * templates stamp it on {@code <html>} as {@code size-<name>}, and bundled
     * themes hang page-density type off exactly that
     * ({@code html.size-6x9 { font-size: 12pt }}). So the reported name has to
     * follow the sheet that resolved — if it echoed the caller's slug instead,
     * a book could be laid out on one sheet and typeset for another.
     */
    @Nested
    @DisplayName("The reported size")
    class ReportedSize {

        @Test
        void follows_the_books_yaml_rather_than_the_callers_slug(@TempDir Path dir) throws IOException {
            // The bug this pins: <pageSize> defaults to a4, the book asks for
            // 6x9, and the card used to report "a4" — so html.size-a4's 11pt
            // typeset a 6x9 page that the theme has a 12pt rule for.
            Path card = book(dir, "title: T\npage:\n  size: 6x9\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals("6x9", ctx.size());
        }

        @Test
        void is_canonical_so_an_alias_normalises(@TempDir Path dir) throws IOException {
            // packt and 7.5x9.25 are the same sheet; one name reaches the CSS.
            Path card = book(dir, "title: T\npage:\n  size: 7.5x9.25\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals("packt", ctx.size());
        }

        @Test
        void names_a_slide_deck_a_slide(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\npage:\n  size: 16x9\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertEquals("16x9", ctx.size());
        }

        @Test
        void a_custom_sheet_reports_dimensions_and_keeps_its_font_scale(@TempDir Path dir) throws IOException {
            // The other half of the same bug, and the worse half: a custom
            // size used to report "a4", and html.size-a4's FIXED font-size
            // outranks the bare html rule that consumes --pw-font-scale — so
            // the scale the resolver computed for the odd sheet was dead on
            // arrival. No preset name, no accidental match, scale lives.
            Path card = book(dir,
                    "title: T\npage:\n  size: { width: 200, height: 150, unit: mm }\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertAll(
                    () -> assertEquals("200x150mm", ctx.size()),
                    () -> assertNotNull(ctx.fontScale(),
                            "an unrecognised sheet still gets the width-ratio scale"));
        }

        @Test
        void the_base_still_shows_through_when_the_yaml_says_nothing(@TempDir Path dir) throws IOException {
            Path card = book(dir, "title: T\n", null);

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "letter");

            assertEquals("letter", ctx.size());
        }

        @Test
        void rotation_does_not_rename_the_sheet(@TempDir Path dir) throws IOException {
            // orientation-* is its own class on <html>; a landscape A4 is
            // still an a4 sheet and still wants A4's type rule.
            Path card = book(dir, "title: T\n", "page:\n  orientation: landscape\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "a4");

            assertAll(
                    () -> assertEquals("a4", ctx.size()),
                    () -> assertEquals(Orientation.LANDSCAPE, ctx.pageSpec().orientation()));
        }
    }
}
