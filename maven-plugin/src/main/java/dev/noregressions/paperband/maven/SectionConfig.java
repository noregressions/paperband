package dev.noregressions.paperband.maven;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code <section>} of a {@code <book>} layout: a titled group of cards
 * selected by glob pattern.
 *
 * <pre>
 * &lt;section&gt;
 *   &lt;id&gt;traces&lt;/id&gt;
 *   &lt;title&gt;Execution Traces&lt;/title&gt;
 *   &lt;includes&gt;
 *     &lt;include&gt;services/&#42;/TRACE.md&lt;/include&gt;
 *   &lt;/includes&gt;
 *   &lt;excludes&gt;
 *     &lt;exclude&gt;services/scratch/&#42;&#42;&lt;/exclude&gt;
 *   &lt;/excludes&gt;
 *   &lt;sort&gt;tier,-id&lt;/sort&gt;
 *   &lt;landingPage&gt;false&lt;/landingPage&gt;
 * &lt;/section&gt;
 * </pre>
 *
 * <p>A plain data holder: Maven's configurator populates the fields by
 * element name, and {@link BookLayout} turns them into
 * {@code BookPlan.SectionSpec}s. Every field is optional except
 * {@code includes}, and {@code id}/{@code title} are only optional together —
 * a section with neither is an anonymous selector that contributes cards but no
 * group (see {@code BookPlan.SectionSpec}).
 */
public class SectionConfig {

    /**
     * Section id, used wherever a section id appears — the PDF divider, the
     * site landing page ({@code <id>.html}), nav and sidebar entries. Derived
     * from {@link #title} as a slug when omitted.
     */
    private String id;

    /** Human-readable section title, shown on the divider and landing page. */
    private String title;

    /**
     * Optional landing/divider template for this section — a built-in preset
     * name (e.g. {@code minimal}) or a path to a template, exactly as a
     * section folder's own {@code landing.template} accepts.
     */
    private String landingTemplate;

    /**
     * Optional Pebble predicate over the build target, e.g.
     * {@code target == 'web'}. When it evaluates false the whole section is
     * skipped and its cards stay out of the book.
     */
    private String where;

    /**
     * Glob patterns selecting this section's cards, relative to the book root,
     * in emission order. {@code *} stops at a path separator, {@code **}
     * crosses it.
     */
    private List<String> includes = new ArrayList<>();

    /** Glob patterns removing files an {@link #includes} pattern matched. */
    private List<String> excludes = new ArrayList<>();

    /**
     * Comma-separated frontmatter field names to order this section's cards by,
     * most significant first, each optionally {@code -}-prefixed for
     * descending — e.g. {@code tier,-id}. Omitted, matches sort by their path
     * relative to the book root.
     */
    private String sort;

    /**
     * Whether this section gets a page of its own — the divider page before its
     * first card in the PDF, and the {@code <id>.html} landing page on the
     * static site. Generated for every named section by default; set
     * {@code false} to keep the grouping and the ordering but skip the page,
     * so the section's first card follows straight on from the previous section's
     * last one.
     */
    private boolean landingPage = true;

    /**
     * Misplacement trap, never a real setting: {@code <page>} belongs
     * <em>between</em> {@code <section>} elements (directly under
     * {@code <sections>}), but nesting it inside one is a natural mistake —
     * and without this field the configurator fails with "Cannot find 'page'
     * in class SectionConfig", which names the symptom and not the fix. With
     * it, configuration succeeds and {@link BookLayout#validate()} rejects the
     * declaration with a message saying where the marker goes.
     */
    private Page page;

    /** @return the section id, or null to derive it from the title */
    public String getId() {
        return id;
    }

    /** @return the section title */
    public String getTitle() {
        return title;
    }

    /** @return the landing template name or path, or null for the book default */
    public String getLandingTemplate() {
        return landingTemplate;
    }

    /** @return the target predicate, or null to always include this section */
    public String getWhere() {
        return where;
    }

    /** @return the include glob patterns, never null */
    public List<String> getIncludes() {
        return includes;
    }

    /** @return the exclude glob patterns, never null */
    public List<String> getExcludes() {
        return excludes;
    }

    /** @return the comma-separated sort fields, or null for path order */
    public String getSort() {
        return sort;
    }

    /** @return whether this section gets its own divider/landing page; true unless declared false */
    public boolean isLandingPage() {
        return landingPage;
    }

    /** @return a misplaced nested {@code <page>}, for {@link BookLayout#validate()} to reject; null when none */
    Page getMisplacedPage() {
        return page;
    }

    /** Parse {@link #sort} into individual field names, dropping blanks. */
    List<String> sortFields() {
        return splitFields(sort);
    }

    /**
     * Split a comma-separated field list, dropping blanks — the form both
     * {@code <section><sort>} and {@code <book><sort>} take.
     *
     * @param value the comma-separated value; may be null
     * @return the field names, never null
     */
    static List<String> splitFields(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String field : value.split(",")) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    @Override
    public String toString() {
        return "section[" + (id != null ? id : title != null ? title : "(anonymous)")
                + " includes=" + includes + "]";
    }
}
