package dev.noregressions.paperband.render.pptx;

import java.util.List;
import java.util.Map;

/**
 * Maps a card's slot signature onto one of PowerPoint's standard slide layouts.
 *
 * <p>This is the payoff of a slot-based deck theme. Free-form CSS gives N
 * bespoke arrangements for N slides; a fixed slot skeleton means each distinct
 * signature resolves to <em>one reusable</em> {@code slideLayout} that slides
 * reference — which is what makes PowerPoint's outline view, layout switching
 * and theme recolouring work on a generated deck.
 *
 * <p>The layouts are the eleven POI's default master already ships, so nothing
 * here authors {@code slideLayout} XML.
 *
 * <h2>Total by construction</h2>
 * The map is an explicit table rather than a chain of {@code if}s, because a
 * chain silently drops content: a {@code title+subtitle+body} card matched
 * "Title and Content", which has no {@code SUBTITLE} placeholder, and the
 * card's {@code oneliner} vanished with no error. Every signature this
 * exporter can produce has a row, and {@link #forSignature} throws on one that
 * doesn't — a region with nowhere to land must fail, not disappear.
 */
final class LayoutMap {

    /** The regions a card can offer, in placeholder-fill order. */
    record Signature(boolean title, boolean subtitle, boolean body, boolean aside) {

        /** Stable, readable key — also what the build logs. */
        String key() {
            StringBuilder sb = new StringBuilder();
            if (title) sb.append("title");
            if (subtitle) sb.append(sb.isEmpty() ? "subtitle" : "+subtitle");
            if (body) sb.append(sb.isEmpty() ? "body" : "+body");
            if (aside) sb.append(sb.isEmpty() ? "aside" : "+aside");
            return sb.isEmpty() ? "(empty)" : sb.toString();
        }
    }

    /**
     * Signature key to layout name, as named in POI's default slide master.
     *
     * <p>{@code Two Content} carries two {@code CONTENT} placeholders, which is
     * what a body-plus-aside card needs. {@code Title Slide} is the only stock
     * layout with a {@code SUBTITLE}, so a card with a {@code oneliner} and no
     * body lands there; a card with all three has no stock layout that fits and
     * is reported rather than silently trimmed.
     */
    private static final Map<String, String> LAYOUTS = Map.ofEntries(
            Map.entry("title", "Title Only"),
            Map.entry("title+body", "Title and Content"),
            Map.entry("title+aside", "Title and Content"),
            Map.entry("title+body+aside", "Two Content"),
            Map.entry("title+subtitle", "Title Slide"),
            Map.entry("body", "Title and Content"),
            Map.entry("(empty)", "Blank"));

    /**
     * Signatures with no stock layout, and what the author should do instead.
     * Naming the fix matters more than naming the problem: the deck theme
     * decides which region to drop, and only the author knows which.
     */
    private static final Map<String, String> UNSUPPORTED = Map.of(
            "title+subtitle+body",
            "no stock layout carries a subtitle and a content region. Move the"
                    + " oneliner into the body slot, or drop it from this card's frontmatter",
            "title+subtitle+aside",
            "no stock layout carries a subtitle and a content region. Move the"
                    + " oneliner into the aside slot, or drop it from this card's frontmatter",
            "title+subtitle+body+aside",
            "no stock layout carries a subtitle plus two content regions. Drop"
                    + " the oneliner from this card's frontmatter");

    private LayoutMap() {}

    /**
     * The layout for {@code sig}.
     *
     * @throws IllegalArgumentException naming the card, the signature and the
     *         fix, when no stock layout can hold every region the card has
     */
    static String forSignature(Signature sig, String cardId) {
        String key = sig.key();
        String layout = LAYOUTS.get(key);
        if (layout != null) return layout;

        String advice = UNSUPPORTED.get(key);
        throw new IllegalArgumentException(
                "Card '" + cardId + "' has slot signature '" + key + "', which "
                        + (advice != null ? advice
                                          : "has no matching PowerPoint layout. Supported: "
                                            + List.copyOf(new java.util.TreeSet<>(LAYOUTS.keySet())))
                        + ".");
    }
}
