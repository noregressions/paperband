package dev.noregressions.paperband.maven;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code <part>} of a {@code <book>} layout: a titled group of cards
 * selected by glob pattern.
 *
 * <pre>
 * &lt;part&gt;
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
 * &lt;/part&gt;
 * </pre>
 *
 * <p>A plain data holder: Maven's configurator populates the fields by
 * element name, and {@link BookLayout} turns them into
 * {@code BookPlan.PartSpec}s. Every field is optional except
 * {@code includes}, and {@code id}/{@code title} are only optional together —
 * a part with neither is an anonymous selector that contributes cards but no
 * group (see {@code BookPlan.PartSpec}).
 */
public class PartConfig {

    /**
     * Part id, used wherever a section id would be — the PDF divider, the
     * site landing page ({@code <id>.html}), nav and sidebar entries. Derived
     * from {@link #title} as a slug when omitted.
     */
    private String id;

    /** Human-readable part title, shown on the divider and landing page. */
    private String title;

    /**
     * Optional landing/divider template for this part — a built-in preset
     * name (e.g. {@code minimal}) or a path to a template, exactly as a
     * section folder's own {@code landing.template} accepts.
     */
    private String landingTemplate;

    /**
     * Optional Pebble predicate over the build target, e.g.
     * {@code target == 'web'}. When it evaluates false the whole part is
     * skipped and its cards stay out of the book.
     */
    private String where;

    /**
     * Glob patterns selecting this part's cards, relative to the book root,
     * in emission order. {@code *} stops at a path separator, {@code **}
     * crosses it.
     */
    private List<String> includes = new ArrayList<>();

    /** Glob patterns removing files an {@link #includes} pattern matched. */
    private List<String> excludes = new ArrayList<>();

    /**
     * Comma-separated frontmatter field names to order this part's cards by,
     * most significant first, each optionally {@code -}-prefixed for
     * descending — e.g. {@code tier,-id}. Omitted, matches sort by their path
     * relative to the book root.
     */
    private String sort;

    /**
     * Whether this part gets a page of its own — the divider page before its
     * first card in the PDF, and the {@code <id>.html} landing page on the
     * static site. Generated for every named part by default; set
     * {@code false} to keep the grouping and the ordering but skip the page,
     * so the part's first card follows straight on from the previous part's
     * last one.
     */
    private boolean landingPage = true;

    /** @return the part id, or null to derive it from the title */
    public String getId() {
        return id;
    }

    /** @return the part title */
    public String getTitle() {
        return title;
    }

    /** @return the landing template name or path, or null for the book default */
    public String getLandingTemplate() {
        return landingTemplate;
    }

    /** @return the target predicate, or null to always include this part */
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

    /** @return whether this part gets its own divider/landing page; true unless declared false */
    public boolean isLandingPage() {
        return landingPage;
    }

    /** Parse {@link #sort} into individual field names, dropping blanks. */
    List<String> sortFields() {
        return splitFields(sort);
    }

    /**
     * Split a comma-separated field list, dropping blanks — the form both
     * {@code <part><sort>} and {@code <book><sort>} take.
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
        return "part[" + (id != null ? id : title != null ? title : "(anonymous)")
                + " includes=" + includes + "]";
    }
}
