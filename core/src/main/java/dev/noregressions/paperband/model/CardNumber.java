package dev.noregressions.paperband.model;

/**
 * A card's chapter number: the numbering group it belongs to, and its position
 * within that group. The {@code 3} and the {@code 14} of "Chapter 3.14".
 *
 * <p>Held as two integers rather than a formatted string on purpose. A template
 * that wants {@code 3.14}, {@code Chapter 3.14} or bare {@code 14} composes it
 * from the parts; a string would force every one of those to be parsed back
 * apart again.
 *
 * <p>Numbers are <em>presentation</em>. A card's identity is its
 * {@code id} — see {@link Card} — and that is what URLs, anchors and PDF
 * destinations are built from, so renumbering a book never invalidates a link
 * or a bookmark.
 *
 * @param group   the numbering group's ordinal: the "3" in 3.14. Groups are
 *                sections by default, but several sections can share one group
 *                (a book "part") by declaring the same {@code part:}
 * @param ordinal position within the group, 1-based and counted in book order:
 *                the "14" in 3.14. Runs continuously across every section in
 *                the group
 */
public record CardNumber(int group, int ordinal) {

    public CardNumber {
        if (ordinal < 1) {
            throw new IllegalArgumentException(
                    "Card ordinal is 1-based; got " + ordinal);
        }
        if (group < 0) {
            throw new IllegalArgumentException(
                    "Card group cannot be negative; got " + group);
        }
    }

    /** The dotted form a reader sees: {@code "3.14"}. */
    public String label() {
        return group + "." + ordinal;
    }

    @Override
    public String toString() {
        return label();
    }
}
