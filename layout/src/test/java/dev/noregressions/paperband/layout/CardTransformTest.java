package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
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
 * The transforms given a card: the selector sees its blocks as well as their
 * nodes, and what comes back is a card a layout writes like any other.
 */
class CardTransformTest {

    private static Block block(Block.Kind kind, String heading, Set<String> classes, String html,
                               List<Block> children, Map<String, String> directives) {
        return new Block(kind, null, classes, heading, heading == null ? 0 : 2, html, children, Map.of(),
                directives, ContentNodes.of(html));
    }

    /** An exercise with a solution paragraph, a solution block holding a nested block, and a stepped block. */
    private static Card card() {
        Block task = block(Block.Kind.HEADING_SECTION, "Exercise", Set.of("exercise"),
                "<p>Write a loop.</p><p class=\"solution\">Use a for loop.</p>", List.of(), Map.of());
        Block why = block(Block.Kind.HEADING_SECTION, "Why it works", Set.of("why-it-works"),
                "<p>Because it counts.</p>", List.of(), Map.of());
        Block solution = block(Block.Kind.HEADING_SECTION, "Solution", Set.of("solution"),
                "<pre><code>for (;;) {}</code></pre>", List.of(why), Map.of());
        Block step = block(Block.Kind.HEADING_SECTION, "Step 1 Run it", Set.of("run-it"),
                "<p>Run it.</p>", List.of(), Map.of("step", "1"));
        return new Card("loops", Path.of("loops.md"), new Frontmatter(Map.of()), "Loops",
                List.of(task, solution, step));
    }

    private static String render(Path book, String body) throws IOException {
        Path layouts = Files.createDirectories(book.resolve("layouts"));
        Files.writeString(layouts.resolve("t.html"), body);
        BookConfig config = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new LayoutEngine(book).render(card(), new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4"),
                "t");
    }

    /** {@code pipe} applied to the card, then its blocks written the default way, then the card's own. */
    private static String[] piped(Path book, String pipe) throws IOException {
        String html = render(book, "{% set c = card | " + pipe + " %}"
                + "{% for block in c.blocks %}{% include \"_block-section\" with {\"block\": block} %}{% endfor %}"
                + "|||{% for block in card.blocks %}{% include \"_block-section\" with {\"block\": block} %}{% endfor %}");
        return html.split("\\|\\|\\|");
    }

    @Test
    void replace_takes_blocks_and_nodes_alike_and_the_card_is_unchanged(@TempDir Path book) throws IOException {
        String[] out = piped(book, "replace('.solution', '{.answer-space lines=4}')");
        String changed = out[0];
        assertTrue(changed.contains("<p>Write a loop.</p><div class=\"answer-space\" lines=\"4\"></div>"), changed);
        assertFalse(changed.contains("Use a for loop."), changed);
        assertFalse(changed.contains("<h2>Solution</h2>"), changed);
        assertFalse(changed.contains("Because it counts."), "a block nested in a match goes with it: " + changed);
        assertTrue(changed.contains("<section class=\"block answer-space\" id=\"solution\" lines=\"4\">"),
                "the block keeps its anchor: " + changed);
        assertTrue(out[1].contains("Use a for loop.") && out[1].contains("Because it counts."), out[1]);
    }

    @Test
    void block_and_node_selectors_reach_only_their_own_kind(@TempDir Path book) throws IOException {
        String changed = piped(book, "drop('block.solution')")[0];
        assertFalse(changed.contains("<h2>Solution</h2>"), changed);
        assertTrue(changed.contains("Use a for loop."), "the paragraph isn't a block: " + changed);
        changed = piped(book, "drop('p.solution')")[0];
        assertTrue(changed.contains("<h2>Solution</h2>"), changed);
        assertFalse(changed.contains("Use a for loop."), changed);
    }

    @Test
    void blocks_go_in_beside_inside_and_around_a_block(@TempDir Path book) throws IOException {
        String changed = piped(book, "insertAfter('block.exercise', '{.space}')"
                + " | append('block.solution', '{.last}') | wrap('block.run-it', '{.box}')")[0];
        assertTrue(changed.matches("(?s).*Use a for loop\\.</p>\\s*</section>\\s*<section class=\"block space\">.*"),
                changed);
        assertTrue(changed.matches("(?s).*Because it counts\\..*<section class=\"block last\">.*"), changed);
        assertTrue(changed.matches("(?s).*<section class=\"block box\">\\s*<section class=\"block run-it\".*"), changed);
    }

    @Test
    void set_and_add_class_reach_the_section(@TempDir Path book) throws IOException {
        String changed = piped(book, "set('block.exercise', '{data-level=easy}') | addClass('block.exercise', 'lead')")[0];
        assertTrue(changed.contains("<section class=\"block exercise lead\" id=\"exercise\" data-level=\"easy\">"),
                changed);
    }

    @Test
    void keep_leaves_the_matching_blocks_and_their_insides(@TempDir Path book) throws IOException {
        String changed = piped(book, "keep('block.solution')")[0];
        assertTrue(changed.contains("<h2>Solution</h2>"), changed);
        assertTrue(changed.contains("Because it counts."), changed);
        assertFalse(changed.contains("Exercise"), changed);
        assertFalse(changed.contains("Run it"), changed);
    }

    @Test
    void steps_are_counted_again(@TempDir Path book) throws IOException {
        String html = render(book, "[{{ card.steps | length }}][{{ (card | drop('block.run-it')).steps | length }}]");
        assertTrue(html.contains("[1][0]"), html);
    }

    @Test
    void the_slots_of_a_changed_card_are_checked(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> render(book,
                "{% set c = card | drop('block.run-it') %}{% for b in c.slots.take('exercise') %}{% endfor %}"));
        assertTrue(messages(e).contains("unplaced: \"Solution\""), messages(e));
        assertFalse(messages(e).contains("Run it"), "a dropped block isn't missing: " + messages(e));
    }

    @Test
    void the_slots_of_a_changed_card_place_its_blocks(@TempDir Path book) throws IOException {
        String html = render(book, "{% set c = card | replace('block.solution', '{.answer-space}') %}"
                + "{% for b in c.slots.take('answer-space') %}[{{ b.classAttr }}]{% endfor %}"
                + "{% for b in c.slots.rest() %}{% endfor %}");
        assertTrue(html.contains("[answer-space]"), html);
    }

    @Test
    void cards_keep_and_drop_cards(@TempDir Path book) throws IOException {
        String html = render(book, "[{{ [card] | keep('card:has(.solution)') | length }}]"
                + "[{{ [card] | drop('card#loops') | length }}][{{ [card] | keep('card:has(.nothing)') | length }}]");
        assertTrue(html.contains("[1][0][0]"), html);
    }

    @Test
    void only_drop_and_keep_change_a_card(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> render(book, "{{ card | replace('card', '{.x}') }}"));
        assertTrue(messages(e).contains("matches a card"), messages(e));
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getMessage()).append('\n');
        return sb.toString();
    }
}
