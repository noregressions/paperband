package dev.noregressions.paperband;

import org.commonmark.node.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Variants: shorter versions of the same content, written side by side, of which one is shown.
 *
 * <pre>
 * :::: {! variants}
 * ::: {! variant=full}
 * Download the release archive for your platform, then verify its checksum.
 * :::
 * ::: {! variant=short}
 * Download the archive and verify it.
 * :::
 * ::::
 * </pre>
 *
 * The names are fixed and ranked {@code full}, {@code short}, {@code shortest}, whatever order
 * they're written in. Without a preference the longest present is shown; with one, that variant,
 * else the next shorter, else the next longer. Everything that reads content (selectors,
 * {@code rest}, rendering, plain text) sees only the chosen variant.
 */
public final class Variants {

    /** The variant names, longest first. */
    public enum Variant {
        FULL, SHORT, SHORTEST;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The variant called {@code name}, or null. */
        public static Variant of(String name) {
            for (Variant v : values()) {
                if (v.id().equals(name)) {
                    return v;
                }
            }
            return null;
        }
    }

    public static final String GROUP_TAG = "variants";
    public static final String VARIANT_KEY = "variant";

    private Variants() {
    }

    /** True for a {@code ::: {! variants}} group. */
    public static boolean isGroup(Node node) {
        return node instanceof Div d && d.getAttributes().tags().contains(GROUP_TAG);
    }

    /** The variant a {@code ::: {! variant=...}} div holds, or null. */
    public static Variant variantOf(Node node) {
        return node instanceof Div d ? Variant.of(d.getAttributes().values().get(VARIANT_KEY)) : null;
    }

    /**
     * The child of {@code group} to show.
     *
     * @param prefer the variant wanted, or null for the longest present
     */
    public static Node choose(Node group, Variant prefer) {
        List<Node> children = new ArrayList<>();
        for (Node c = group.getFirstChild(); c != null; c = c.getNext()) {
            children.add(c);
        }
        Node best = null;
        Variant bestVariant = null;
        if (prefer == null) {
            for (Node c : children) {
                Variant v = variantOf(c);
                if (v != null && (bestVariant == null || v.ordinal() < bestVariant.ordinal())) {
                    best = c;
                    bestVariant = v;
                }
            }
            return best;
        }
        // The preferred one; else the nearest shorter; else the nearest longer.
        int bestScore = Integer.MAX_VALUE;
        for (Node c : children) {
            Variant v = variantOf(c);
            if (v == null) {
                continue;
            }
            int distance = v.ordinal() - prefer.ordinal();
            int score = distance >= 0 ? distance : 100 - distance;
            if (score < bestScore) {
                best = c;
                bestScore = score;
            }
        }
        return best;
    }

    /** Checks a group: only {@code variant=} divs, known names, each once, at least one. */
    static void check(Div group) {
        List<Variant> seen = new ArrayList<>();
        for (Node c = group.getFirstChild(); c != null; c = c.getNext()) {
            if (!(c instanceof Div d) || !d.getAttributes().values().containsKey(VARIANT_KEY)) {
                throw new Attributes.MarkerException("a {! variants} group can only hold ::: {! variant=...} groups;"
                        + " found " + NodeInfo.name(c), NodeInfo.line(c));
            }
            String name = d.getAttributes().values().get(VARIANT_KEY);
            Variant v = Variant.of(name);
            if (v == null) {
                throw new Attributes.MarkerException("variant \"" + name + "\" isn't one of [full, short, shortest]",
                        NodeInfo.line(c));
            }
            if (seen.contains(v)) {
                throw new Attributes.MarkerException("variant \"" + name + "\" appears twice in this group", NodeInfo.line(c));
            }
            seen.add(v);
        }
        if (seen.isEmpty()) {
            throw new Attributes.MarkerException("a {! variants} group needs at least one ::: {! variant=...}",
                    NodeInfo.line(group));
        }
    }
}
