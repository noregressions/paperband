package dev.noregressions.paperband.layout;

import java.util.List;

/**
 * A cross-reference whose hand-written chapter number contradicts the number
 * the book computed.
 *
 * <p>A content failure rather than a crash, in the same family as
 * {@link CardLinkException}: the book rendered, but its prose asserts a chapter
 * number that isn't true. The goals map it onto Maven's build-failure kind, so
 * it reads like the page-budget check rather than like a bug in the plugin.
 */
public class NumberCheckException extends LayoutException {

    private final transient List<NumberCheck.Mismatch> mismatches;

    public NumberCheckException(List<NumberCheck.Mismatch> mismatches) {
        super(message(mismatches));
        this.mismatches = List.copyOf(mismatches);
    }

    /** The stale labels, in book order. */
    public List<NumberCheck.Mismatch> mismatches() {
        return mismatches;
    }

    private static String message(List<NumberCheck.Mismatch> mismatches) {
        StringBuilder sb = new StringBuilder();
        sb.append(mismatches.size() == 1
                ? "1 cross-reference states a chapter number that is no longer true:\n"
                : mismatches.size() + " cross-references state chapter numbers that are no"
                        + " longer true:\n");
        for (NumberCheck.Mismatch m : mismatches) {
            sb.append("  ").append(m).append('\n');
        }
        sb.append("\nChapter numbers are derived from book order, so a number written into a"
                + " link label is a copy that drifts. Delete the number from the label and"
                + " let it be rendered:\n"
                + "  [](card:some-card)       renders \"Chapter 3.14\"\n"
                + "  [title](card:some-card)  renders \"Chapter 3.14 — Some Card\"\n"
                + "A label that names the chapter in its own words, with no number in it, is"
                + " left alone.");
        return sb.toString();
    }
}
