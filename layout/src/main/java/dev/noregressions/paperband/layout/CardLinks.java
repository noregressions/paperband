package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code card:} links — one cross-reference form that spells itself
 * correctly for whichever output is being built.
 *
 * <pre>
 * See [the quickstart](card:quickstart) for the short version.
 * </pre>
 *
 * <p>An author writing a cross-reference used to have to pick a side. A card's
 * id is the PDF's {@code #card-<id>} destination <em>and</em> the site's
 * {@code cards/<id>.html} page, and prose could name only one of them: the
 * anchor is a dead link on the site, where each card is its own document, and
 * the page path is a dead file reference in the PDF. Worse, the site spelling
 * isn't even stable — {@code cards/<id>.html} is right from a landing page and
 * wrong from a card page, which sits a directory down. The engine already knew
 * the right answer in both cases (it writes its own nav links); authors simply
 * had no way to ask for it.
 *
 * <p>So they ask by id, and this resolves it:
 *
 * <table>
 *   <caption>What a reference becomes</caption>
 *   <tr><th>Written</th><th>Print</th><th>Site (from a card page)</th></tr>
 *   <tr><td>{@code card:beta}</td><td>{@code #card-beta}</td><td>{@code ../cards/beta.html}</td></tr>
 *   <tr><td>{@code card:beta#watch-out}</td><td>{@code #card-beta}</td>
 *       <td>{@code ../cards/beta.html#watch-out}</td></tr>
 * </table>
 *
 * <p><strong>Why print drops the fragment.</strong> In the PDF the whole book
 * is one document, and block anchors are slugged per heading with no card
 * prefix — eleven cards in Paperband's own guide would each emit
 * {@code id="watch-out"}. A fragment link would land on whichever came first,
 * which is a wrong answer dressed as a right one. The card's own destination is
 * unambiguous, so print stops there. The fragment is still <em>checked</em>, so
 * a typo in it fails the build whichever output you happened to render.
 *
 * <p>Ordinary markdown, deliberately: a previewer, an editor and a link checker
 * all see a link. The alternative — a Pebble helper in the prose — would have
 * put template syntax in running text and walked into the newline-eating
 * behaviour that {@code {% %}} tags have in markdown.
 */
public final class CardLinks {

    /** The scheme an author writes. */
    public static final String SCHEME = "card:";

    /**
     * {@code href="card:<id>"}, optionally {@code #fragment}, in either quote
     * style — markdown emits double, a hand-written template may not.
     */
    private static final Pattern LINK =
            Pattern.compile("(href\\s*=\\s*)([\"'])card:([^\"'#\\s]*)(?:#([^\"'\\s]*))?\\2");

    /** Card id to the block anchors that card offers, in book order. */
    private final Map<String, Set<String>> anchors = new LinkedHashMap<>();

    /** The cards themselves, for saying which file a broken reference is in. */
    private final List<Card> cards;

    /** Ids the book holds that this build left out — a selection, or an edition. */
    private final Set<String> excluded;

    private CardLinks(List<Card> cards, Set<String> excluded) {
        this.cards = cards == null ? List.of() : cards;
        this.excluded = excluded == null ? Set.of() : excluded;
        for (Card card : this.cards) {
            Set<String> found = new LinkedHashSet<>();
            collectAnchors(card.blocks(), found);
            anchors.put(card.id(), found);
        }
    }

    /**
     * Build a resolver over the cards in the book.
     *
     * @param cards every card the build holds — the set a reference may name
     * @return the resolver
     */
    public static CardLinks of(List<Card> cards) {
        return new CardLinks(cards, Set.of());
    }

    /**
     * Build a resolver that can also tell a typo from a deliberate omission.
     *
     * @param cards    the cards this build renders
     * @param excluded ids the book holds that a {@code select:} or an edition
     *                 left out. Naming one is still a failure — the link would
     *                 go nowhere in this edition — but it is a different
     *                 mistake from a misspelling, and saying so saves the
     *                 author hunting for a card that is sitting right there.
     * @return the resolver
     */
    public static CardLinks of(List<Card> cards, Set<String> excluded) {
        return new CardLinks(cards, excluded);
    }

    /**
     * Resolve every reference in a print document, where the book is one file.
     *
     * @param html the assembled document
     * @return the document with references resolved
     * @throws CardLinkException if any reference names a card or anchor that
     *         doesn't exist
     */
    public String print(String html) {
        return resolve(html, (id, fragment) -> "#card-" + id, true, null);
    }

    /**
     * Resolve references in a single-card render, which has no book to check
     * against.
     *
     * <p>Unvalidated on purpose: {@code paperband:build} on one card is a
     * preview of that card, and failing it because the card mentions its
     * neighbours would make the preview useless exactly when it's most wanted.
     * A book build checks the same prose a moment later.
     *
     * @param html the rendered card
     * @return the card with references resolved
     */
    public String preview(String html) {
        return resolve(html, (id, fragment) -> "#card-" + id, false, null);
    }

    /**
     * Resolve every reference on one site page, relative to where that page
     * sits.
     *
     * @param html    the rendered page
     * @param pageKey its output-relative path, e.g. {@code cards/alpha.html}
     * @return the page with references resolved
     * @throws CardLinkException if any reference names a card or anchor that
     *         doesn't exist
     */
    public String site(String html, String pageKey) {
        String prefix = LayoutEngine.urlPrefixFor(pageKey);
        return resolve(html,
                (id, fragment) -> prefix + "cards/" + id + ".html"
                        + (fragment == null || fragment.isEmpty() ? "" : "#" + fragment),
                true, pageKey);
    }

    /** How a resolved reference is spelled for one output. */
    private interface Target {
        String href(String id, String fragment);
    }

    private String resolve(String html, Target target, boolean validate, String pageKey) {
        if (html == null || !html.contains(SCHEME)) return html;

        List<String> problems = new ArrayList<>();
        Matcher m = LINK.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        while (m.find()) {
            String id = m.group(3);
            String fragment = m.group(4);
            if (validate) {
                String problem = check(id, fragment);
                if (problem != null) problems.add(problem);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1) + m.group(2) + target.href(id, fragment) + m.group(2)));
        }
        m.appendTail(out);

        if (!problems.isEmpty()) throw new CardLinkException(message(problems, pageKey));
        return out.toString();
    }

    /** @return a description of what's wrong with this reference, or null when it resolves */
    private String check(String id, String fragment) {
        if (!anchors.containsKey(id)) {
            if (excluded.contains(id)) {
                return "card:" + id + reference(id) + " — card '" + id + "' is in the book but"
                        + " this build leaves it out, so the link would go nowhere here."
                        + " Widen the selection, or don't link to it from a card that ships"
                        + " without it.";
            }
            return "card:" + id + reference(id) + " — no card has that id."
                    + suggest(id, anchors.keySet());
        }
        if (fragment == null || fragment.isEmpty()) return null;
        Set<String> known = anchors.get(id);
        if (known.contains(fragment)) return null;
        return "card:" + id + "#" + fragment + reference(id)
                + " — card '" + id + "' has no block anchored '" + fragment + "'."
                + (known.isEmpty()
                        ? " That card has no anchored blocks (a block is anchored by its"
                                + " heading, or by an explicit {#id})."
                        : suggest(fragment, known));
    }

    /**
     * Which file a broken reference is written in.
     *
     * <p>Recovered by searching the cards for the literal text rather than
     * tracked through the render, because by the time a page is assembled the
     * prose has lost its provenance. Worth the scan: "no card has that id" with
     * no file to open is a grep, and a build failure should not set homework.
     */
    private String reference(String id) {
        String needle = SCHEME + id;
        for (Card card : cards) {
            if (containsRef(card.blocks(), needle)) {
                return " in " + (card.source() == null ? card.id() : card.source().getFileName());
            }
        }
        // A section body or a template wrote it; neither is in the card set.
        return "";
    }

    private static boolean containsRef(List<Block> blocks, String needle) {
        for (Block b : blocks) {
            if (b.html() != null && b.html().contains(needle)) return true;
            if (containsRef(b.children(), needle)) return true;
        }
        return false;
    }

    private String message(List<String> problems, String pageKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(problems.size() == 1
                ? "A card link points at nothing"
                : problems.size() + " card links point at nothing");
        if (pageKey != null) sb.append(" (building ").append(pageKey).append(')');
        sb.append(":\n");
        for (String p : problems) sb.append("  ").append(p).append('\n');
        sb.append("A card: link is checked against the book being built, so a renamed id "
                + "fails here instead of rotting into a dead link.");
        return sb.toString();
    }

    /** " Did you mean 'x'?", when something close enough exists. */
    private static String suggest(String written, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = distance(written.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        // A third of the length, so a typo suggests and a different word doesn't.
        int tolerance = Math.max(1, written.length() / 3);
        return best != null && bestDistance <= tolerance ? " Did you mean '" + best + "'?" : "";
    }

    /** Levenshtein distance, two rows at a time. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Every anchor a card's blocks offer, nested ones included. */
    private static void collectAnchors(List<Block> blocks, Set<String> into) {
        for (Block b : blocks) {
            String anchor = LayoutEngine.blockAnchor(b);
            if (anchor != null) into.add(anchor);
            collectAnchors(b.children(), into);
        }
    }
}
