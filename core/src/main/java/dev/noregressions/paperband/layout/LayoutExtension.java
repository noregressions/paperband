package dev.noregressions.paperband.layout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layout composition: a layout that {@code extends} another is merged with it before it is
 * checked, so the result is validated exactly like any layout.
 *
 * <pre>
 * extends: tutorial              # a layout file, or a layout that extends one
 * boxes:                         # changes to the base's boxes, by id
 *   steps:                       #   values replace, maps merge, null removes a key
 *     split: { between: "section.h2" }
 *   steps/command:               #   a box nested inside another box, by path
 *     class: big-command
 *   notes: null                  #   removes the box
 * add:                           # new boxes, after the base's (or before:/after: a box id)
 *   - { id: footer, text: Draft, after: notes }
 * </pre>
 *
 * Other layout keys replace the base's, except {@code css} and {@code stylesheet}, which are kept
 * from both (the base's first). A stylesheet is read relative to the file that names it, so a
 * base's styles keep working when a layout elsewhere extends it.
 */
final class LayoutExtension {

    /** Stylesheets already read, each {@code {name, css}}, base first; {@link Layout} scopes them. */
    static final String READ_STYLESHEETS = "_stylesheets";

    /** A layout definition as written: its YAML, the directory its paths are relative to, its name in messages. */
    record Raw(Object yaml, Path dir, String shown) {
    }

    private LayoutExtension() {
    }

    /**
     * The layout {@code name} with everything it extends merged in.
     *
     * @param raws every layout definition available as a base, by name
     */
    static Map<String, Object> resolve(String name, Map<String, Raw> raws) throws IOException {
        return resolve(name, raws.get(name), raws, new ArrayDeque<>());
    }

    /** Like {@link #resolve(String, Map)}, for a definition that isn't in {@code raws} (a front matter layout). */
    static Map<String, Object> resolve(String name, Raw raw, Map<String, Raw> raws) throws IOException {
        return resolve(name, raw, raws, new ArrayDeque<>());
    }

    private static Map<String, Object> resolve(String name, Raw raw, Map<String, Raw> raws, Deque<String> chain)
            throws IOException {
        if (chain.contains(name)) {
            List<String> loop = new ArrayList<>(chain.reversed());
            loop.add(name);
            throw new ConfigException("layouts extend each other in a loop: " + String.join(" > ", loop));
        }
        Map<String, Object> own = withStylesheet(map(raw.yaml(), raw.shown()), raw.dir(), raw.shown());
        Object base = own.remove("extends");
        if (base == null) {
            if (own.get("boxes") instanceof Map) {
                throw new ConfigException(raw.shown() + ": boxes: a map of changes only applies with extends;"
                        + " without it, boxes is a list");
            }
            if (own.containsKey("add")) {
                throw new ConfigException(raw.shown() + ": add only applies with extends");
            }
            return own;
        }
        if (!(base instanceof String baseName)) {
            throw new ConfigException(raw.shown() + ": extends: expected a layout name, got " + YamlSpec.describe(base));
        }
        if (baseName.equals(Layout.PAGE_LAYOUT)) {
            throw new ConfigException(raw.shown() + ": extends: \"page\" isn't a layout here; name the layout to extend");
        }
        Raw baseRaw = raws.get(baseName);
        if (baseRaw == null) {
            throw new ConfigException(raw.shown() + ": extends: unknown layout \"" + baseName + "\"; available: "
                    + raws.keySet().stream().sorted().toList());
        }
        chain.push(name);
        Map<String, Object> merged;
        try {
            merged = resolve(baseName, baseRaw, raws, chain);
        } finally {
            chain.pop();
        }
        return extend(copy(merged), own, baseName, raw.shown());
    }

    /** Applies {@code child}'s changes to a copy of the base. */
    private static Map<String, Object> extend(Map<String, Object> base, Map<String, Object> child, String baseName,
                                              String shown) throws ConfigException {
        String context = shown + " (extends " + baseName + ")";
        Map<String, Object> out = base;
        out.remove("description");
        Object changes = child.remove("boxes");
        Object additions = child.remove("add");
        for (var e : child.entrySet()) {
            if (e.getKey().equals("css") && out.get("css") instanceof String baseCss && e.getValue() instanceof String css) {
                out.put("css", baseCss + "\n" + css);
            } else if (e.getKey().equals(READ_STYLESHEETS) && out.get(READ_STYLESHEETS) instanceof List<?> baseSheets
                    && e.getValue() instanceof List<?> sheets) {
                List<Object> both = new ArrayList<>(baseSheets);
                both.addAll(sheets);
                out.put(READ_STYLESHEETS, both);
            } else if (e.getValue() == null) {
                out.remove(e.getKey());
            } else {
                out.put(e.getKey(), copy(e.getValue()));
            }
        }

        List<Object> boxes = boxes(out, context, "the base layout " + baseName);
        if (changes != null) {
            if (!(changes instanceof Map<?, ?> byId)) {
                throw new ConfigException(context + ": boxes: with extends, boxes is a map of box id to changes,"
                        + " e.g. { steps: { pick: first } }; new boxes go under add");
            }
            for (var c : byId.entrySet()) {
                change(boxes, String.valueOf(c.getKey()), c.getValue(), context);
            }
        }
        if (additions != null) {
            if (!(additions instanceof List<?> list)) {
                throw new ConfigException(context + ": add: expected a list of boxes");
            }
            for (int i = 0; i < list.size(); i++) {
                add(boxes, list.get(i), context + ": add[" + i + "]");
            }
        }
        out.put("boxes", boxes);
        return out;
    }

    /** Changes (or with {@code null}, removes) the box at {@code path}, e.g. {@code steps/command}. */
    private static void change(List<Object> boxes, String path, Object changes, String context) throws ConfigException {
        String[] parts = path.split("/");
        List<Object> level = boxes;
        String where = "the layout";
        for (int i = 0; i < parts.length; i++) {
            int index = indexOf(level, parts[i]);
            if (index < 0) {
                throw new ConfigException(context + ": boxes." + path + ": " + where + " has no box \"" + parts[i]
                        + "\"; its boxes are " + ids(level));
            }
            Map<String, Object> box = boxMap(level.get(index));
            if (i == parts.length - 1) {
                if (changes == null) {
                    level.remove(index);
                } else if (changes instanceof Map<?, ?> m) {
                    merge(box, m);
                } else {
                    throw new ConfigException(context + ": boxes." + path + ": expected the box's changes, or null to"
                            + " remove it, got " + YamlSpec.describe(changes));
                }
                return;
            }
            if (!(box.get("boxes") instanceof List<?>)) {
                String named = box.get("layout") instanceof String n ? "; it uses the layout \"" + n + "\", so extend"
                        + " that layout instead" : "";
                throw new ConfigException(context + ": boxes." + path + ": box \"" + parts[i]
                        + "\" has no nested boxes" + named);
            }
            @SuppressWarnings("unchecked")
            List<Object> nested = (List<Object>) box.get("boxes");
            level = nested;
            where = "box \"" + parts[i] + "\"";
        }
    }

    /** Adds a box, at the end or {@code before}/{@code after} a box id. */
    private static void add(List<Object> boxes, Object value, String context) throws ConfigException {
        if (!(value instanceof Map<?, ?> m)) {
            throw new ConfigException(context + ": expected a box, got " + YamlSpec.describe(value));
        }
        Map<String, Object> box = copyMap(m);
        Object before = box.remove("before");
        Object after = box.remove("after");
        if (before != null && after != null) {
            throw new ConfigException(context + ": use either before or after, not both");
        }
        Object anchor = before != null ? before : after;
        if (anchor == null) {
            boxes.add(box);
            return;
        }
        int index = indexOf(boxes, String.valueOf(anchor));
        if (index < 0) {
            throw new ConfigException(context + ": " + (before != null ? "before" : "after") + ": no box \"" + anchor
                    + "\"; the boxes are " + ids(boxes));
        }
        boxes.add(before != null ? index : index + 1, box);
    }

    /** A {@code stylesheet} read now (relative to {@code dir}), so it travels with the definition. */
    private static Map<String, Object> withStylesheet(Map<String, Object> layout, Path dir, String shown)
            throws IOException {
        Object stylesheet = layout.remove("stylesheet");
        if (stylesheet == null) {
            return layout;
        }
        Path file = dir.resolve(String.valueOf(stylesheet));
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(shown + ": stylesheet: not found: " + file);
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", String.valueOf(stylesheet));
        sheet.put("css", Files.readString(file, StandardCharsets.UTF_8));
        layout.put(READ_STYLESHEETS, new ArrayList<>(List.of(sheet)));
        return layout;
    }

    private static List<Object> boxes(Map<String, Object> layout, String context, String what) throws ConfigException {
        if (!(layout.get("boxes") instanceof List<?> list)) {
            throw new ConfigException(context + ": " + what + " has no list of boxes");
        }
        @SuppressWarnings("unchecked")
        List<Object> boxes = (List<Object>) list;
        return boxes;
    }

    private static int indexOf(List<Object> boxes, String id) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i) instanceof Map<?, ?> m && id.equals(String.valueOf(m.get("id")))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> ids(List<Object> boxes) {
        List<String> ids = new ArrayList<>();
        for (Object b : boxes) {
            if (b instanceof Map<?, ?> m) {
                ids.add(String.valueOf(m.get("id")));
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> boxMap(Object box) {
        return (Map<String, Object>) box;
    }

    /** Values replace, maps merge, null removes. */
    private static void merge(Map<String, Object> target, Map<?, ?> changes) {
        for (var e : changes.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (value == null) {
                target.remove(key);
            } else if (value instanceof Map<?, ?> m && target.get(key) instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inner = (Map<String, Object>) target.get(key);
                merge(inner, m);
            } else {
                target.put(key, copy(value));
            }
        }
    }

    private static Map<String, Object> map(Object yaml, String shown) throws ConfigException {
        if (!(yaml instanceof Map<?, ?> m)) {
            throw new ConfigException(shown + ": expected key: value pairs, got " + YamlSpec.describe(yaml));
        }
        return copyMap(m);
    }

    /** A deep, mutable copy of a parsed YAML mapping. */
    static Map<String, Object> copyMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
        return out;
    }

    /** A deep, mutable copy of parsed YAML. */
    @SuppressWarnings("unchecked")
    static <T> T copy(T value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
            return (T) out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(copy(v)));
            return (T) out;
        }
        return value;
    }
}
