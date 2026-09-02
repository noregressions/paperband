package dev.noregressions.paperband.number;

import dev.noregressions.paperband.model.CardNumber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns every card its chapter number, from nothing but book order.
 *
 * <p>The number is <em>derived</em>, never authored. A book that wrote its
 * numbers by hand would have them in two places at once — the filename that
 * orders the chapter and every cross-reference that points at it — and nothing
 * keeping the two honest. Here the single source is the order the book walker
 * already established, so inserting or moving a chapter renumbers the book for
 * free and renaming a file is never required to change a number.
 *
 * <h2>Groups</h2>
 * <p>Numbering happens per <em>group</em>, not per section. By default a
 * section is its own group and groups are numbered by the order they first
 * appear: {@code 01-getting-started} yields 1.1, 1.2, …, and
 * {@code 02-authoring} yields 2.1, 2.2, ….
 *
 * <p>That is not always the right unit. A book whose "Part 3" spans five
 * sibling folders wants its chapter numbers to run continuously across all
 * five — 3.1 through 3.39 — with each folder keeping its own divider page. Such
 * sections declare {@code part: 3} and share one group.
 *
 * <p>Declaring parts is all-or-nothing among numbered sections. A book that
 * declares some and not others is rejected rather than guessed at: there is no
 * defensible answer for what number an undeclared section should take once its
 * neighbours have opted out of positional numbering.
 *
 * @see SectionNumbering
 */
public final class Numbering {

    private Numbering() {
    }

    /**
     * One card's placement in the book: which card, and which section it sits
     * in. Built by the caller, which owns section resolution.
     *
     * @param cardId    the card's stable id
     * @param sectionId the id of the section the card belongs to, after any
     *                  {@code sections:} declaration has had its say
     */
    public record Placement(String cardId, String sectionId) {
    }

    /**
     * Number every card that sits in a numbered section.
     *
     * @param placements cards in book order, one entry each
     * @param sections   numbering declarations by section id; a section absent
     *                   from the map is treated as {@link
     *                   SectionNumbering#discovered()}
     * @return card id to number, in book order. Cards in unnumbered sections
     *         are absent rather than present-and-null, so a caller asking for
     *         one gets nothing instead of a number to render
     * @throws IllegalStateException when parts are declared for some numbered
     *                               sections but not all
     */
    public static Map<String, CardNumber> resolve(
            List<Placement> placements, Map<String, SectionNumbering> sections) {
        if (placements == null || placements.isEmpty()) return Map.of();
        Map<String, SectionNumbering> declared = sections == null ? Map.of() : sections;

        // Section order of first appearance, restricted to numbered sections:
        // both the implicit group numbering and the all-or-nothing check below
        // need it, and it is the book's own order rather than the map's.
        Set<String> numberedSections = new LinkedHashSet<>();
        for (Placement p : placements) {
            if (numbering(declared, p.sectionId()).numbered()) {
                numberedSections.add(p.sectionId());
            }
        }
        requireConsistentParts(declared, numberedSections);

        // Group key -> group number. The key is the declared part when there is
        // one, and the section id otherwise; the two never mix, so a key can
        // safely be either.
        Map<Object, Integer> groupNumbers = new LinkedHashMap<>();
        for (String sectionId : numberedSections) {
            SectionNumbering sn = numbering(declared, sectionId);
            if (sn.part() != null) {
                groupNumbers.putIfAbsent(sn.part(), sn.part());
            } else {
                groupNumbers.putIfAbsent(sectionId, groupNumbers.size() + 1);
            }
        }

        Map<Object, Integer> counters = new LinkedHashMap<>();
        Map<String, CardNumber> out = new LinkedHashMap<>();
        for (Placement p : placements) {
            SectionNumbering sn = numbering(declared, p.sectionId());
            if (!sn.numbered()) continue;
            Object key = sn.part() != null ? sn.part() : p.sectionId();
            int ordinal = counters.merge(key, 1, Integer::sum);
            out.put(p.cardId(), new CardNumber(groupNumbers.get(key), ordinal));
        }
        return out;
    }

    private static SectionNumbering numbering(
            Map<String, SectionNumbering> declared, String sectionId) {
        SectionNumbering sn = declared.get(sectionId);
        return sn == null ? SectionNumbering.discovered() : sn;
    }

    /**
     * Parts are declared for every numbered section or for none. Half-declared
     * is a book that cannot be numbered without guessing, so it stops here with
     * the offending sections named.
     */
    private static void requireConsistentParts(
            Map<String, SectionNumbering> declared, Set<String> numberedSections) {
        List<String> with = new ArrayList<>();
        List<String> without = new ArrayList<>();
        for (String sectionId : numberedSections) {
            (numbering(declared, sectionId).part() != null ? with : without).add(sectionId);
        }
        if (with.isEmpty() || without.isEmpty()) return;
        throw new IllegalStateException(
                "Numbering: `part:` is declared by some numbered sections but not all, so the"
                        + " undeclared ones have no defensible number.\n"
                        + "  declares part: " + String.join(", ", with) + "\n"
                        + "  does not:      " + String.join(", ", without) + "\n"
                        + "Give every numbered section a `part:` in its _section.md, or remove"
                        + " them all and let sections number themselves. A section that should"
                        + " not be numbered at all wants `numbered: false` instead.");
    }
}
