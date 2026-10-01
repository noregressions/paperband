package dev.noregressions.paperband.number;

/**
 * One section's numbering declaration, as written in its {@code _section.md}
 * frontmatter.
 *
 * <pre>
 * ---
 * part: 3          # join numbering group 3, sharing it with sibling sections
 * ---
 *
 * ---
 * numbered: false  # front matter and appendices are named, not numbered
 * ---
 *
 * ---
 * numbering: "Scenario {n}"  # its cards are Scenario 1, 2, 3, whether or
 * ---                        # not the book numbers its chapters
 * </pre>
 *
 * <p>A section that declares neither — or has no {@code _section.md} at all —
 * gets {@link #discovered()}: numbered, and a group of its own.
 *
 * @param numbered whether this section's cards are numbered at all. False for
 *                 front matter and appendices, whose folders are ordered by a
 *                 numeric prefix ({@code 99-appendix}) that must not be
 *                 mistaken for a part number
 * @param part     the numbering group to join, or null to be a group of one
 *                 section. Sections sharing a part number number continuously
 *                 across the whole group, in book order
 * @param format   how this section's numbers read, with {@code {n}} for the
 *                 card's place in its group and {@code {part}} for the group's
 *                 number: {@code "Scenario {n}"}. Null for the dotted chapter
 *                 number. A section with a format is numbered on its own
 *                 account, without the book's {@code vars.numbering}
 */
public record SectionNumbering(boolean numbered, Integer part, String format) {

    private static final SectionNumbering DISCOVERED = new SectionNumbering(true, null, null);

    /** A declaration with no format: the dotted chapter number. */
    public SectionNumbering(boolean numbered, Integer part) {
        this(numbered, part, null);
    }

    /** Whether the section asked for its own numbers, which it gets even when the book numbers nothing. */
    public boolean formatted() {
        return format != null;
    }

    /** The default for a section that says nothing: numbered, its own group. */
    public static SectionNumbering discovered() {
        return DISCOVERED;
    }

    /** A section that opts out of numbering entirely. */
    public static SectionNumbering unnumbered() {
        return new SectionNumbering(false, null, null);
    }
}
