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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-index term selection: distinctive-to-this-card wins, common-to-
 * the-book loses, code identifiers count verbatim, and the whole thing is
 * deterministic — same book, same index.
 */
class IndexTermExtractorTest {

    @Nested
    @DisplayName("What gets picked")
    class Picks {

        @Test
        void should_pick_a_term_distinctive_of_one_card() {
            Map<String, List<String>> terms = extract(
                    card("a", "Rendering", "Playwright drives Chromium. Playwright downloads "
                            + "Chromium once. Playwright then renders."),
                    card("b", "Authoring", "Cards are markdown files with frontmatter."),
                    card("c", "Config", "Configuration cascades through folders."));

            assertTrue(terms.get("a").stream().anyMatch(t -> t.equalsIgnoreCase("playwright")),
                    "a's terms: " + terms.get("a"));
            assertFalse(terms.get("b").stream().anyMatch(t -> t.equalsIgnoreCase("playwright")));
        }

        @Test
        void should_drop_a_term_the_whole_book_uses() {
            // "paperband" in every card: it's the book's subject, not an
            // index entry — locating it everywhere locates it nowhere.
            Map<String, List<String>> terms = extract(
                    card("a", "One", "Paperband renders books. Paperband is a toolchain."),
                    card("b", "Two", "Paperband builds sites. Paperband walks folders."),
                    card("c", "Three", "Paperband stamps watermarks everywhere."),
                    card("d", "Four", "Paperband reads yaml configuration files."));

            for (var e : terms.entrySet()) {
                assertFalse(e.getValue().stream().anyMatch(t -> t.equalsIgnoreCase("paperband")),
                        e.getKey() + " picked it anyway: " + e.getValue());
            }
        }

        @Test
        void should_keep_code_identifiers_verbatim() {
            Map<String, List<String>> terms = extract(
                    card("a", "Config", "Configuration lives in <code>paperband.yaml</code> "
                            + "and <code>paperband.yaml</code> cascades."),
                    card("b", "Other", "Something else entirely, about themes."),
                    card("c", "More", "And a third card for corpus size."));

            assertTrue(terms.get("a").contains("paperband.yaml"),
                    "verbatim identifier expected: " + terms.get("a"));
        }

        @Test
        void should_weight_headings_over_body_mentions() {
            Map<String, List<String>> terms = extract(
                    card("a", "Watermarks", "Stamp text on pages. The stamp repeats."),
                    card("b", "Other", "Unrelated content about dividers."),
                    card("c", "More", "And a third card again."));

            List<String> a = terms.get("a");
            assertFalse(a.isEmpty());
            assertTrue(a.get(0).equalsIgnoreCase("watermarks"),
                    "the heading term should outrank body terms: " + a);
        }
    }

    @Nested
    @DisplayName("Guardrails")
    class Guardrails {

        @Test
        void should_cap_terms_per_card() {
            Map<String, List<String>> terms = extract(
                    card("a", "Everything", "alpha bravo charlie delta echo foxtrot golf hotel "
                            + "india juliet kilo lima mike november oscar papa quebec romeo"),
                    card("b", "Other", "unrelated words here entirely."),
                    card("c", "More", "and again different prose."));

            assertTrue(terms.get("a").size() <= IndexTermExtractor.MAX_PER_CARD,
                    "picked " + terms.get("a").size() + ": " + terms.get("a"));
        }

        @Test
        void should_respect_the_stop_list() {
            Map<String, List<String>> terms = IndexTermExtractor.extract(List.of(
                    card("a", "Rendering", "Playwright drives Chromium. Playwright renders."),
                    card("b", "Other", "Cards are markdown files."),
                    card("c", "More", "Configuration cascades.")),
                    Set.of("Playwright"));

            assertFalse(terms.get("a").stream().anyMatch(t -> t.equalsIgnoreCase("playwright")),
                    "vetoed term still picked: " + terms.get("a"));
        }

        @Test
        void should_skip_short_and_numeric_terms() {
            Map<String, List<String>> terms = extract(
                    card("a", "Sizes", "A4 A4 A4 6x9 6x9 6x9 xy xy xy"),
                    card("b", "Other", "unrelated prose."),
                    card("c", "More", "different again."));

            for (String t : terms.get("a")) {
                assertTrue(t.length() >= IndexTermExtractor.MIN_LENGTH, t);
                assertFalse(t.matches("[0-9.,x\\-]+"), t);
            }
        }

        @Test
        void should_be_deterministic() {
            Card[] book = {
                    card("a", "Rendering", "Playwright drives Chromium and snapshots pages."),
                    card("b", "Authoring", "Cards, blocks and frontmatter fields."),
                    card("c", "Config", "Cascading yaml configuration folders.")};

            assertEquals(extract(book), extract(book), "same book, same index");
        }
    }

    // ---- helpers ----

    private static Map<String, List<String>> extract(Card... cards) {
        return IndexTermExtractor.extract(List.of(cards), Set.of());
    }

    private static Card card(String id, String heading, String bodyHtml) {
        Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), heading, 2,
                "<p>" + bodyHtml + "</p>", List.of());
        return new Card(id, Path.of(id + ".md"), new Frontmatter(Map.of()), heading,
                List.of(block));
    }
}
