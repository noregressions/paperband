package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.OutlineEntry;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** What a rendered book holds, read back from its output: printed cards, reachable bookmarks. */
class PrintedTest {

    private static Card card(String id, Path source) {
        return new Card(id, source, new Frontmatter(Map.of()), id.toUpperCase(), List.of(
                new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0, "<p>x</p>", List.of())));
    }

    private static RenderContext ctx(BookConfig book) {
        return new RenderContext(book, List.of(), Map.of("toc", true), null, "pdf", "A4");
    }

    @Test
    void a_card_its_templates_never_printed_fails_naming_it(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/_card-body.html"), "<article class=\"card\">{{ card.title }}</article>");
        BookConfig config = new BookConfig(book, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        Card a = card("alpha", book.resolve("alpha.md"));

        UnprintedCardException e = assertThrows(UnprintedCardException.class,
                () -> new LayoutEngine(book).renderBook(List.of(a), List.of(ctx(config)), ctx(config)));
        assertTrue(e.getMessage().contains("never printed: alpha"), e.getMessage());
        assertTrue(e.getMessage().contains("keep.html"), "it says how to leave a card out: " + e.getMessage());
    }

    @Test
    void a_divider_page_a_template_left_out_loses_its_bookmark_and_its_cards_move_up(@TempDir Path book)
            throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/_section-divider.html"), "<p>no page here</p>");
        Path lab = book.resolve("route/lab.md");
        Section route = new Section("route", "The Route", List.of(), null, List.of(lab));
        BookConfig config = new BookConfig(book, "Book", List.of(), List.of(), Map.of(), List.of(),
                null, null, null, null, null, null, null, List.of(route));
        LayoutEngine engine = new LayoutEngine(book);

        engine.renderBook(List.of(card("lab", lab)), List.of(ctx(config)), ctx(config));

        assertTrue(engine.droppedBookmarks().stream().anyMatch(b -> b.contains("#section-divider-route")),
                engine.droppedBookmarks().toString());
        OutlineEntry lab1 = engine.outline().stream().filter(o -> o.anchor().equals("card-lab")).findFirst()
                .orElseThrow();
        assertEquals(0, lab1.depth(), "not left nested under an entry that isn't there: " + engine.outline());
    }

    @Test
    void lifting_stops_at_the_next_entry_as_shallow_as_the_dropped_one() {
        Printed printed = new Printed("<div id=\"a\"></div><div id=\"a1\"></div><div id=\"b\"></div>"
                + "<div id=\"c\"></div><div id=\"c1\"></div>");
        List<String> dropped = new ArrayList<>();
        List<OutlineEntry> out = printed.reachable(List.of(
                new OutlineEntry("A", "a", 0), new OutlineEntry("A1", "a1", 1),
                new OutlineEntry("Gone", "gone", 0), new OutlineEntry("B", "b", 1),
                new OutlineEntry("C", "c", 0), new OutlineEntry("C1", "c1", 1)), dropped);
        assertEquals(List.of("a:0", "a1:1", "b:0", "c:0", "c1:1"),
                out.stream().map(o -> o.anchor() + ":" + o.depth()).toList());
        assertEquals(List.of("'Gone' (#gone)"), dropped);
    }
}
