package dev.noregressions.paperband;

import org.commonmark.node.Block;
import org.commonmark.node.Node;

import java.util.Locale;
import java.util.Map;

/**
 * Break markers: where an author wants a splitting box to break, or not.
 *
 * <pre>
 * ## Step 3 {! break=force}       always break before this block, even with room left
 * ## Step 4 {! break=prefer}      break here if the box must break and the page is full enough
 * {! break=avoid}                 never break just before this block
 * ::: {! keep} ... :::            never break inside this group
 * </pre>
 *
 * Both go on blocks only. When the file is parsed, a {@code force} or {@code prefer} inside a
 * {@code keep} group is an error; which box a marker lands in, and whether that box can break
 * there, is checked when pages are made for a paged output.
 */
public final class Breaks {

    public enum Break {
        PREFER, AVOID, FORCE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** True for the markers that ask for a break, rather than forbid one. */
        public boolean wantsBreak() {
            return this != AVOID;
        }
    }

    public static final String BREAK_KEY = "break";
    public static final String KEEP_TAG = "keep";

    private Breaks() {
    }

    /** The break marker in {@code a}, or null. */
    public static Break of(Attributes a) {
        String v = a == null ? null : a.values().get(BREAK_KEY);
        if (v == null) {
            return null;
        }
        for (Break b : Break.values()) {
            if (b.id().equals(v)) {
                return b;
            }
        }
        return null;
    }

    public static boolean isKeep(Attributes a) {
        return a != null && a.tags().contains(KEEP_TAG);
    }

    /** Checks one node's markers; {@code all} holds every node's, for finding enclosing {@code keep} groups. */
    static void check(Node n, Attributes a, Map<Node, Attributes> all, int line) {
        String value = a.values().get(BREAK_KEY);
        if (value != null && of(a) == null) {
            throw new Attributes.MarkerException("break \"" + value + "\" isn't one of [prefer, avoid, force]", line);
        }
        if ((value != null || isKeep(a)) && !(n instanceof Block)) {
            throw new Attributes.MarkerException("{! " + (value != null ? "break=" + value : KEEP_TAG)
                    + "} goes on a block (a heading, paragraph, list, code block or ::: group), not inside text", line);
        }
        Break b = of(a);
        if (b != null && b.wantsBreak()) {
            for (Node p = n.getParent(); p != null; p = p.getParent()) {
                if (isKeep(all.get(p))) {
                    throw new Attributes.MarkerException("{! break=" + b.id() + "} can't take effect inside the"
                            + " {! keep} group on line " + NodeInfo.line(p) + ", which never breaks", line);
                }
            }
        }
    }
}
