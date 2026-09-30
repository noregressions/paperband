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
 * The bundled student view: the whole book, with every {@code {.solution}}
 * turned into space to write the answer in.
 */
class StudentViewTest {

    private static Block block(Block.Kind kind, String heading, Set<String> classes, String html,
                               List<Block> children, Map<String, String> attributes) {
        return new Block(kind, null, classes, heading, heading == null ? 0 : 2, html, children, attributes,
                Map.of(), ContentNodes.of(html));
    }

    /** A solution paragraph inside an exercise, a solution block with a nested block, and a fenced-div solution. */
    private static Card exercise() {
        Block task = block(Block.Kind.HEADING_SECTION, "Exercise", Set.of("exercise"),
                "<p>Write a loop.</p><p class=\"solution\">Use a for loop.</p>", List.of(), Map.of());
        Block why = block(Block.Kind.HEADING_SECTION, "Why it works", Set.of("why-it-works"),
                "<p>Because it counts.</p>", List.of(), Map.of());
        Block solution = block(Block.Kind.HEADING_SECTION, "Solution", Set.of("solution"),
                "<pre><code>for (int i = 0; i &lt; 3; i++) {}</code></pre>", List.of(why), Map.of("lines", "8"));
        Block hint = block(Block.Kind.FENCED_DIV, null, Set.of("solution"), "<p>Start at zero.</p>", List.of(),
                Map.of());
        return new Card("loops", Path.of("loops.md"), new Frontmatter(Map.of()), "Loops",
                List.of(task, solution, hint));
    }

    private static Card plain() {
        Block b = block(Block.Kind.HEADING_SECTION, "Notes", Set.of("notes"),
                "<p>Just prose.</p><pre><code class=\"language-command\">mvn package\n</code></pre>", List.of(), Map.of());
        return new Card("notes", Path.of("notes.md"), new Frontmatter(Map.of()), "Notes", List.of(b));
    }

    private static RenderContext ctx(Map<String, Object> vars) {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), vars, null, "pdf", "A4");
    }

    private static String render(LayoutEngine engine, String view, Card card, Map<String, Object> vars) {
        if (view != null) engine.setView(view);
        return engine.render(card, ctx(vars));
    }

    private static String student(Card card) {
        return render(new LayoutEngine(), "student", card, Map.of());
    }

    @Test
    void the_full_book_shows_the_solutions() {
        String html = render(new LayoutEngine(), null, exercise(), Map.of());
        assertTrue(html.contains("Use a for loop."), html);
        assertTrue(html.contains("Because it counts."), html);
        assertFalse(html.contains("answer-space"), html);
    }

    @Test
    void a_solution_paragraph_becomes_answer_space_and_the_rest_stays() {
        String html = student(exercise());
        assertTrue(html.contains("Write a loop."), html);
        assertFalse(html.contains("Use a for loop."), html);
        assertTrue(html.contains("<p>Write a loop.</p><div class=\"answer-space\"></div>"), html);
    }

    @Test
    void a_solution_block_goes_with_its_heading_and_nested_blocks() {
        String html = student(exercise());
        assertFalse(html.contains("i++"), html);
        assertFalse(html.contains("Because it counts."), "a block nested in a solution is part of it: " + html);
        assertFalse(html.contains("<h2>Solution</h2>"), html);
        assertTrue(html.contains("<div class=\"answer-space\" id=\"solution\" style=\"--answer-lines: 8\">"
                + "<span class=\"answer-space-label\">Your answer</span></div>"), html);
    }

    @Test
    void a_fenced_div_solution_becomes_answer_space_too() {
        String html = student(exercise());
        assertFalse(html.contains("Start at zero."), html);
        assertTrue(html.contains("<div class=\"answer-space\"><span class=\"answer-space-label\">"), html);
    }

    @Test
    void a_card_with_no_solution_prints_as_it_does_in_the_full_book() {
        assertEquals(render(new LayoutEngine(), null, plain(), Map.of()), student(plain()));
    }

    @Test
    void the_label_is_a_var() {
        String html = render(new LayoutEngine(), "student", exercise(), Map.of("answerLabel", "Work it out"));
        assertTrue(html.contains(">Work it out</span>"), html);
    }

    @Test
    void every_card_is_kept() {
        LayoutEngine engine = new LayoutEngine();
        engine.setView("student");
        assertEquals(List.of(true, true),
                engine.keeps(List.of(exercise(), plain()), List.of(ctx(Map.of()), ctx(Map.of())), "print"));
    }

    @Test
    void a_books_own_block_section_still_writes_the_other_blocks(@TempDir Path book) throws IOException {
        // default:_block-section is the template the view replaces, as the
        // chain finds it -- here the book's own, not the bundled one.
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/_block-section.html"),
                "<div class=\"own\">{{ block.heading }}{% include \"_block-content\" %}</div>");
        String html = render(new LayoutEngine(book), "student", exercise(), Map.of());
        assertTrue(html.contains("<div class=\"own\">Exercise<p>Write a loop.</p><div class=\"answer-space\"></div></div>"),
                html);
        assertFalse(html.contains("<div class=\"own\">Solution"), html);
    }

    /** The handout in the Themes card's Views section, verbatim. */
    @Test
    void the_documented_handout_leaves_out_every_aside(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts/handout"));
        Files.writeString(book.resolve("layouts/handout/keep.html"), "true");
        Files.writeString(book.resolve("layouts/handout/_block-section.html"), """
                {# layouts/handout/_block-section.html #}
                {% if not (block.classes contains "aside") %}{% include "default:_block-section" %}{% endif %}
                """);
        Block inner = block(Block.Kind.HEADING_SECTION, "Inner aside", Set.of("aside"), "<p>Nested aside.</p>",
                List.of(), Map.of());
        Block kept = block(Block.Kind.HEADING_SECTION, "Kept", Set.of("kept"), "<p>Kept text.</p>", List.of(inner),
                Map.of());
        Block aside = block(Block.Kind.HEADING_SECTION, "Aside", Set.of("aside"), "<p>Top aside.</p>", List.of(),
                Map.of());
        Card card = new Card("c", Path.of("c.md"), new Frontmatter(Map.of()), "C", List.of(kept, aside));
        String html = render(new LayoutEngine(book), "handout", card, Map.of());
        assertTrue(html.contains("Kept text."), html);
        assertFalse(html.contains("Top aside."), html);
        assertFalse(html.contains("Nested aside."), "an aside nested in a kept block goes too: " + html);
    }
}
