package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A categorical axis declared at the book level. Cards classify themselves
 * along an axis by frontmatter field of the same {@link #name()}.
 *
 * <p>Multiple axes per book are allowed; each emits its own set of landing
 * pages (one per value).
 *
 * @param name            axis identifier; matches the frontmatter key cards use to assign themselves
 * @param title           human-readable axis title for display in landing pages and indices
 * @param values          allowed values along this axis, in declaration order
 * @param landingTemplate optional Pebble template path used when emitting per-value landing pages; null disables landing-page generation for this axis
 * @param dividers        whether this axis contributes PDF divider pages before the first card of each value run; false makes the axis label-only (card classes, badges, landing pages and nav still apply), so section dividers fire as if the axis were not there
 */
public record Axis(
        String name,
        String title,
        List<AxisValue> values,
        Path landingTemplate,
        boolean dividers
) {

    public Axis {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** Convenience constructor for the common case: an axis with divider pages. */
    public Axis(String name, String title, List<AxisValue> values, Path landingTemplate) {
        this(name, title, values, landingTemplate, true);
    }
}
