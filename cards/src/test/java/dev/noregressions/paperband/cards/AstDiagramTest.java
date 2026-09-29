package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ast:} in the frontmatter: the card's tree drawn as a last block. */
class AstDiagramTest {

    private static Card card(String ast, String body) {
        String fm = ast == null ? "---\ntitle: T\n---\n" : "---\ntitle: T\nast: " + ast + "\n---\n";
        return new CardLoader().parse(Path.of("t.md"), fm + body);
    }

    /** The PlantUML source in the card's AST block. */
    private static String source(Card card) {
        Block last = card.blocks().get(card.blocks().size() - 1);
        assertEquals("AST", last.heading());
        assertEquals(Set.of("paperband-ast"), last.classes());
        return Jsoup.parse(last.html()).select("pre > code.language-plantuml").first().wholeText();
    }

    @Test
    void no_ast_key_adds_nothing() {
        List<Block> bs = card(null, "## A\n\nx\n").blocks();
        assertEquals(List.of("A"), bs.stream().map(Block::heading).toList());
    }

    @Test
    void ast_true_draws_the_block_tree_after_paperbands_passes() {
        String puml = source(card("true", "## {!step} Install {.x}\n\nRun it.\n\n### Inner\n\n- one\n"));
        assertTrue(puml.startsWith("@startmindmap\n* Document\n"), puml);
        assertTrue(puml.contains("\n** Section h2 .x !step=1\n"), puml);
        assertTrue(puml.contains("\n*** Heading h2  ~\"Step 1 Install~\"\n"), puml);
        assertTrue(puml.contains("\n*** Paragraph  ~\"Run it.~\"\n"), puml);
        assertTrue(puml.contains("\n*** Section h3\n"), puml);
        assertTrue(puml.contains("\n**** BulletList\n"), puml);
        assertFalse(puml.contains("Text"), "block detail leaves out inline nodes: " + puml);
        assertTrue(puml.endsWith("@endmindmap\n"), puml);
    }

    @Test
    void the_ast_block_is_not_part_of_the_tree_it_draws() {
        assertFalse(source(card("true", "## A\n")).contains("AST"));
    }

    @Test
    void ast_inline_includes_text_runs_code_and_links() {
        String puml = source(card("inline", "## A\n\nSee [docs](http://x.y) and `code`.\n"));
        assertTrue(puml.contains("Text  ~\"See~\""), puml);
        assertTrue(puml.contains("Link http:~/~/x.y"), puml);
        assertTrue(puml.contains("Code  ~\"code~\""), puml);
        assertFalse(puml.contains("Marker"), puml);
    }

    @Test
    void a_marker_shows_what_it_was_replaced_with() {
        assertTrue(source(card("inline", "## {!step} A\n")).contains("Marker {!step} = ~\"Step 1~\""));
    }

    @Test
    void labels_are_escaped_so_card_text_is_never_read_as_plantuml_markup() {
        // Code spans keep the characters literal in the card, so the label
        // sees exactly the markup PlantUML would otherwise interpret.
        String puml = source(card("true", "## A\n\n`**bold** //x// <b> a_b ~/bin #tag [x]`\n"));
        assertTrue(puml.contains("~*~*bold~*~* ~/~/x~/~/ ~<b> a~_b <U+007E>~/bin ~#tag ~[x~]"), puml);
    }

    @Test
    void an_unknown_value_fails_and_lists_the_options() {
        CardParseException e = assertThrows(CardParseException.class, () -> card("everything", "## A\n"));
        assertTrue(e.getMessage().contains("ast: true"), e.getMessage());
        assertTrue(e.getMessage().contains("ast: inline"), e.getMessage());
    }

    @Test
    void ast_false_adds_nothing() {
        assertEquals(1, card("false", "## A\n").blocks().size());
    }

    @Test
    void an_html_card_has_no_tree_to_draw() {
        CardParseException e = assertThrows(CardParseException.class, () -> new CardLoader().parse(
                Path.of("t.html"), "<html><head><meta name=\"ast\" content=\"true\"></head><body><h1>T</h1></body></html>"));
        assertTrue(e.getMessage().contains("markdown card"), e.getMessage());
    }
}
