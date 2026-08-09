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
 */
public record Axis(
        String name,
        String title,
        List<AxisValue> values,
        Path landingTemplate
) {

    public Axis {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
