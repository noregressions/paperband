package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.number.SectionNumbering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A section that declares {@code numbering: "Scenario {n}"}: its cards are
 * numbered whether or not the book numbers its chapters, and the number shows
 * wherever the card is listed.
 */
class SectionNumberFormatTest {

    private static Card card(Path root, String folder, String id, String title) {
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of("x"), "X", 2, "<p>Text.</p>", List.of());
        return new Card(id, root.resolve(folder).resolve(id + ".md"), new Frontmatter(Map.of()), title, List.of(b));
    }

    private static List<Card> cards(Path root) {
        return List.of(card(root, "basics", "intro", "Introduction"),
                card(root, "scenarios", "login", "Login fails"),
                card(root, "scenarios", "reset", "Password reset"));
    }

    private static LayoutEngine engine() {
        LayoutEngine engine = new LayoutEngine();
        engine.setSectionBodies(Map.of("scenarios", new SectionBody("", "Scenarios", true,
                new SectionNumbering(true, null, "Scenario {n}"), null, true)));
        return engine;
    }

    private static String labels(Map<String, CardNumber> numbers) {
        StringBuilder sb = new StringBuilder();
        numbers.forEach((id, n) -> sb.append(id).append('=').append(n.label()).append(';'));
        return sb.toString();
    }

    @Test
    void a_formatted_section_is_numbered_without_the_books_numbering(@TempDir Path root) {
        assertEquals("login=Scenario 1;reset=Scenario 2;",
                labels(engine().cardNumbers(root, List.of(), cards(root), Map.of())));
    }

    @Test
    void with_the_books_numbering_the_other_sections_keep_chapter_numbers(@TempDir Path root) {
        assertEquals("intro=1.1;login=Scenario 1;reset=Scenario 2;",
                labels(engine().cardNumbers(root, List.of(), cards(root), Map.of("numbering", "sequential"))));
    }

    @Test
    void a_book_with_neither_has_no_numbers(@TempDir Path root) {
        assertTrue(new LayoutEngine().cardNumbers(root, List.of(), cards(root), Map.of()).isEmpty());
    }

    @Test
    void the_site_shows_the_number_wherever_it_lists_the_card(@TempDir Path root) {
        List<Card> cards = cards(root);
        LayoutEngine engine = engine();
        engine.setCardNumbers(engine.cardNumbers(root, List.of(), cards, Map.of()));
        BookConfig book = new BookConfig(root, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(), Map.of(), null, "web", null);
        Map<String, String> site = engine.renderSite(cards, List.of(ctx, ctx, ctx), ctx);
        assertTrue(site.get("scenarios.html").contains(
                "<span class=\"card-number\">Scenario 2</span> Password reset"), "the section's card grid");
        String login = site.get("cards/login.html");
        assertTrue(login.contains("<span class=\"card-number\">Scenario 1</span> Login fails"), "the card's title");
        assertTrue(login.contains("<span class=\"card-number\">Scenario 2</span> Password reset →"), "next");
        assertTrue(login.contains("<title>Scenario 1 Login fails — Book</title>"), login);
        assertFalse(site.get("basics.html").contains("card-number"), "an unnumbered section shows none");
    }
}
