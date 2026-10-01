package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
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
 * {@code {!number}} filled in when the book is assembled: the card's bare
 * number wherever its mark landed, before any template reads the card.
 */
class OwnNumberTest {

    private static final String M = CardNumber.MARK;

    /** As loading leaves {@code ## Scenario {!number} recap} and a paragraph with the marker. */
    private static Card card() {
        String html = "<p>This is scenario " + M + " of three.</p>";
        Block recap = new Block(Block.Kind.HEADING_SECTION, null, Set.of("scenario-number-recap"),
                "Scenario " + M + " recap", 2, html, List.of(), Map.of(), Map.of(), ContentNodes.of(html));
        return new Card("login", Path.of("login.md"), new Frontmatter(Map.of()), "Login fails", List.of(recap));
    }

    private static RenderContext ctx() {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
    }

    private static LayoutEngine numbered(Path layouts) {
        LayoutEngine engine = layouts == null ? new LayoutEngine() : new LayoutEngine(layouts);
        engine.setCardNumbers(Map.of("login", new CardNumber(1, 3, "Scenario {n}")));
        return engine;
    }

    @Test
    void the_book_prints_the_cards_bare_number_where_the_marker_was() {
        String html = numbered(null).renderBook(List.of(card()), List.of(ctx()), ctx());
        assertTrue(html.contains("This is scenario 3 of three."), html);
        assertTrue(html.contains("Scenario 3 recap"), html);
        assertFalse(html.contains(M), "no mark left anywhere");
        assertTrue(html.contains("id=\"scenario-number-recap\""), "the anchor stays put when the number moves");
    }

    @Test
    void the_site_prints_it_too() {
        Map<String, String> site = numbered(null).renderSite(List.of(card()), List.of(ctx()), ctx());
        assertTrue(site.get("cards/login.html").contains("This is scenario 3 of three."));
    }

    @Test
    void find_and_html_see_the_number(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/t.html"),
                "[{{ (card.blocks[0] | find('p') | first).text }}][{{ card.blocks[0] | html | raw }}]");
        String html = numbered(book).render(card(), ctx(), "t");
        assertTrue(html.contains("[This is scenario 3 of three.][<p>This is scenario 3 of three.</p>]"), html);
    }

    @Test
    void a_chapter_number_prints_dotted() {
        LayoutEngine engine = new LayoutEngine();
        engine.setCardNumbers(Map.of("login", new CardNumber(2, 4)));
        assertTrue(engine.renderBook(List.of(card()), List.of(ctx()), ctx()).contains("This is scenario 2.4 of three."));
    }

    @Test
    void a_book_that_doesnt_number_the_card_fails_naming_it() {
        LayoutException e = assertThrows(LayoutException.class,
                () -> new LayoutEngine().renderBook(List.of(card()), List.of(ctx()), ctx()));
        assertTrue(e.getMessage().contains("{!number}"), e.getMessage());
        assertTrue(e.getMessage().contains("login (login.md)"), e.getMessage());
    }

    @Test
    void a_one_card_preview_prints_nothing_in_its_place() {
        String html = new LayoutEngine().render(card(), ctx());
        assertTrue(html.contains("This is scenario  of three."), html);
        assertFalse(html.contains(M), html);
    }
}
