package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Node;

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
 *
 * <p>A card numbered by its section's format ({@code numbering: "Scenario {n}"})
 * is checked against that format instead: "[Scenario 2](card:x)" fails when x
 * is Scenario 3, and "[the login scenario](card:x)" is prose.
 */
public final class NumberCheck {

    /** A dotted chapter number sitting in link text. */
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\.(\\d+)\\b");

    private NumberCheck() {
    }

    /**
     * A section's format as a pattern for link text: its words as written, in
     * any case, with {@code {n}} and {@code {part}} as whole numbers.
     * {@code "Scenario {n}"} finds "Scenario 2" and "scenario 12".
     */
    static Pattern formatPattern(String format) {
        StringBuilder re = new StringBuilder();
        Matcher m = Pattern.compile("\\{(n|part)}").matcher(format);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) re.append(Pattern.quote(format.substring(last, m.start())));
            re.append("\\d+");
            last = m.end();
        }
        if (last < format.length()) re.append(Pattern.quote(format.substring(last)));
        // Bounded so "S3" isn't found in "PS3", nor "Scenario 3" in "Scenario 30".
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + re + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
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
            scanNodes(card, b.nodes(), numbers, out);
            scanBlocks(card, b.children(), numbers, out);
        }
    }

    /** Every {@code card:} link among {@code nodes}, at any depth, whose label states a stale number. */
    private static void scanNodes(Card card, List<Node> nodes,
            Map<String, CardNumber> numbers, List<Mismatch> out) {
        for (Node n : nodes) {
            String href = n.attributes().get("href");
            if ("link".equals(n.type()) && href != null && href.startsWith(CardLinks.SCHEME)) {
                check(card, targetOf(href), n.text(), numbers, out);
            }
            scanNodes(card, n.children(), numbers, out);
        }
    }

    /** {@code card:id#fragment} to {@code id}. */
    private static String targetOf(String href) {
        String target = href.substring(CardLinks.SCHEME.length());
        int hash = target.indexOf('#');
        return hash < 0 ? target : target.substring(0, hash);
    }

    private static void check(Card card, String targetId, String label,
            Map<String, CardNumber> numbers, List<Mismatch> out) {
        CardNumber actual = numbers.get(targetId);
        // Unnumbered target, or a link into a card this build left out:
        // CardLinks owns the "does it exist" question, not this check.
        if (actual == null) return;
        String claimed;
        if (actual.format() != null) {
            // "Scenario 2" in the label, for a card numbered "Scenario {n}".
            Matcher f = formatPattern(actual.format()).matcher(label);
            if (!f.find()) return;                      // doesn't say which; leave it be
            claimed = f.group();
            if (claimed.equalsIgnoreCase(actual.label())) return;
        } else {
            Matcher n = NUMBER.matcher(label);
            if (!n.find()) return;                      // prose label; leave it be
            claimed = n.group(1) + "." + n.group(2);
        }
        if (!claimed.equals(actual.label())) {
            out.add(new Mismatch(card.source(), card.id(), targetId,
                    claimed, actual.label(), label.trim()));
        }
    }
}
