package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict reader for one YAML mapping in a config file. Every read marks a key as known;
 * {@link #finish()} then rejects any key that wasn't read, so typos fail loudly instead of
 * being ignored. Errors carry the location, e.g. {@code manifest.yml: sequence[2].sort}.
 */
final class YamlSpec {

    private final String context;
    private final boolean root;
    private final Map<String, Object> map;
    private final Set<String> known = new LinkedHashSet<>();

    private YamlSpec(String context, boolean root, Map<String, Object> map) {
        this.context = context;
        this.root = root;
        this.map = map;
    }

    /** The top-level mapping of a file; child locations read {@code file.yml: key}. */
    static YamlSpec root(Object value, String fileName) throws ConfigException {
        return of(value, fileName, true);
    }

    /** A nested mapping; child locations read {@code context.key}. */
    static YamlSpec of(Object value, String context) throws ConfigException {
        return of(value, context, false);
    }

    @SuppressWarnings("unchecked")
    private static YamlSpec of(Object value, String context, boolean root) throws ConfigException {
        if (!(value instanceof Map<?, ?>)) {
            throw new ConfigException(context + ": expected key: value pairs, got " + describe(value));
        }
        return new YamlSpec(context, root, (Map<String, Object>) value);
    }

    String context() {
        return context;
    }

    String child(String key) {
        return context + (root ? ": " : ".") + key;
    }

    boolean has(String key) {
        known.add(key);
        return map.containsKey(key) && map.get(key) != null;
    }

    Object raw(String key) {
        known.add(key);
        return map.get(key);
    }

    String string(String key, String fallback) throws ConfigException {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Map || v instanceof List) {
            throw error(key, "expected a single value, got " + describe(v));
        }
        return v.toString();
    }

    String requiredString(String key) throws ConfigException {
        String v = string(key, null);
        if (v == null || v.isBlank()) {
            throw new ConfigException(context + ": missing required key \"" + key + "\"");
        }
        return v;
    }

    boolean bool(String key, boolean fallback) throws ConfigException {
        Object v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof Boolean b)) {
            throw error(key, "expected true or false, got " + describe(v));
        }
        return b;
    }

    /** A single string or a list of strings. */
    List<String> strings(String key) throws ConfigException {
        Object v = raw(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            return List.of(v.toString());
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null || o instanceof Map || o instanceof List) {
                throw error(key, "expected a list of single values, got " + describe(o));
            }
            out.add(o.toString());
        }
        return out;
    }

    /** A nested mapping, or null if absent. */
    YamlSpec map(String key) throws ConfigException {
        Object v = raw(key);
        return v == null ? null : of(v, child(key));
    }

    /** A list of mappings. */
    List<YamlSpec> maps(String key) throws ConfigException {
        Object v = raw(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw error(key, "expected a list, got " + describe(v));
        }
        List<YamlSpec> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(of(list.get(i), child(key) + "[" + i + "]"));
        }
        return out;
    }

    /** All keys, in file order, for mappings whose keys are data (e.g. {@code where}). */
    Set<String> keys() {
        known.addAll(map.keySet());
        return map.keySet();
    }

    /** @throws ConfigException if the mapping has a key nothing asked for */
    void finish() throws ConfigException {
        for (String key : map.keySet()) {
            if (!known.contains(key)) {
                throw new ConfigException(context + ": unknown key \"" + key + "\"; expected one of " + known);
            }
        }
    }

    ConfigException error(String key, String message) {
        return new ConfigException(child(key) + ": " + message);
    }

    static String describe(Object v) {
        return switch (v) {
            case null -> "nothing";
            case Map<?, ?> m -> "a mapping";
            case List<?> l -> "a list";
            default -> "\"" + v + "\"";
        };
    }
}
