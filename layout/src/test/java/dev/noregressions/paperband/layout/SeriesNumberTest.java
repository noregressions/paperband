package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code numberAs: "S{n}"} in a card's vars: a series numbered across the
 * book in book order, whichever sections its cards sit in.
 */
class SeriesNumberTest {

    private static final Map<String, Object> SCENARIO = Map.of("numberAs", "S{n}");

    private static Card card(Path root, String path, String id, String title) {
        String html = "<p>Text.</p>";
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of("x"), "X", 2, html, List.of(), Map.of(),
                Map.of(), ContentNodes.of(html));
        return new Card(id, root.resolve(path), new Frontmatter(Map.of()), title, List.of(b));
    }

    /** The workshop's shape: two parts, each mixing lessons with scenarios. */
    private static List<Card> cards(Path root) {
        return List.of(card(root, "workshop/02.md", "identification", "Identification"),
                card(root, "scenarios/spring-node/LESSON.md", "spring-node", "Spring and Node"),
                card(root, "scenarios/extended-sbom/LESSON.md", "extended-sbom", "Extended SBOM"),
                card(root, "workshop/05.md", "provenance", "Provenance"),
                card(root, "scenarios/provenance-s01/LESSON.md", "provenance-s01", "Provenance lab"));
    }

    private static List<Map<String, Object>> vars(List<Card> cards) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Card c : cards) out.add(c.source().toString().contains("/scenarios/") ? SCENARIO : Map.of());
        return out;
    }

    private static List<Section> parts(Path root, List<Card> cards) {
        return List.of(new Section("part-2", "Part 2", List.of(), null, List.of(cards.get(0).source(),
                        cards.get(1).source(), cards.get(2).source())),
                new Section("part-5", "Part 5", List.of(), null, List.of(cards.get(3).source(), cards.get(4).source())));
    }

    private static String labels(Map<String, CardNumber> numbers) {
        StringBuilder sb = new StringBuilder();
        numbers.forEach((id, n) -> sb.append(id).append('=').append(n.label()).append(';'));
        return sb.toString();
    }

    @Test
    void a_series_numbers_its_cards_across_sections_in_book_order(@TempDir Path root) {
        List<Card> cards = cards(root);
        assertEquals("spring-node=S1;extended-sbom=S2;provenance-s01=S3;",
                labels(new LayoutEngine().cardNumbers(root, parts(root, cards), cards, vars(cards), Map.of())));
    }

    @Test
    void with_chapter_numbers_the_series_cards_are_left_out_of_the_count(@TempDir Path root) {
        List<Card> cards = cards(root);
        assertEquals("identification=1.1;spring-node=S1;extended-sbom=S2;provenance=2.1;provenance-s01=S3;",
                labels(new LayoutEngine().cardNumbers(root, parts(root, cards), cards, vars(cards),
                        Map.of("numbering", "sequential"))));
    }

    @Test
    void the_title_reads_with_the_series_number(@TempDir Path root) {
        Card titled = new Card("extended-sbom", root.resolve("scenarios/extended-sbom/LESSON.md"),
                new Frontmatter(Map.of()), "S" + CardNumber.MARK + " — Extended SBOM", List.of());
        List<Card> cards = List.of(cards(root).get(1), titled);
        LayoutEngine engine = new LayoutEngine();
        engine.setCardNumbers(engine.cardNumbers(root, List.of(), cards, List.of(SCENARIO, SCENARIO), Map.of()));
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
        String html = engine.renderBook(cards, List.of(ctx, ctx), ctx);
        assertTrue(html.contains("S2 — Extended SBOM"), html);
        assertFalse(html.contains("S2 S2"), "a title that prints its number gets no second one: " + html);
        assertFalse(html.contains("<span class=\"card-number\">S2</span>"), html);
        assertTrue(html.contains("<span class=\"card-number\">S1</span>"), "a title without one keeps the prefix");
    }

    @Test
    void a_format_without_n_fails_naming_the_card(@TempDir Path root) {
        List<Card> cards = cards(root);
        List<Map<String, Object>> vars = new ArrayList<>(vars(cards));
        vars.set(1, Map.of("numberAs", "Scenario"));
        LayoutException e = assertThrows(LayoutException.class,
                () -> new LayoutEngine().cardNumbers(root, List.of(), cards, vars, Map.of()));
        assertTrue(e.getMessage().contains("spring-node/LESSON.md"), e.getMessage());
        assertTrue(e.getMessage().contains("has no {n}"), e.getMessage());
    }

    @Test
    void a_series_has_no_part(@TempDir Path root) {
        List<Card> cards = cards(root);
        List<Map<String, Object>> vars = new ArrayList<>(vars(cards));
        vars.set(1, Map.of("numberAs", "S{part}.{n}"));
        LayoutException e = assertThrows(LayoutException.class,
                () -> new LayoutEngine().cardNumbers(root, List.of(), cards, vars, Map.of()));
        assertTrue(e.getMessage().contains("names a {part}"), e.getMessage());
    }

    @Test
    void a_link_naming_the_wrong_scenario_fails_and_a_word_that_ends_in_it_doesnt() {
        Map<String, CardNumber> numbers = Map.of("extended-sbom", new CardNumber(0, 3, "S{n}"));
        String wrong = "<a href=\"card:extended-sbom\">S2</a>";
        String word = "<a href=\"card:extended-sbom\">the PS2 build</a>";
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of(), null, 0, wrong + word, List.of(),
                Map.of(), Map.of(), ContentNodes.of(wrong + word));
        Card intro = new Card("intro", Path.of("intro.md"), new Frontmatter(Map.of()), "Intro", List.of(b));
        var bad = NumberCheck.findMismatches(List.of(intro), numbers);
        assertEquals(1, bad.size(), bad.toString());
        assertEquals("S2", bad.get(0).claimed());
    }
}
