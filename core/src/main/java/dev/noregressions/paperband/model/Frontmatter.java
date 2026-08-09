package dev.noregressions.paperband.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed YAML frontmatter from a markdown card file. Values are loosely typed
 * (strings, numbers, booleans, lists, maps) because that's what YAML gives us;
 * accessor helpers cast where the type is known.
 */
public record Frontmatter(Map<String, Object> values) {

    public Frontmatter {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static Frontmatter empty() {
        return new Frontmatter(Map.of());
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Optional<String> getString(String key) {
        Object v = values.get(key);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }

    public Optional<Integer> getInt(String key) {
        Object v = values.get(key);
        if (v == null) return Optional.empty();
        if (v instanceof Number n) return Optional.of(n.intValue());
        try { return Optional.of(Integer.parseInt(v.toString())); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }

    public Optional<Boolean> getBoolean(String key) {
        Object v = values.get(key);
        if (v == null) return Optional.empty();
        if (v instanceof Boolean b) return Optional.of(b);
        return Optional.of(Boolean.parseBoolean(v.toString()));
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object v = values.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(v.toString());
    }
}
