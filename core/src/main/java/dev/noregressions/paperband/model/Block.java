package dev.noregressions.paperband.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A logical block extracted from a card's body. Carries its CSS classes
 * (auto-derived from heading text or explicit via Pandoc-attribute syntax),
 * an optional {@code id}, and the rendered HTML content.
 *
 * <p>Two block kinds: heading-derived sections (any heading level from
 * {@code h2} through {@code h6} — {@code h1} is reserved for the card title
 * and never becomes a block) and Pandoc fenced divs ({@code ::: {.class} ... :::}).
 *
 * <p><b>Nesting.</b> A {@code HEADING_SECTION} block owns every node between
 * its heading and the next heading whose level is less than or equal to its
 * own — including deeper headings, which become {@link #children()} rather
 * than being flattened into {@link #html()}. This is the same rank-based
 * sectioning rule Pandoc's {@code --section-divs} and Docutils' section
 * transform use: a heading at level <i>L</i> closes every currently-open
 * section at level <i>L</i> or deeper, then opens a new one nested under
 * whichever section (if any) is still open above it. Content before the
 * first real heading becomes a synthetic {@code intro} block (heading
 * {@code null}, {@code level} {@code 0}) at the top of {@link Card#blocks()};
 * it never has children, since nothing can nest above the first heading.
 *
 * <p>{@link #html()} is <em>only</em> this block's own direct content — the
 * markdown between its heading and whatever closes it (a child heading or a
 * same-or-shallower sibling/closing heading) — not the rendered content of
 * its children. A theme's template is expected to render {@link #html()}
 * and then recurse into {@link #children()} to get the full section.
 *
 * @param kind     whether the block came from a heading or a fenced div
 * @param id       explicit element id from the source attribute syntax, or null when none was set
 * @param classes  CSS classes attached to the block; never null, may be empty
 * @param heading  the heading text for {@link Kind#HEADING_SECTION} blocks; null for the synthetic intro block and fenced-div blocks
 * @param level    heading depth (2–6) for a real heading section; {@code 0} for the synthetic intro block
 * @param html     this block's own direct HTML content, excluding any nested children's content
 * @param children nested sub-sections opened by a deeper heading while this block was open; never null, may be empty
 * @param attributes every other attribute the heading carried ({@code ## Heading {step=1}}), in source
 *                   order, keyed by name; class and id are excluded (they have their own components).
 *                   Rendered onto the block's {@code <section>} alongside its classes; never null, may be empty
 * @param directives paperband instructions on the heading ({@code {!step}}), by name without the
 *                   {@code !}, with the value their pass gave them -- {@code step} is the block's
 *                   number among its siblings. Rendered as {@code data-paperband-<name>}; never
 *                   null, may be empty
 * @param nodes      the same direct content as {@code html}, as data: its top-level {@link Node}s in
 *                   document order, children excluded like {@code html}'s; never null, may be empty
 */
public record Block(
        Kind kind,
        String id,
        Set<String> classes,
        String heading,
        int level,
        String html,
        List<Block> children,
        Map<String, String> attributes,
        Map<String, String> directives,
        List<Node> nodes
) {

    public Block {
        classes = classes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(classes));
        children = children == null ? List.of() : List.copyOf(children);
        // Map.copyOf would lose source order, and the order is what the author
        // wrote -- keep it, so the rendered attribute list reads the same way.
        attributes = attributes == null || attributes.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        directives = directives == null || directives.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(directives));
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** A block whose content hasn't been read as nodes. */
    public Block(Kind kind, String id, Set<String> classes, String heading, int level,
                 String html, List<Block> children, Map<String, String> attributes,
                 Map<String, String> directives) {
        this(kind, id, classes, heading, level, html, children, attributes, directives, List.of());
    }

    /** A block with attributes but no directives. */
    public Block(Kind kind, String id, Set<String> classes, String heading, int level,
                 String html, List<Block> children, Map<String, String> attributes) {
        this(kind, id, classes, heading, level, html, children, attributes, Map.of());
    }

    /** A block with no attributes beyond its class and id. */
    public Block(Kind kind, String id, Set<String> classes, String heading, int level,
                 String html, List<Block> children) {
        this(kind, id, classes, heading, level, html, children, Map.of(), Map.of());
    }

    /** The text a {@code {!step}} marker is replaced with. */
    public static String stepLabel(String number) {
        return "Step " + number;
    }

    /**
     * The heading as the author wrote it, without the text its {@code {!step}}
     * markers were replaced with. Slugs and anchors derive from this, not from
     * {@link #heading()}, so a link to {@code #build} keeps working when
     * reordering turns "Step 2 Build" into "Step 3 Build".
     *
     * @return the heading without step labels, or null for a block with no heading
     */
    public String plainHeading() {
        return withoutStepLabel(heading, directives.get("step"));
    }

    /** {@code heading} with every {@link #stepLabel} for {@code step} removed. */
    public static String withoutStepLabel(String heading, String step) {
        if (heading == null || step == null || step.isEmpty()) return heading;
        return heading.replace(stepLabel(step), " ").replaceAll("\\s+", " ").strip();
    }

    public enum Kind { HEADING_SECTION, FENCED_DIV }
}
