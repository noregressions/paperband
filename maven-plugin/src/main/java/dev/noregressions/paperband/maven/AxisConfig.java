package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One axis of a POM-declared book: a categorical dimension cards assign
 * themselves to, which the build turns into divider pages, per-value landing
 * pages, and nav entries.
 *
 * <pre>
 * &lt;axis&gt;
 *   &lt;name&gt;tier&lt;/name&gt;
 *   &lt;title&gt;Tier&lt;/title&gt;
 *   &lt;values&gt;
 *     &lt;value&gt;&lt;id&gt;1&lt;/id&gt;&lt;label&gt;Critical&lt;/label&gt;&lt;color&gt;#c0392b&lt;/color&gt;&lt;/value&gt;
 *     &lt;value&gt;&lt;id&gt;2&lt;/id&gt;&lt;label&gt;Standard&lt;/label&gt;&lt;color&gt;#e67e22&lt;/color&gt;&lt;/value&gt;
 *   &lt;/values&gt;
 * &lt;/axis&gt;
 * </pre>
 *
 * <p>{@code name} is the frontmatter key cards use ({@code tier: 1}). Axes are
 * opt-in structure: a value only gets a divider and a landing page because an
 * axis declared it, which is why declaring them in the POM is part of
 * describing the book there.
 */
public class AxisConfig {

    /** Axis identifier, and the frontmatter key cards assign themselves with. */
    private String name;

    /** Human-readable axis title, shown on landing pages and indices. */
    private String title;

    /** Allowed values, in declaration order — which is the order dividers appear in. */
    private List<AxisValueConfig> values = new ArrayList<>();

    /**
     * Per-value landing page template, as a path relative to the book root.
     * Omitted, the built-in landing template is used.
     */
    private String landingTemplate;

    /**
     * Whether this axis contributes PDF divider pages before the first card
     * of each value run. {@code <dividers>false</dividers>} makes the axis
     * label-only: cards keep their axis classes, badges, landing pages and
     * nav entries, but section dividers fire as if the axis were not there.
     * Defaults to true.
     */
    private Boolean dividers;

    /** @return the axis name */
    public String getName() {
        return name;
    }

    /** @return the axis title */
    public String getTitle() {
        return title;
    }

    /** @return the declared values, never null */
    public List<AxisValueConfig> getValues() {
        return values;
    }

    /** @return the landing template path, or null */
    public String getLandingTemplate() {
        return landingTemplate;
    }

    /** @return whether this axis contributes divider pages (default true) */
    public Boolean getDividers() {
        return dividers;
    }

    /**
     * Translate into the model's {@link Axis}.
     *
     * @param bookRoot the root a landing template path resolves against
     * @return the axis
     * @throws IllegalArgumentException if the axis declares no name or no values
     */
    Axis toAxis(Path bookRoot) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("<axis> declares no <name>");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "<axis> '" + name + "' declares no <values> — an axis with no values "
                            + "produces no dividers, landing pages or nav entries");
        }
        List<AxisValue> resolved = new ArrayList<>(values.size());
        for (AxisValueConfig value : values) {
            resolved.add(value.toAxisValue(name));
        }
        Path landing = (landingTemplate == null || landingTemplate.isBlank())
                ? null
                : bookRoot.resolve(landingTemplate.trim());
        return new Axis(name.trim(), title, resolved, landing, dividers == null || dividers);
    }
}
