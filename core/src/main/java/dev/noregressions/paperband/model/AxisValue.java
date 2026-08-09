package dev.noregressions.paperband.model;

import java.util.Map;

/**
 * One value of a categorical {@link Axis}.
 *
 * @param id    raw value from YAML, usually a {@link String} or {@link Integer}; preserved as-is so callers can match against frontmatter without coercion surprises
 * @param label human-readable display for this value
 * @param meta  axis-specific extras (colour, icon, description, etc.); never null, may be empty
 */
public record AxisValue(Object id, String label, Map<String, Object> meta) {

    public AxisValue {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static AxisValue of(Object id, String label) {
        return new AxisValue(id, label, Map.of());
    }
}
