package dev.noregressions.paperband.layout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@code sort:} order for files matched by a {@code files:} pattern.
 *
 * <pre>
 * sort: page.chapter                              # one key, ascending
 * sort: [page.part, page.chapter, path]           # later keys break ties
 * sort: { by: page.date, order: desc, missing: last }
 * </pre>
 *
 * Keys are {@code path} or {@code page.<front matter key>} (dotted for nested values). A file
 * missing a sort key is an error unless {@code missing} is {@code first} or {@code last}.
 * Comparing different kinds of value (a number with text) is an error.
 */
final class SortSpec {

    enum Order { ASC, DESC }

    enum Missing { ERROR, FIRST, LAST }

    record Key(String by, Order order, Missing missing) {
    }

    /** The default: by path, ascending. */
    static final SortSpec BY_PATH = new SortSpec("", List.of(new Key("path", Order.ASC, Missing.ERROR)));

    private final String context;
    private final List<Key> keys;

    private SortSpec(String context, List<Key> keys) {
        this.context = context;
        this.keys = keys;
    }

    static SortSpec parse(Object value, String context) throws ConfigException {
        List<Object> items = value instanceof List<?> list ? new ArrayList<>(list) : List.of(value);
        if (items.isEmpty()) {
            throw new ConfigException(context + ": empty sort");
        }
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String ctx = items.size() == 1 ? context : context + "[" + i + "]";
            Object item = items.get(i);
            if (item instanceof Map) {
                YamlSpec spec = YamlSpec.of(item, ctx);
                String by = checkKey(spec.requiredString("by"), ctx);
                Order order = choice(Order.class, spec.string("order", "asc"), spec.child("order"));
                Missing missing = choice(Missing.class, spec.string("missing", "error"), spec.child("missing"));
                spec.finish();
                keys.add(new Key(by, order, missing));
            } else if (item instanceof String s) {
                keys.add(new Key(checkKey(s, ctx), Order.ASC, Missing.ERROR));
            } else {
                throw new ConfigException(ctx + ": expected path, page.<key>, or { by: ... }, got " + YamlSpec.describe(item));
            }
        }
        return new SortSpec(context, List.copyOf(keys));
    }

    private static String checkKey(String key, String context) throws ConfigException {
        if (!key.equals("path") && !(key.startsWith("page.") && key.length() > 5)) {
            throw new ConfigException(context + ": sort key \"" + key + "\" must be \"path\" or \"page.<front matter key>\"");
        }
        return key;
    }

    static <E extends Enum<E>> E choice(Class<E> type, String value, String context) throws ConfigException {
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(value)) {
                return e;
            }
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            names.add(e.name().toLowerCase());
        }
        throw new ConfigException(context + ": \"" + value + "\" isn't one of " + names);
    }

    List<Key> keys() {
        return keys;
    }

    /** Sorts {@code files} (stable), reading values from {@code frontMatter}. */
    List<Path> sort(List<Path> files, Map<Path, Map<String, Object>> frontMatter) throws ConfigException {
        for (Path f : files) {
            for (Key k : keys) {
                if (k.missing() == Missing.ERROR && value(k, f, frontMatter) == null) {
                    throw new ConfigException(context + ": " + f + " has no value for sort key \"" + k.by()
                            + "\" (add it, or use { by: " + k.by() + ", missing: last })");
                }
            }
        }
        List<Path> sorted = new ArrayList<>(files);
        try {
            sorted.sort((a, b) -> {
                for (Key k : keys) {
                    int c = compare(k, value(k, a, frontMatter), value(k, b, frontMatter));
                    if (c != 0) {
                        return k.order() == Order.DESC ? -c : c;
                    }
                }
                return 0;
            });
        } catch (IllegalArgumentException e) {
            throw new ConfigException(context + ": " + e.getMessage());
        }
        return sorted;
    }

    private static Object value(Key k, Path file, Map<Path, Map<String, Object>> frontMatter) {
        return k.by().equals("path")
                ? Globs.slashed(file)
                : Values.lookup(frontMatter.getOrDefault(file, Map.of()), k.by().substring(5));
    }

    private static int compare(Key k, Object a, Object b) {
        if (a == null || b == null) {
            if (a == b) {
                return 0;
            }
            // Missing values go first or last regardless of asc/desc, so undo the later flip for desc.
            int nullFirst = a == null ? -1 : 1;
            int c = k.missing() == Missing.FIRST ? nullFirst : -nullFirst;
            return k.order() == Order.DESC ? -c : c;
        }
        return Values.compare(a, b);
    }
}
