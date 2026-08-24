package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.AxisValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One value of a POM-declared {@code <axis>}.
 *
 * <pre>
 * &lt;value&gt;
 *   &lt;id&gt;1&lt;/id&gt;
 *   &lt;label&gt;Critical&lt;/label&gt;
 *   &lt;color&gt;#c0392b&lt;/color&gt;
 * &lt;/value&gt;
 * </pre>
 *
 * <p>The id is a string here, where a yaml one keeps its native type — a yaml
 * {@code id: 1} stays an Integer. That costs nothing: every comparison between
 * an axis value and a card's frontmatter runs both sides through
 * {@code String.valueOf} first, so {@code <id>1</id>} matches a card declaring
 * {@code tier: 1} exactly as the yaml form does.
 */
public class AxisValueConfig {

    /** Value id, matched against the frontmatter field named by the axis. */
    private String id;

    /** Human-readable label for dividers, landing pages and nav. */
    private String label;

    /** Accent colour for this value, e.g. {@code #c0392b}. Shorthand for a {@code color} meta entry. */
    private String color;

    /**
     * Further per-value extras themes may read — icon, description, anything a
     * template asks for. Flat strings, like {@code <book><vars>}.
     */
    private Map<String, String> meta = new LinkedHashMap<>();

    /** @return the value id */
    public String getId() {
        return id;
    }

    /** @return the display label */
    public String getLabel() {
        return label;
    }

    /** @return the accent colour, or null */
    public String getColor() {
        return color;
    }

    /** @return the extra meta entries, never null */
    public Map<String, String> getMeta() {
        return meta;
    }

    /**
     * Translate into the model's {@link AxisValue}, folding {@code <color>} in
     * with the rest of the meta the way a yaml value's sibling keys are.
     *
     * @param axisName the owning axis, for error messages
     * @return the axis value
     * @throws IllegalArgumentException when no id is declared
     */
    AxisValue toAxisValue(String axisName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "<axis> '" + axisName + "' declares a <value> with no <id>");
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (color != null && !color.isBlank()) resolved.put("color", color.trim());
        resolved.putAll(meta);
        return new AxisValue(id.trim(), label, resolved);
    }
}
