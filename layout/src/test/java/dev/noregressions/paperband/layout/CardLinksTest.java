package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("card: links")
class CardLinksTest {

    private static final List<Card> BOOK = List.of(
            card("alpha", "Watch Out", "How To Fix"),
            card("beta", "Watch Out"));

    private final CardLinks links = CardLinks.of(BOOK);

    @Nested
    @DisplayName("resolving")
    class Resolving {

        @Test
        void print_makes_a_reference_an_in_document_anchor() {
            assertEquals("<a href=\"#card-beta\">B</a>",
                    links.print("<a href=\"card:beta\">B</a>"));
        }

        @Test
        void the_site_spells_it_relative_to_the_page_it_is_on() {
            // The reason the form exists: cards/<id>.html is right from a
            // landing page and wrong from a card page, which sits a level down.
            String html = "<a href=\"card:beta\">B</a>";

            assertEquals("<a href=\"cards/beta.html\">B</a>", links.site(html, "index.html"));
            assertEquals("<a href=\"cards/beta.html\">B</a>", links.site(html, "guides.html"));
            assertEquals("<a href=\"../cards/beta.html\">B</a>",
                    links.site(html, "cards/alpha.html"));
        }

        @Test
        void a_fragment_sharpens_the_site_link() {
            assertEquals("<a href=\"../cards/beta.html#watch-out\">W</a>",
                    links.site("<a href=\"card:beta#watch-out\">W</a>", "cards/alpha.html"));
        }

        @Test
        void print_drops_the_fragment_and_lands_on_the_card() {
            // Block anchors are slugged per heading with no card prefix, so in
            // one print document many cards emit id="watch-out". Landing on the
            // first is a wrong answer dressed as a right one; the card's own
            // destination is unambiguous.
            assertEquals("<a href=\"#card-beta\">W</a>",
                    links.print("<a href=\"card:beta#watch-out\">W</a>"));
        }

        @Test
        void several_references_on_one_page_all_resolve() {
            String out = links.print(
                    "<p><a href=\"card:alpha\">A</a> and <a href=\"card:beta\">B</a></p>");

            assertEquals("<p><a href=\"#card-alpha\">A</a> and <a href=\"#card-beta\">B</a></p>", out);
        }

        @Test
        void single_quoted_hrefs_resolve_too() {
            // Markdown emits double quotes; a hand-written template may not.
            assertEquals("<a href='#card-beta'>B</a>",
                    links.print("<a href='card:beta'>B</a>"));
        }

        @Test
        void html_with_no_reference_is_returned_untouched() {
            String html = "<p>Nothing to resolve here.</p>";

            assertSame(html, links.print(html));
        }

        @Test
        void prose_that_merely_contains_the_scheme_is_not_rewritten() {
            // "discard:" ends in "card:", which defeats the fast path — the
            // match still has to be an href for anything to happen.
            String html = "<p>A discard: pile, and a placard: neither is a link.</p>";

            assertEquals(html, links.print(html));
        }

        @Test
        void other_links_are_left_alone() {
            String html = "<a href=\"https://example.com\">x</a><a href=\"#card-beta\">y</a>";

            assertEquals(html, links.print(html));
        }
    }

    @Nested
    @DisplayName("checking")
    class Checking {

        @Test
        void an_unknown_id_fails_the_build_and_suggests_the_near_miss() {
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.print("<a href=\"card:beeta\">B</a>"));

            assertTrue(e.getMessage().contains("no card has that id"), e.getMessage());
            assertTrue(e.getMessage().contains("Did you mean 'beta'?"), e.getMessage());
        }

        @Test
        void an_unknown_id_that_resembles_nothing_suggests_nothing() {
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.print("<a href=\"card:completely-different\">B</a>"));

            assertFalse(e.getMessage().contains("Did you mean"), e.getMessage());
        }

        @Test
        void an_unknown_fragment_fails_even_though_print_would_not_use_it() {
            // Checked in both outputs so a typo surfaces whichever you rendered.
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.print("<a href=\"card:beta#watchout\">W</a>"));

            assertTrue(e.getMessage().contains("no block anchored 'watchout'"), e.getMessage());
            assertTrue(e.getMessage().contains("Did you mean 'watch-out'?"), e.getMessage());
        }

        @Test
        void a_card_with_no_anchored_blocks_says_so() {
            CardLinks bare = CardLinks.of(List.of(card("plain")));

            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> bare.print("<a href=\"card:plain#nope\">x</a>"));

            assertTrue(e.getMessage().contains("no anchored blocks"), e.getMessage());
        }

        @Test
        void every_broken_reference_is_reported_at_once() {
            // One build, one list — not one failure per run.
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.print("<a href=\"card:nope1\">a</a><a href=\"card:nope2\">b</a>"));

            assertTrue(e.getMessage().startsWith("2 card links point at nothing"), e.getMessage());
            assertTrue(e.getMessage().contains("nope1"));
            assertTrue(e.getMessage().contains("nope2"));
        }

        @Test
        void the_message_names_the_file_the_reference_is_written_in() {
            // Provenance is recovered by finding the reference in a card's own
            // html, so the card has to be the one that wrote it — as it is once
            // the page is assembled from these blocks.
            String broken = "<a href=\"card:nope\">x</a>";
            Card writer = new Card("gamma", Path.of("gamma.md"), new Frontmatter(Map.of()), "G",
                    List.of(new Block(Block.Kind.HEADING_SECTION, null, Set.of(), null, 0,
                            "<p>See " + broken + "</p>", List.of())));

            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> CardLinks.of(List.of(writer)).print(broken));

            assertTrue(e.getMessage().contains("in gamma.md"), e.getMessage());
        }

        @Test
        void a_reference_from_a_template_reports_without_a_file() {
            // Section bodies and templates aren't in the card set; the failure
            // still has to be legible.
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.print("<a href=\"card:nope\">x</a>"));

            assertTrue(e.getMessage().contains("card:nope"), e.getMessage());
            assertFalse(e.getMessage().contains(" in "), e.getMessage());
        }

        @Test
        void the_site_message_names_the_page_being_built() {
            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> links.site("<a href=\"card:nope\">x</a>", "cards/alpha.html"));

            assertTrue(e.getMessage().contains("building cards/alpha.html"), e.getMessage());
        }

        @Test
        void a_card_the_build_left_out_keeps_its_words_and_loses_its_link() {
            // A `select:` build is a subset by construction, so a reference
            // reaching outside it is expected. The prose survives; the anchor
            // does not. Failing instead would mean a sampler could only ever
            // carry chapters that reference nothing.
            CardLinks selected = CardLinks.of(List.of(BOOK.get(0)), Set.of("beta"));

            String html = selected.print("see <a href=\"card:beta\">Chapter 2.1</a> for more");

            assertEquals("see Chapter 2.1 for more", html);
        }

        @Test
        void a_typo_still_fails_even_when_the_build_leaves_cards_out() {
            CardLinks selected = CardLinks.of(List.of(BOOK.get(0)), Set.of("beta"));

            CardLinkException e = assertThrows(CardLinkException.class,
                    () -> selected.print("<a href=\"card:nope\">x</a>"));

            assertTrue(e.getMessage().contains("no card has that id"), e.getMessage());
        }

        @Test
        void a_single_card_preview_resolves_without_checking() {
            // paperband:build on one card is a preview of that card; failing it
            // for mentioning its neighbours would make the preview useless.
            CardLinks alone = CardLinks.of(List.of(BOOK.get(0)));

            assertEquals("<a href=\"#card-beta\">B</a>",
                    alone.preview("<a href=\"card:beta\">B</a>"));
        }
    }

    /** A card whose blocks carry the given headings, so they get slugged anchors. */
    private static Card card(String id, String... headings) {
        List<Block> blocks = new java.util.ArrayList<>();
        blocks.add(new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0,
                "<p>Body of " + id + "</p>", List.of()));
        for (String heading : headings) {
            blocks.add(new Block(Block.Kind.HEADING_SECTION, null, Set.of(), heading, 2,
                    "<p>x</p>", List.of()));
        }
        return new Card(id, Path.of(id + ".md"), new Frontmatter(Map.of()), id, blocks);
    }
}
