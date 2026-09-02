package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Frontmatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-reference that writes no label gets its target's chapter number
 * rendered into it — the half of derived numbering that lets a book stop
 * writing numbers by hand.
 */
class CardLinkNumberingTest {

    private static final Map<String, CardNumber> NUMBERS = Map.of(
            "unsafe", new CardNumber(3, 14),
            "lombok", new CardNumber(3, 29));

    @Nested
    @DisplayName("Filling an empty label")
    class Filling {

        @Test
        void should_render_chapter_and_number_for_an_empty_label() {
            assertTrue(resolved("see <a href=\"card:unsafe\"></a> for detail")
                    .contains(">Chapter 3.14</a>"));
        }

        @Test
        void should_render_the_bare_number_for_the_hash_marker() {
            // "Chapters 3.14 and 3.29" — the prose already says the noun.
            String html = resolved("Chapters <a href=\"card:unsafe\">#</a> and "
                    + "<a href=\"card:lombok\">#</a>");

            assertTrue(html.contains(">3.14</a>"), html);
            assertTrue(html.contains(">3.29</a>"), html);
            assertTrue(!html.contains("Chapter 3.14"), html);
        }

        @Test
        void should_still_rewrite_the_href() {
            assertTrue(resolved("<a href=\"card:unsafe\"></a>").contains("href=\"#card-unsafe\""));
        }

        @Test
        void should_fill_a_label_that_is_only_whitespace() {
            assertTrue(resolved("<a href=\"card:unsafe\">  </a>").contains(">Chapter 3.14</a>"));
        }

        @Test
        void should_fill_an_empty_label_on_a_link_with_a_fragment() {
            assertTrue(resolved("<a href=\"card:unsafe#detail\"></a>")
                    .contains(">Chapter 3.14</a>"));
        }
    }

    @Nested
    @DisplayName("Labels left alone")
    class Untouched {

        @Test
        void should_never_touch_a_label_the_author_wrote() {
            assertTrue(resolved("<a href=\"card:unsafe\">the Unsafe chapter</a>")
                    .contains(">the Unsafe chapter</a>"));
        }

        @Test
        void should_leave_an_empty_label_empty_when_the_target_has_no_number() {
            // An appendix opted out of numbering: inventing one would be worse
            // than the visible gap.
            String html = resolved("<a href=\"card:appendix\"></a>");

            assertTrue(html.contains("></a>"), html);
            assertTrue(!html.contains("Chapter"), html);
        }

        @Test
        void should_do_nothing_when_the_book_has_no_numbers() {
            String html = CardLinks.of(cards()).print("<a href=\"card:unsafe\"></a>");

            assertTrue(html.contains("></a>"), html);
        }
    }

    private static String resolved(String html) {
        return CardLinks.of(cards()).withNumbers(NUMBERS).print(html);
    }

    private static List<Card> cards() {
        // "unsafe" carries an anchored block so the fragment case has something
        // real to point at — CardLinks validates fragments, not just ids.
        return List.of(card("unsafe", "detail"), card("lombok", null),
                card("appendix", null));
    }

    private static Card card(String id, String anchor) {
        Block b = new Block(Block.Kind.HEADING_SECTION, anchor, Set.of(), null, 0, "",
                List.of());
        return new Card(id, Path.of("content/" + id + ".md"), Frontmatter.empty(), id,
                List.of(b));
    }
}
