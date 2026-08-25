package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.model.Part;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.render.PageConfigResolver;
import dev.noregressions.paperband.render.PageSpec;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the {@code paperband.yaml} cascade for a target markdown file and
 * resolves it into a {@link BookConfig} plus a per-file {@link RenderContext}.
 *
 * <p>Discovery: starting at the file's directory, we walk parent-by-parent
 * collecting any {@code paperband.yaml} we find. The highest one in the
 * chain is treated as the book root.
 *
 * <p>Cascade rules:
 * <ul>
 *   <li>{@code css}: list, appended in book&rarr;leaf order. No replace.</li>
 *   <li>{@code vars}: map, merged with later entries overriding earlier.</li>
 *   <li>{@code axis}: map of axis-name &rarr; value bindings (e.g. {@code tier: 1}).
 *       Merged into {@code vars} so they're directly visible to predicates.
 *       Later (closer-to-leaf) bindings override earlier ones.</li>
 *   <li>{@code layout}: scalar path, last non-null wins.</li>
 *   <li>{@code targets}: list, last non-empty wins.</li>
 * </ul>
 *
 * <p>The book root may additionally declare {@code title}, {@code axes},
 * {@code theme}, {@code parts}, and {@code sections.landing.template}; these
 * don't cascade because they're book-level concepts. {@code parts} declares
 * titled groups of top-level folders (see {@link Part}) — the declared
 * counterpart to folder-discovered sections. The {@code theme} value is the default
 * theme name; the plugin's {@code <theme>} parameter overrides it when supplied. The
 * {@code sections.landing.template} value is the book-wide default landing
 * page for folder-based "sections" (see {@link BookConfig#sectionLandingTemplate()});
 * an individual section folder's own {@code paperband.yaml} can override it
 * per-folder with a {@code landing.template} key of the same shape.
 */
public final class ConfigLoader {
    /** Creates a new config loader. */
    public ConfigLoader() {}

    private static final String CONFIG_FILENAME = "paperband.yaml";

    /**
     * Load and resolve config for {@code mdFile}.
     *
     * @param mdFile  the markdown card file
     * @param target  current build target (e.g. {@code "pdf-a4"}); may be null
     * @param size    current page size (e.g. {@code "A4"}); may be null
     * @return resolved {@link RenderContext}; if no paperband.yaml is found
     *         anywhere up the tree, returns an empty context with {@code mdFile}'s
     *         parent treated as the book root
     */
    public RenderContext load(Path mdFile, String target, String size) {
        return load(mdFile, target, size, null);
    }

    /**
     * Load and resolve config for {@code mdFile}, with the page-size preset's
     * margins replaced before the yaml cascade runs.
     *
     * <p>{@code margins} sits exactly where {@code size} does: it seeds the
     * <em>base</em> geometry, so a {@code vars.page.margins} block in the
     * book still wins over it — same precedence a {@code vars.page.size}
     * block has over {@code size}. Its reason to exist is the build tool that
     * has no yaml of its own to edit: the Maven plugin's {@code <margins>},
     * which is how a book asks for a full-bleed render ({@code 0}) from the
     * POM.
     *
     * @param mdFile  the markdown card file
     * @param target  current build target (e.g. {@code "pdf-a4"}); may be null
     * @param size    current page size (e.g. {@code "A4"}); may be null
     * @param margins margins to use in place of the size preset's own; null
     *                keeps the preset's
     * @return resolved {@link RenderContext}
     */
    public RenderContext load(Path mdFile, String target, String size, Margins margins) {
        return load(mdFile, target, size, margins, null);
    }

    /**
     * Load and resolve config for {@code mdFile}, with the book root declared
     * rather than discovered.
     *
     * <p>Normally the book root is the directory of the <em>topmost</em>
     * {@code paperband.yaml} above the card. That inference has nowhere to go
     * when a book has no yaml at all — a book whose structure and config are
     * declared in a build tool instead (the Maven plugin's {@code <book>}) —
     * and it silently picks the card's own parent directory, so every card
     * ends up in a different "book". Passing the root fixes it at the one
     * directory that is actually the root:
     *
     * <ul>
     *   <li>the book root is {@code declaredRoot}, whether or not a yaml sits
     *       there;</li>
     *   <li>the book-level config comes from {@code declaredRoot/paperband.yaml}
     *       when that exists, and is empty when it doesn't;</li>
     *   <li>the cascade still collects the yamls <em>between</em> root and card,
     *       so per-folder css/vars/layout keep working — it just stops at the
     *       root instead of walking to the filesystem root.</li>
     * </ul>
     *
     * @param mdFile       the markdown card file
     * @param target       current build target; may be null
     * @param size         current page size; may be null
     * @param margins      margins replacing the size preset's own; may be null
     * @param declaredRoot the book root; null to discover it as usual
     * @return resolved {@link RenderContext}
     */
    public RenderContext load(Path mdFile, String target, String size, Margins margins,
                              Path declaredRoot) {
        return load(mdFile, target, size, margins, declaredRoot, Map.of());
    }

    /**
     * Load and resolve config for {@code mdFile}, with the book root declared
     * and extra book-level vars supplied by the caller.
     *
     * <p>{@code declaredVars} enter the cascade exactly where the root yaml's
     * own {@code vars:} would — above the built-ins, below any folder-level
     * override. That placement is the point: a build-declared var
     * ({@code <book><vars>}) has to reach every card's template context, the
     * way {@code author} or {@code subtitle} does, and it has to remain
     * overridable per folder like any other book var. Merging it into the book
     * config after the fact would do neither.
     *
     * @param mdFile       the markdown card file
     * @param target       current build target; may be null
     * @param size         current page size; may be null
     * @param margins      margins replacing the size preset's own; may be null
     * @param declaredRoot the book root; null to discover it as usual
     * @param declaredVars book-level vars from the caller; may be empty
     * @return resolved {@link RenderContext}
     */
    public RenderContext load(Path mdFile, String target, String size, Margins margins,
                              Path declaredRoot, Map<String, Object> declaredVars) {
        Map<String, Object> extraVars = declaredVars == null ? Map.of() : declaredVars;
        Path startDir = mdFile.toAbsolutePath().getParent();
        Path root = declaredRoot == null ? null : declaredRoot.toAbsolutePath().normalize();
        if (startDir == null) {
            PageConfigResolver.Resolved page = PageConfigResolver.resolve(null, basePageSpec(size, margins));
            return new RenderContext(BookConfig.empty(mdFile.toAbsolutePath()),
                    List.of(), seedVars(extraVars), null, target, size,
                    page.pageSpec(), page.fontScale());
        }

        // Walk parents, collecting yamls. Order: leaf-first. A declared root
        // is where the walk stops -- above it is somebody else's book.
        Deque<Path> chain = new ArrayDeque<>();
        Path dir = startDir;
        while (dir != null) {
            Path yaml = dir.resolve(CONFIG_FILENAME);
            if (Files.isRegularFile(yaml)) {
                chain.addFirst(yaml);                 // book-first ordering
            }
            if (root != null && dir.toAbsolutePath().normalize().equals(root)) break;
            dir = dir.getParent();
        }

        if (chain.isEmpty()) {
            PageConfigResolver.Resolved page = PageConfigResolver.resolve(null, basePageSpec(size, margins));
            // With a declared root, an absent yaml is expected rather than a
            // fallback: the book is described somewhere else entirely.
            return new RenderContext(BookConfig.empty(root != null ? root : startDir),
                    List.of(), seedVars(extraVars), null, target, size,
                    page.pageSpec(), page.fontScale());
        }

        // Book root: declared when the caller knows it, otherwise the
        // directory of the topmost yaml found. The book-level config is read
        // from the root's own yaml -- with a declared root that has none, the
        // book carries no yaml-declared config at all, which is the point:
        // it's declared elsewhere.
        Path bookRoot;
        BookConfig book;
        if (root != null) {
            bookRoot = root;
            Path rootYaml = root.resolve(CONFIG_FILENAME);
            book = Files.isRegularFile(rootYaml)
                    ? parseBookConfig(root, rootYaml)
                    : BookConfig.empty(root);
        } else {
            Path bookRootYaml = chain.peekFirst();
            bookRoot = bookRootYaml.getParent();
            book = parseBookConfig(bookRoot, bookRootYaml);
        }

        // Cascade resolution: walk yamls book-first → file-closest.
        List<Path> cssChain = new ArrayList<>(book.globalCss());
        // Built-in vars seeded first so user yaml at any cascade level can
        // override (e.g. pin build_date in the book root yaml for reproducibility).
        Map<String, Object> vars = new LinkedHashMap<>(BuiltInVars.compute());
        vars.putAll(book.vars());
        vars.putAll(extraVars);        // build-declared book vars sit with the book's own
        Path layout = null;
        List<String> targets = new ArrayList<>(book.targets());

        boolean first = true;
        for (Path yaml : chain) {
            if (first) { first = false; continue; }       // book root already applied
            Map<String, Object> data = readYaml(yaml);
            cssChain.addAll(resolveCssPaths(yaml.getParent(), data.get("css")));
            Object varsNode = data.get("vars");
            if (varsNode instanceof Map<?, ?> vm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) vm;
                vars.putAll(typed);
            }
            // Axis bindings (e.g. `axis: { tier: 1 }`) merge into vars so
            // predicates can refer to them as plain variables.
            Object axisNode = data.get("axis");
            if (axisNode instanceof Map<?, ?> am) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) am;
                vars.putAll(typed);
            }
            Object layoutNode = data.get("layout");
            if (layoutNode != null) {
                layout = yaml.getParent().resolve(layoutNode.toString());
            }
            Object targetsNode = data.get("targets");
            if (targetsNode instanceof List<?> ll && !ll.isEmpty()) {
                targets = ll.stream().map(Object::toString).toList();
                targets = new ArrayList<>(targets);
            }
        }

        // Resolve final page geometry: the plugin's <pageSize> picks the base preset;
        // a `vars.page` yaml block (book root or any folder in the cascade,
        // last-wins per field — same shallow-override semantics as the rest
        // of `vars`) can override size/margins/orientation/fontScale on top.
        // See PageConfigResolver for why fontScale is nullable.
        @SuppressWarnings("unchecked")
        Map<String, Object> pageNode = vars.get("page") instanceof Map<?, ?> pm
                ? (Map<String, Object>) pm
                : null;
        PageConfigResolver.Resolved page = PageConfigResolver.resolve(pageNode, basePageSpec(size, margins));

        return new RenderContext(book, cssChain, vars, layout, target, size, page.pageSpec(), page.fontScale());
    }

    // ---- internals ----

    /** Built-in vars plus whatever the caller declared, for the no-yaml paths. */
    private static Map<String, Object> seedVars(Map<String, Object> declaredVars) {
        Map<String, Object> vars = new LinkedHashMap<>(BuiltInVars.compute());
        vars.putAll(declaredVars);
        return vars;
    }

    /**
     * The page-size preset the yaml cascade layers on, with its margins
     * swapped out when the caller supplied their own.
     */
    private static PageSpec basePageSpec(String size, Margins margins) {
        PageSpec base = PageSpec.forSizeName(size);
        return margins == null
                ? base
                : new PageSpec(base.size(), margins, base.orientation());
    }

    @SuppressWarnings("unchecked")
    private BookConfig parseBookConfig(Path bookRoot, Path bookYaml) {
        Map<String, Object> data = readYaml(bookYaml);
        String title = data.get("title") == null ? null : data.get("title").toString();

        List<Axis> axes = new ArrayList<>();
        Object axesNode = data.get("axes");
        if (axesNode instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    axes.add(parseAxis(bookRoot, (Map<String, Object>) m));
                }
            }
        }

        List<Path> globalCss = resolveCssPaths(bookRoot, data.get("css"));
        Map<String, Object> vars = data.get("vars") instanceof Map<?, ?> vm
                ? (Map<String, Object>) vm
                : Map.of();
        List<String> targets = data.get("targets") instanceof List<?> tl
                ? tl.stream().map(Object::toString).toList()
                : List.of();
        String theme = data.get("theme") == null ? null : data.get("theme").toString();

        // Book-wide default landing template for "sections" (folders of cards
        // with no value on any declared axis). A section's own folder
        // paperband.yaml can override this per-folder with the same
        // `landing: { template: <name-or-path> }` shape (see LayoutEngine,
        // which reads that file directly since section grouping happens after
        // the whole book's cards are loaded, not during this per-card cascade
        // walk). Either value can be a built-in preset name (e.g. "minimal")
        // or a custom template file path -- NamedTemplates tells them apart.
        String sectionLandingTemplate = null;
        Object sectionsNode = data.get("sections");
        if (sectionsNode instanceof Map<?, ?> sm) {
            Object landingNode = sm.get("landing");
            if (landingNode instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) {
                    sectionLandingTemplate = NamedTemplates.resolveSectionTemplate(bookRoot, t.toString());
                }
            }
        }

        // Optional YAML-card schema: lets *.yaml files in the book be
        // transpiled into markdown cards at load time (see CardSchema).
        // Book-level only; it deliberately doesn't cascade because the schema
        // describes a file format, not per-folder presentation.
        CardSchema cardSchema = null;
        Object schemaNode = data.get("cardSchema");
        if (schemaNode != null) {
            try {
                cardSchema = CardSchema.fromYaml(schemaNode);
            } catch (IllegalArgumentException e) {
                throw new ConfigParseException(bookYaml + ": " + e.getMessage(), e);
            }
        }

        // Optional front-cover / back-page / running-header / running-footer
        // declarations. Book-level only. header/footer reuse the same
        // PageMatter shape as cover/back (image field unused — a header or
        // footer is always a template).
        PageMatter cover  = parsePageMatter(bookRoot, bookYaml, "cover",  data.get("cover"));
        PageMatter back   = parsePageMatter(bookRoot, bookYaml, "back",  data.get("back"));
        PageMatter footer = parsePageMatter(bookRoot, bookYaml, "footer", data.get("footer"));
        PageMatter header = parsePageMatter(bookRoot, bookYaml, "header", data.get("header"));

        // Optional declared parts: titled groups of top-level folders. Book-level
        // only -- a part describes the shape of the whole book, and the folders
        // it names are resolved relative to the book root.
        List<Part> parts = parseParts(bookRoot, bookYaml, data.get("parts"));

        return new BookConfig(bookRoot, title, axes, globalCss, vars, targets, theme,
                sectionLandingTemplate, cardSchema, cover, back, footer, header, parts);
    }

    /**
     * Parse the book root {@code parts:} list into {@link Part} records.
     *
     * <p>Shape — a list of maps, each with a {@code title}, a {@code folders}
     * list (a bare string is tolerated as a one-folder list), an optional
     * explicit {@code id}, and an optional {@code landing: { template: ... }}
     * of the same shape as a section folder's own override:
     * <pre>
     * parts:
     *   - title: "Foundations"
     *     folders: [01-getting-started, 02-authoring]
     * </pre>
     *
     * <p>The id defaults to a slug of the title. Validation is structural and
     * strict, because a mistake here silently reshapes the whole book: a part
     * must have a title (or explicit id) and at least one folder, ids must be
     * unique, and no two parts may claim the same folder — an ambiguous
     * folder has no single correct group to report.
     */
    private static List<Part> parseParts(Path bookRoot, Path bookYaml, Object node) {
        if (node == null) return List.of();
        if (!(node instanceof List<?> list)) {
            throw new ConfigParseException(bookYaml + ": 'parts' must be a list of parts");
        }
        List<Part> parts = new ArrayList<>();
        Map<String, String> folderOwner = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            if (!(item instanceof Map<?, ?> m)) {
                throw new ConfigParseException(bookYaml
                        + ": each 'parts' entry must be a mapping, got: " + item);
            }
            String partTitle = string(m.get("title"));
            String rawId = string(m.get("id"));
            String id = rawId != null && !rawId.isBlank() ? rawId.trim() : Part.slug(partTitle);
            if (id == null) {
                throw new ConfigParseException(bookYaml
                        + ": part declares neither a usable 'title' nor an 'id': " + m);
            }
            if (ids.contains(id)) {
                throw new ConfigParseException(bookYaml
                        + ": duplicate part id '" + id + "'");
            }
            ids.add(id);

            List<String> folders = new ArrayList<>();
            Object foldersNode = m.get("folders");
            if (foldersNode instanceof List<?> fl) {
                for (Object f : fl) {
                    if (f != null && !f.toString().isBlank()) folders.add(f.toString().trim());
                }
            } else if (foldersNode != null && !foldersNode.toString().isBlank()) {
                folders.add(foldersNode.toString().trim());
            }
            if (folders.isEmpty()) {
                throw new ConfigParseException(bookYaml
                        + ": part '" + id + "' declares no 'folders'");
            }
            for (String f : folders) {
                String owner = folderOwner.putIfAbsent(f, id);
                if (owner != null) {
                    throw new ConfigParseException(bookYaml + ": folder '" + f
                            + "' is claimed by both part '" + owner + "' and part '" + id + "'");
                }
            }

            String landing = null;
            Object landingNode = m.get("landing");
            if (landingNode instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) {
                    landing = NamedTemplates.resolveSectionTemplate(bookRoot, t.toString());
                }
            }
            parts.add(new Part(id, partTitle, folders, landing));
        }
        return List.copyOf(parts);
    }

    /**
     * Parse a {@code cover:} / {@code back:} node. A bare string is shorthand
     * for {@code image:}; the map form takes {@code image} and/or
     * {@code template} keys. The template path is reduced to the bare Pebble
     * loader name here (same convention as section landing templates), so
     * downstream consumers never re-derive it.
     */
    private static PageMatter parsePageMatter(Path bookRoot, Path bookYaml, String key, Object node) {
        if (node == null) return null;
        if (node instanceof Map<?, ?> m) {
            Object image = m.get("image");
            Object template = m.get("template");
            PageMatter matter = new PageMatter(
                    image == null ? null : image.toString(),
                    template == null ? null
                            : NamedTemplates.templateName(template.toString()));
            if (matter.isEmpty()) {
                throw new ConfigParseException(bookYaml + ": '" + key
                        + "' declares neither 'image' nor 'template'");
            }
            return matter;
        }
        String image = node.toString();
        if (image.isBlank()) return null;
        return new PageMatter(image, null);
    }

    @SuppressWarnings("unchecked")
    private Axis parseAxis(Path bookRoot, Map<String, Object> data) {
        String name  = string(data.get("name"));
        String title = string(data.get("title"));
        if (name == null) {
            throw new ConfigParseException("axis missing required 'name'");
        }

        List<AxisValue> values = new ArrayList<>();
        Object valuesNode = data.get("values");
        if (valuesNode instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> v = (Map<String, Object>) m;
                    Object id    = v.get("id");
                    String label = string(v.get("label"));
                    Map<String, Object> meta = new LinkedHashMap<>(v);
                    meta.remove("id");
                    meta.remove("label");
                    values.add(new AxisValue(id, label, meta));
                }
            }
        }

        Path landingTemplate = null;
        Object landingNode = data.get("landing");
        if (landingNode instanceof Map<?, ?> lm) {
            Object t = lm.get("template");
            if (t != null) {
                landingTemplate = bookRoot.resolve(t.toString());
            }
        }

        return new Axis(name, title, values, landingTemplate);
    }

    private static List<Path> resolveCssPaths(Path baseDir, Object cssNode) {
        if (cssNode == null) return List.of();
        List<Path> out = new ArrayList<>();
        if (cssNode instanceof List<?> list) {
            for (Object item : list) {
                out.add(baseDir.resolve(item.toString()));
            }
        } else {
            out.add(baseDir.resolve(cssNode.toString()));
        }
        return out;
    }

    private static String string(Object o) { return o == null ? null : o.toString(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path yaml) {
        try (Reader r = Files.newBufferedReader(yaml)) {
            Object data = new Yaml().load(r);
            if (data == null) return Map.of();
            if (!(data instanceof Map<?, ?>)) {
                throw new ConfigParseException(
                        yaml + ": top level must be a YAML mapping");
            }
            return (Map<String, Object>) data;
        } catch (IOException e) {
            throw new ConfigParseException("Failed to read " + yaml, e);
        } catch (RuntimeException e) {
            throw new ConfigParseException("Failed to parse " + yaml + ": " + e.getMessage(), e);
        }
    }
}
