package dev.noregressions.paperband.layout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The layouts available to a build: every {@code .yml}/{@code .yaml} file in the layouts
 * directory, named after the file, plus the built-in {@code default} (one box holding the whole
 * file), which {@code layouts/default.yml} replaces if present.
 * <p>
 * References between layouts ({@code layout: name} in a box) are checked when loading: every
 * name must exist, and a layout can't include itself, directly or through others.
 * {@code page} is reserved: in {@code layout: page} it means the file's own layout.
 * <p>
 * Inline layouts, written in a markdown file's front matter, are added with
 * {@link #withInline}; each is named after its file ({@link #inlineName}).
 * <p>
 * Any layout, file or inline, can {@code extends} another and change only some of its boxes
 * (see {@link LayoutExtension}); the merged result is checked like any layout.
 */
public final class Layouts {

    /** What {@link #source} shows for the built-in default layout. */
    static final String DEFAULT_SOURCE = """
            # The built-in default layout: the whole file in one unwrapped box.
            # Add layouts/default.yml to replace it.
            boxes:
              - { id: content, select: rest, wrap: false }
            """;

    /** The built-in default layout as a definition, so other layouts can extend it. */
    private static final LayoutExtension.Raw DEFAULT_RAW = new LayoutExtension.Raw(
            Map.of("boxes", List.of(Map.of("id", "content", "select", "rest", "wrap", false))), Path.of(""), "default");

    private final Map<String, Layout> byName;
    private final Map<String, String> sources;
    private final Map<String, LayoutExtension.Raw> raws;

    private Layouts(Map<String, Layout> byName, Map<String, String> sources, Map<String, LayoutExtension.Raw> raws) {
        this.byName = byName;
        this.sources = sources;
        this.raws = raws;
    }

    public static Layouts builtIn() {
        return new Layouts(Map.of(Layout.DEFAULT.name(), Layout.DEFAULT), Map.of(Layout.DEFAULT.name(), DEFAULT_SOURCE),
                Map.of(Layout.DEFAULT.name(), DEFAULT_RAW));
    }

    /** A missing directory means only the built-in layout. */
    public static Layouts load(Path dir, PageSpec defaultPage) throws IOException {
        Map<String, Layout> byName = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, LayoutExtension.Raw> raws = new LinkedHashMap<>();
        byName.put(Layout.DEFAULT.name(), Layout.DEFAULT);
        sources.put(Layout.DEFAULT.name(), DEFAULT_SOURCE);
        raws.put(Layout.DEFAULT.name(), DEFAULT_RAW);
        if (!Files.isDirectory(dir)) {
            return new Layouts(byName, sources, raws);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches(".+\\.ya?ml")).sorted().toList();
        }
        // Read every definition first: a layout can extend any other, in any order.
        Map<String, Path> seen = new LinkedHashMap<>();
        for (Path f : files) {
            String fileName = f.getFileName().toString();
            String name = fileName.substring(0, fileName.lastIndexOf('.'));
            if (name.equals(Layout.PAGE_LAYOUT)) {
                throw new ConfigException(f + ": \"page\" is reserved (layout: page means a file's own layout); rename it");
            }
            Path other = seen.put(name, f);
            if (other != null) {
                throw new ConfigException("layout \"" + name + "\" is defined twice: " + other + " and " + f);
            }
            String text = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            Object yaml;
            try {
                yaml = dev.noregressions.paperband.FrontMatter.parseYaml(text);
            } catch (RuntimeException e) {
                throw new ConfigException(fileName + ": invalid YAML: " + e.getMessage(), e);
            }
            raws.put(name, new LayoutExtension.Raw(yaml, f.toAbsolutePath().getParent(), fileName));
            sources.put(name, text);
        }
        for (String name : seen.keySet()) {
            byName.put(name, build(name, raws.get(name), raws, defaultPage));
        }
        Layouts layouts = new Layouts(byName, sources, raws);
        layouts.checkReferences();
        return layouts;
    }

    /** One definition with everything it extends merged in, checked as a layout. */
    private static Layout build(String name, LayoutExtension.Raw raw, Map<String, LayoutExtension.Raw> raws,
                                PageSpec defaultPage) throws IOException {
        Map<String, Object> merged = LayoutExtension.resolve(name, raw, raws);
        String shown = raw.yaml() instanceof Map<?, ?> m && m.get("extends") instanceof String base
                ? raw.shown() + " (extends " + base + ")" : raw.shown();
        return Layout.fromYaml(merged, name, raw.dir(), shown, defaultPage);
    }

    private void checkReferences() throws ConfigException {
        for (Layout layout : byName.values()) {
            for (String ref : layout.references()) {
                if (!byName.containsKey(ref)) {
                    throw new ConfigException(layout.name() + ": unknown layout \"" + ref + "\"; available: "
                            + byName.keySet().stream().sorted().toList());
                }
            }
        }
        for (String start : byName.keySet()) {
            findCycle(start, new java.util.ArrayList<>());
        }
        for (Layout layout : byName.values()) {
            for (String fallback : layout.fallbacks()) {
                if (!byName.containsKey(fallback)) {
                    throw new ConfigException(layout.name() + ": fit: layout: unknown layout \"" + fallback
                            + "\"; available: " + byName.keySet().stream().sorted().toList());
                }
            }
        }
        checkContinuations();
    }

    /** A splitting box's continuation layout must exist and have a matching box to take the rest. */
    private void checkContinuations() throws ConfigException {
        for (Layout layout : byName.values()) {
            Layout.Box box = layout.splitting();
            if (box == null) {
                continue;
            }
            Layout.Split split = box.rules().split();
            String where = layout.name() + ": box \"" + box.id() + "\" split";
            Layout target = byName.get(split.continueIn(layout));
            if (target == null) {
                throw new ConfigException(where + ": continue: unknown layout \"" + split.continueLayout()
                        + "\"; available: " + byName.keySet().stream().sorted().toList());
            }
            String into = split.intoBox(box);
            Layout.Box receiver = target.boxes().stream().filter(b -> b.id().equals(into)).findFirst().orElse(null);
            String in = target == layout ? "this layout" : "layout \"" + target.name() + "\"";
            if (receiver == null) {
                throw new ConfigException(where + ": into: " + in + " has no box \"" + into + "\" to take the rest;"
                        + " its boxes are " + target.boxes().stream().map(Layout.Box::id).toList());
            }
            boolean each = box.kind() == Layout.Kind.EACH;
            boolean fits = each ? receiver.kind() == Layout.Kind.EACH
                    : receiver.kind() == Layout.Kind.SELECT || receiver.kind() == Layout.Kind.REST;
            if (!fits) {
                throw new ConfigException(where + ": into: box \"" + into + "\" in " + in + " can't take the rest of "
                        + (each ? "an each box; it must be an each box too" : "a " + box.kind().name().toLowerCase()
                        + " box; it must be a select or rest box"));
            }
        }
    }

    private void findCycle(String name, List<String> path) throws ConfigException {
        if (path.contains(name)) {
            List<String> cycle = new java.util.ArrayList<>(path.subList(path.indexOf(name), path.size()));
            cycle.add(name);
            throw new ConfigException("layouts include each other in a loop: " + String.join(" > ", cycle));
        }
        path.add(name);
        for (String ref : byName.get(name).references()) {
            findCycle(ref, path);
        }
        path.removeLast();
    }

    /** The name given to the inline layout in {@code file}'s front matter, e.g. {@code inline-docs-a-md}. */
    public static String inlineName(Path file) {
        return "inline-" + LayoutEngine.anchor(file.normalize().toString().replace('\\', '/'));
    }

    /**
     * These layouts plus the inline layout of every file whose front matter {@code layout:} is a
     * layout definition rather than a name. References are checked again across them all.
     *
     * @param frontMatter each source's front matter, by path relative to {@code dir}
     * @param dir         the directory source paths are relative to
     */
    public Layouts withInline(Map<Path, Map<String, Object>> frontMatter, Path dir, PageSpec defaultPage)
            throws IOException {
        Map<String, Layout> all = new LinkedHashMap<>(byName);
        Map<String, String> allSources = new LinkedHashMap<>(sources);
        Map<String, LayoutExtension.Raw> allRaws = new LinkedHashMap<>(raws);
        for (var e : frontMatter.entrySet()) {
            Object definition = e.getValue().get("layout");
            if (!(definition instanceof Map)) {
                continue;
            }
            String name = inlineName(e.getKey());
            if (all.containsKey(name)) {
                throw new ConfigException(e.getKey() + ": its inline layout's name \"" + name
                        + "\" is already a layout's name; rename that layout");
            }
            LayoutExtension.Raw raw = new LayoutExtension.Raw(definition,
                    dir.resolve(e.getKey()).toAbsolutePath().getParent(), e.getKey() + " front matter layout");
            all.put(name, build(name, raw, raws, defaultPage));
            allRaws.put(name, raw);
            allSources.put(name, "# inline layout, from the front matter of " + e.getKey() + "\n"
                    + dump(Map.of("layout", definition)));
        }
        if (all.size() == byName.size()) {
            return this;
        }
        Layouts layouts = new Layouts(all, allSources, allRaws);
        layouts.checkReferences();
        return layouts;
    }

    /** YAML text for a parsed value, in block style. */
    private static String dump(Object value) {
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        return new org.yaml.snakeyaml.Yaml(options).dump(plain(value));
    }

    /** Copies into plain maps and lists, so the dump has no Java type tags. */
    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(Layouts::plain).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        }
        return value;
    }

    /** A layout's file as written (for the built-in default, a short description of it). */
    public String source(String name) {
        String source = sources.get(name);
        if (source == null) {
            throw new IllegalArgumentException("unknown layout \"" + name + "\"");
        }
        return source;
    }

    public Set<String> names() {
        return byName.keySet();
    }

    public Layout get(String name) {
        Layout layout = byName.get(name);
        if (layout == null) {
            throw new IllegalArgumentException("unknown layout \"" + name + "\"");
        }
        return layout;
    }
}
