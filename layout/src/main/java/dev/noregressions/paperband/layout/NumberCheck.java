package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches a cross-reference whose hand-written chapter number disagrees with
 * the number the book actually computed.
 *
 * <p>Paperband already makes a broken cross-reference <em>target</em>
 * impossible: {@link CardLinks} fails the build on a {@code card:} id that
 * resolves to nothing. A broken cross-reference <em>number</em> is the same
 * class of bug and has until now been invisible, because the number lives in
 * the link's prose:
 *
 * <pre>
 * [Chapter 3.14](card:unsafe-memory-access)
 * </pre>
 *
 * <p>The id is checked; "3.14" is not. A book that inserts one chapter can turn
 * an arbitrary number of those labels stale with nothing to say so — the JDK
 * migration guide this was written for had 214 such labels, of which a single
 * reordering would have falsified 58.
 *
 * <p>So: if a label states a number and the target's computed number differs,
 * that is a build error. The fix is to delete the number from the label and let
 * it be rendered, which is the point of deriving numbers at all.
 *
 * <p>Labels that state no number are left entirely alone — prose must stay free
 * to name a chapter in its own words.
 */
public final class NumberCheck {

    /**
     * An anchor with a {@code card:} href, capturing the id and the label text.
     * Only the un-rewritten scheme is matched, so this must run before
     * {@link CardLinks} resolves hrefs.
     */
    private static final Pattern LINK = Pattern.compile(
            "<a\\s[^>]*href\\s*=\\s*([\"'])card:([^\"'#\\s]*)(?:#[^\"'\\s]*)?\\1[^>]*>(.*?)</a>",
            Pattern.DOTALL);

    /** A dotted chapter number sitting in link text. */
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\.(\\d+)\\b");

    private NumberCheck() {
    }

    /** One stale label: where it is, what it claims, and what is true. */
    public record Mismatch(Path source, String cardId, String targetId, String claimed,
            String actual, String label) {

        @Override
        public String toString() {
            return source + " (card '" + cardId + "'): [" + label + "](card:" + targetId
                    + ") says " + claimed + ", but " + targetId + " is " + actual;
        }
    }

    /**
     * Verify every numbered cross-reference in the book.
     *
     * @param cards   the cards this build renders
     * @param numbers card id to computed number, from {@code
     *                LayoutEngine.cardNumbers}
     * @throws NumberCheckException when any label contradicts a computed number
     */
    public static void verify(List<Card> cards, Map<String, CardNumber> numbers) {
        List<Mismatch> bad = findMismatches(cards, numbers);
        if (!bad.isEmpty()) throw new NumberCheckException(bad);
    }

    /**
     * The mismatches, without throwing — for callers that want to report rather
     * than fail.
     *
     * @param cards   the cards this build renders
     * @param numbers card id to computed number
     * @return every stale label found, in book order; empty when all agree
     */
    public static List<Mismatch> findMismatches(
            List<Card> cards, Map<String, CardNumber> numbers) {
        if (cards == null || cards.isEmpty() || numbers == null || numbers.isEmpty()) {
            return List.of();
        }
        List<Mismatch> out = new ArrayList<>();
        for (Card card : cards) {
            scanBlocks(card, card.blocks(), numbers, out);
        }
        return out;
    }

    private static void scanBlocks(Card card, List<Block> blocks,
            Map<String, CardNumber> numbers, List<Mismatch> out) {
        if (blocks == null) return;
        for (Block b : blocks) {
            scanHtml(card, b.html(), numbers, out);
            scanBlocks(card, b.children(), numbers, out);
        }
    }

    private static void scanHtml(Card card, String html,
            Map<String, CardNumber> numbers, List<Mismatch> out) {
        if (html == null || html.isEmpty()) return;
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String targetId = m.group(2);
            String label = stripTags(m.group(3));
            CardNumber actual = numbers.get(targetId);
            // Unnumbered target, or a link into a card this build left out:
            // CardLinks owns the "does it exist" question, not this check.
            if (actual == null) continue;
            Matcher n = NUMBER.matcher(label);
            if (!n.find()) continue;                    // prose label; leave it be
            String claimed = n.group(1) + "." + n.group(2);
            if (!claimed.equals(actual.label())) {
                out.add(new Mismatch(card.source(), card.id(), targetId,
                        claimed, actual.label(), label.trim()));
            }
        }
    }

    /** Link text is markdown-rendered, so it may carry {@code <code>} and friends. */
    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", "");
    }
}
