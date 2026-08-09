package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Publication;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the {@code publication:} block of a book root {@code pagewright.yaml}
 * into a {@link Publication} (see DESIGN-publications.md). Deliberately a
 * separate loader from {@link ConfigLoader}: the per-card config cascade and
 * the publication block answer different questions ({@code ConfigLoader}
 * resolves one card's context; this resolves the set of build outputs), and
 * keeping them apart means neither grows the other's concerns.
 *
 * <p>{@code --set} overrides (dotted paths, e.g. {@code defaults.theme=carded}
 * or {@code editions.batch-guide.size=A4}) are applied to the raw yaml map
 * after parse and before record construction, so they participate as the
 * topmost layer of the settings cascade. Editions are addressed by id, not
 * index. Unknown paths are an error, not a silent accept — the Helm lesson.
 * Values parse as YAML scalars so booleans and numbers keep their types.
 *
 * <p>Validation here is structural (ids present and unique, override paths
 * recognised); selection emptiness is a per-edition build-time failure, not
 * a parse failure.
 */
public final class PublicationLoader {

    /** Scalar leaf keys settable via --set, per level. vars.* and select.* pass through. */
    private static final Set<String> DEFAULTS_KEYS =
            Set.of("theme", "size", "output", "themeDir");
    private static final Set<String> EDITION_KEYS =
            Set.of("theme", "size", "output", "title");
    private static final Set<String> PAGES_KEYS =
            Set.of("report", "maxPerCard");
    /** select: keys that are structure, not field clauses. */
    private static final Set<String> SELECT_RESERVED = Set.of("cards", "where");

    private PublicationLoader() {}

    /** Load without overrides. */
    public static Optional<Publication> load(Path bookRootYaml) throws IOException {
        return load(bookRootYaml, List.of());
    }

    /**
     * Load the publication block from {@code bookRootYaml}, if declared,
     * applying {@code overrides} ({@code path=value} strings) first.
     *
     * @throws IllegalArgumentException on a structurally invalid block or an
     *         unrecognised override path
     */
    public static Optional<Publication> load(Path bookRootYaml, List<String> overrides)
            throws IOException {
        if (!Files.isRegularFile(bookRootYaml)) return Optional.empty();
        Map<String, Object> root;
        try (Reader r = Files.newBufferedReader(bookRootYaml)) {
            Object parsed = new Yaml().load(r);
            if (!(parsed instanceof Map<?, ?> m)) return Optional.empty();
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            root = cast;
        }
        Object pub = root.get("publication");
        if (!(pub instanceof Map<?, ?>)) return Optional.empty();
        @SuppressWarnings("unchecked")
        Map<String, Object> pubMap = deepMutable((Map<String, Object>) pub);

        for (String override : overrides == null ? List.<String>of() : overrides) {
            applyOverride(pubMap, override);
        }

        Publication.Defaults defaults = parseDefaults(asMap(pubMap.get("defaults")));
        List<Publication.Edition> editions = new ArrayList<>();
        if (pubMap.get("editions") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> e = asMap(o);
                if (e == null) continue;
                editions.add(parseEdition(e, bookRootYaml));
            }
        }

        // Structural validation: ids present (parseEdition enforces) and unique.
        List<String> seen = new ArrayList<>();
        for (Publication.Edition e : editions) {
            if (seen.contains(e.id())) {
                throw new IllegalArgumentException(
                        "publication: duplicate edition id '" + e.id() + "' in " + bookRootYaml);
            }
            seen.add(e.id());
        }
        return Optional.of(new Publication(defaults, editions));
    }

    // ── --set override application ──────────────────────────────────────────

    /**
     * Apply one {@code path=value} override onto the raw publication map.
     * Grammar: {@code defaults.<key>}, {@code defaults.vars.<name>},
     * {@code defaults.pages.<key>}, {@code editions.<id>.<key>},
     * {@code editions.<id>.vars.<name>}, {@code editions.<id>.select.<field>},
     * {@code editions.<id>.pages.<key>}.
     */
    private static void applyOverride(Map<String, Object> pub, String override) {
        int eq = override.indexOf('=');
        if (eq <= 0 || eq == override.length() - 1) {
            throw new IllegalArgumentException("Bad --set (expected path=value): " + override);
        }
        String path = override.substring(0, eq).trim();
        Object value = new Yaml().load(override.substring(eq + 1).trim());
        String[] seg = path.split("\\.");

        if (seg.length >= 2 && seg[0].equals("defaults")) {
            Map<String, Object> defaults = childMap(pub, "defaults");
            setLeaf(defaults, seg, 1, DEFAULTS_KEYS, value, path);
            return;
        }
        if (seg.length >= 3 && seg[0].equals("editions")) {
            Map<String, Object> edition = editionById(pub, seg[1], path);
            setLeaf(edition, seg, 2, EDITION_KEYS, value, path);
            return;
        }
        throw new IllegalArgumentException("Unknown --set path: " + path);
    }

    /** Set a validated leaf: bare scalar key, or vars.* / select.* / pages.* one level down. */
    private static void setLeaf(Map<String, Object> node, String[] seg, int i,
                                Set<String> scalarKeys, Object value, String path) {
        String key = seg[i];
        boolean last = i == seg.length - 1;
        if (last) {
            if (!scalarKeys.contains(key)) {
                throw new IllegalArgumentException("Unknown --set path: " + path
                        + " (settable keys here: " + scalarKeys + ", vars.*, select.*, pages.*)");
            }
            node.put(key, value);
            return;
        }
        // One nested level: vars.<name>, select.<field>, pages.<key>.
        if (i != seg.length - 2) {
            throw new IllegalArgumentException("Unknown --set path: " + path);
        }
        String nested = seg[i + 1];
        switch (key) {
            case "vars" -> childMap(node, "vars").put(nested, value);
            case "select" -> {
                if (SELECT_RESERVED.contains(nested)) {
                    throw new IllegalArgumentException("--set cannot address select."
                            + nested + " (list/predicate values belong in the yaml): " + path);
                }
                childMap(node, "select").put(nested, value);
            }
            case "pages" -> {
                if (!PAGES_KEYS.contains(nested)) {
                    throw new IllegalArgumentException("Unknown --set path: " + path
                            + " (pages keys: " + PAGES_KEYS + ")");
                }
                childMap(node, "pages").put(nested, value);
            }
            default -> throw new IllegalArgumentException("Unknown --set path: " + path);
        }
    }

    private static Map<String, Object> editionById(Map<String, Object> pub, String id, String path) {
        if (pub.get("editions") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> e = asMap(o);
                if (e != null && id.equals(str(e.get("id")))) return e;
            }
        }
        throw new IllegalArgumentException(
                "Unknown edition '" + id + "' in --set path: " + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> fresh = new LinkedHashMap<>();
        parent.put(key, fresh);
        return fresh;
    }

    /** SnakeYAML maps may be immutable views; copy so overrides can mutate. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutable(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), deepMutable((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                List<Object> copy = new ArrayList<>(l.size());
                for (Object o : l) {
                    copy.add(o instanceof Map<?, ?> m ? deepMutable((Map<String, Object>) m) : o);
                }
                out.put(e.getKey(), copy);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    private static Publication.Defaults parseDefaults(Map<String, Object> d) {
        if (d == null) return Publication.Defaults.empty();
        return new Publication.Defaults(
                str(d.get("theme")),
                str(d.get("size")),
                str(d.get("output")),
                str(d.get("themeDir")),
                asMap(d.get("vars")),
                parsePages(asMap(d.get("pages"))));
    }

    private static Publication.Edition parseEdition(Map<String, Object> e, Path source) {
        String id = str(e.get("id"));
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "publication: edition without an id in " + source);
        }
        return new Publication.Edition(
                id,
                strList(e.get("class")),
                str(e.get("title")),
                parseSelect(asMap(e.get("select"))),
                str(e.get("theme")),
                str(e.get("size")),
                str(e.get("output")),
                asMap(e.get("vars")),
                parsePages(asMap(e.get("pages"))));
    }

    /**
     * select: reserved keys {@code cards} (explicit id list) and {@code where}
     * (Pebble predicate); every other key is a frontmatter equality clause.
     * Clause values stringified so yaml numerics match.
     */
    private static Publication.Select parseSelect(Map<String, Object> raw) {
        if (raw == null) return Publication.Select.all();
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> en : raw.entrySet()) {
            if (SELECT_RESERVED.contains(en.getKey()) || en.getValue() == null) continue;
            fields.put(en.getKey(), String.valueOf(en.getValue()));
        }
        return new Publication.Select(fields, strList(raw.get("cards")), str(raw.get("where")));
    }

    private static Publication.Pages parsePages(Map<String, Object> raw) {
        if (raw == null) return Publication.Pages.empty();
        Object report = raw.get("report");
        Object max = raw.get("maxPerCard");
        return new Publication.Pages(
                report instanceof Boolean b ? b
                        : report != null ? Boolean.valueOf(String.valueOf(report)) : null,
                max instanceof Number n ? n.intValue()
                        : max != null ? Integer.valueOf(String.valueOf(max)) : null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Accepts a bare string or a list of strings. */
    private static List<String> strList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object x : l) if (x != null) out.add(String.valueOf(x));
            return out;
        }
        return List.of(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
