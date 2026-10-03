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
 * A view's {@code transform.html}: run over each card's model before anything
 * writes it, so every template the view uses sees the changed card.
 */
class ViewTransformTest {

    private static Block block(String heading, Set<String> classes, String html, Map<String, String> attributes) {
        return new Block(Block.Kind.HEADING_SECTION, null, classes, heading, 2, html, List.of(), attributes,
                Map.of(), ContentNodes.of(html));
    }

    private static Card card() {
        return new Card("notes", Path.of("notes.md"), new Frontmatter(Map.of()), "Notes", List.of(
                block("Keep", Set.of("keep"), "<p>Kept prose.</p><p class=\"aside\">An aside.</p>", Map.of()),
                block("Aside", Set.of("aside"), "<p>Aside prose.</p>", Map.of("lines", "8"))));
    }

    private static RenderContext ctx() {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
    }

    /** An engine through view {@code edit}: {@code keep} and {@code transform} are its two templates. */
    private static LayoutEngine view(Path book, String keep, String transform) throws IOException {
        Path dir = Files.createDirectories(book.resolve("layouts/edit"));
        Files.writeString(dir.resolve("keep.html"), keep);
        if (transform != null) Files.writeString(dir.resolve("transform.html"), transform);
        LayoutEngine engine = new LayoutEngine(book);
        engine.setView("edit");
        return engine;
    }

    @Test
    void the_card_body_writes_the_changed_card(@TempDir Path book) throws IOException {
        String html = view(book, "true", "{{ result(card | drop('.aside')) }}").render(card(), ctx());
        assertTrue(html.contains("Kept prose."), html);
        assertFalse(html.contains("An aside."), html);
        assertFalse(html.contains("Aside prose."), html);
    }

    @Test
    void keep_sees_the_changed_card(@TempDir Path book) throws IOException {
        LayoutEngine engine = view(book, "{{ card.blocks | length == 1 }}", "{{ result(card | drop('block.aside')) }}");
        assertEquals(List.of(true), engine.keeps(List.of(card()), List.of(ctx()), "print"));
    }

    @Test
    void the_transform_sees_output_and_target(@TempDir Path book) throws IOException {
        LayoutEngine engine = view(book, "{{ card.blocks | length == 1 }}",
                "{% if output == 'print' and target == 'pdf' %}{{ result(card | drop('block.aside')) }}"
                        + "{% else %}{{ result(card) }}{% endif %}");
        assertEquals(List.of(true), engine.keeps(List.of(card()), List.of(ctx()), "print"));
        assertEquals(List.of(false), engine.keeps(List.of(card()), List.of(ctx()), "site"));
    }

    @Test
    void replace_keeps_what_the_match_carried_over_the_spec(@TempDir Path book) throws IOException {
        String html = view(book, "true", "{{ result(card | replace('block.aside', '{.space lines=4 data-x=y}')) }}")
                .render(card(), ctx());
        assertTrue(html.contains("<section class=\"block space\" id=\"aside\" lines=\"8\" data-x=\"y\">"), html);
    }

    @Test
    void the_sites_rail_sees_the_changed_card_and_leaves_out_a_block_with_no_heading(@TempDir Path book)
            throws IOException {
        LayoutEngine engine = view(book, "true", "{{ result(card | replace('block.aside', '{.space}')) }}");
        // Enough headings for a rail once the aside is gone: it takes three.
        Card card = new Card("notes", Path.of("notes.md"), new Frontmatter(Map.of()), "Notes", List.of(
                block("Keep", Set.of("keep"), "<p>Kept.</p>", Map.of()),
                block("Aside", Set.of("aside"), "<p>Aside.</p>", Map.of()),
                block("More", Set.of("more"), "<p>More.</p>", Map.of()),
                block("Last", Set.of("last"), "<p>Last.</p>", Map.of())));
        String page = engine.renderSite(List.of(card), List.of(ctx()), ctx()).get("cards/notes.html");
        int rail = page.indexOf("class=\"page-rail\"");
        assertTrue(rail >= 0, "the site page has a rail: " + page);
        String nav = page.substring(rail, page.indexOf("</nav>", rail));
        assertTrue(nav.contains("<a href=\"#keep\">Keep</a>"), nav);
        assertFalse(nav.contains("#aside"), "the space keeps the anchor but has no heading to list: " + nav);
    }

    @Test
    void statements_run_in_order_each_seeing_what_the_last_did(@TempDir Path book) throws IOException {
        String html = view(book, "true", """
                {# leave the asides out, then mark what's left #}
                drop .aside
                insert {.note} after block.keep
                add .wide to .note
                """).render(card(), ctx());
        assertFalse(html.contains("An aside.") || html.contains("Aside prose."), html);
        assertTrue(html.contains("<section class=\"block note wide\">"), html);
    }

    @Test
    void statements_can_be_chosen_with_pebble(@TempDir Path book) throws IOException {
        LayoutEngine engine = view(book, "{{ card.blocks | length == 1 }}",
                "{% if output == 'print' %}drop block.aside{% else %}drop p.aside{% endif %}");
        assertEquals(List.of(true), engine.keeps(List.of(card()), List.of(ctx()), "print"));
        assertEquals(List.of(false), engine.keeps(List.of(card()), List.of(ctx()), "site"));
    }

    @Test
    void a_statement_that_fails_is_named(@TempDir Path book) throws IOException {
        for (String[] c : new String[][] {
                {"drop .aside\nreplace .keep", "has no 'with'"},
                {"replace .keep with {style=x}", "'replace .keep with {style=x}'"},
                {"drop card", "left out card notes"},
                {"{{ result(card) }}\ndrop .aside", "Use one or the other"},
                {"{# nothing #}", "did neither"}}) {
            LayoutEngine engine = view(book, "true", c[0]);
            LayoutException e = assertThrows(LayoutException.class, () -> engine.render(card(), ctx()), c[0]);
            assertTrue(e.getMessage().contains(c[1]) && e.getMessage().contains("edit/transform.html"),
                    e.getMessage());
        }
    }

    @Test
    void a_view_without_a_transform_writes_the_card_as_it_is(@TempDir Path book) throws IOException {
        String html = view(book, "true", null).render(card(), ctx());
        assertTrue(html.contains("An aside."), html);
    }

    @Test
    void a_transform_has_to_hand_back_a_card(@TempDir Path book) throws IOException {
        for (String transform : List.of("{{ card.id }}", "{{ result(card.blocks) }}", "{{ result([card] | drop('card')) }}")) {
            LayoutEngine engine = view(book, "true", transform);
            LayoutException e = assertThrows(LayoutException.class, () -> engine.render(card(), ctx()), transform);
            assertTrue(e.getMessage().contains("edit/transform.html") && e.getMessage().contains("notes"),
                    e.getMessage());
        }
    }

    @Test
    void result_works_only_in_a_transform(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/t.html"), "{{ result(card) }}");
        Exception e = assertThrows(Exception.class, () -> new LayoutEngine(book).render(card(), ctx(), "t"));
        assertTrue(messages(e).contains("works only there"), messages(e));
    }

    @Test
    void the_slots_of_a_transformed_card_are_checked(@TempDir Path book) throws IOException {
        LayoutEngine engine = view(book, "true", "{{ result(card | drop('p.aside')) }}");
        Files.writeString(book.resolve("layouts/edit/_card-body.html"),
                "{% for b in card.slots.take('keep') %}{{ b.html | raw }}{% endfor %}");
        Exception e = assertThrows(Exception.class, () -> engine.render(card(), ctx()));
        assertTrue(messages(e).contains("unplaced: \"Aside\""), messages(e));
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getMessage()).append('\n');
        return sb.toString();
    }
}
