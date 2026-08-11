package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.model.RenderContext;
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
 * {@code theme}, and {@code sections.landing.template}; these don't cascade
 * because they're book-level concepts. The {@code theme} value is the default
 * theme name; the {@code --theme} CLI flag overrides it when supplied. The
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
        Path startDir = mdFile.toAbsolutePath().getParent();
        if (startDir == null) {
            PageConfigResolver.Resolved page = PageConfigResolver.resolve(null, PageSpec.forSizeName(size));
            return new RenderContext(BookConfig.empty(mdFile.toAbsolutePath()),
                    List.of(), Map.of(), null, target, size, page.pageSpec(), page.fontScale());
        }

        // Walk parents, collecting yamls. Order: leaf-first.
        Deque<Path> chain = new ArrayDeque<>();
        Path dir = startDir;
        while (dir != null) {
            Path yaml = dir.resolve(CONFIG_FILENAME);
            if (Files.isRegularFile(yaml)) {
                chain.addFirst(yaml);                 // book-first ordering
            }
            dir = dir.getParent();
        }

        if (chain.isEmpty()) {
            PageConfigResolver.Resolved page = PageConfigResolver.resolve(null, PageSpec.forSizeName(size));
            return new RenderContext(BookConfig.empty(startDir),
                    List.of(), Map.of(), null, target, size, page.pageSpec(), page.fontScale());
        }

        // First yaml in chain = book root.
        Path bookRootYaml = chain.peekFirst();
        Path bookRoot = bookRootYaml.getParent();

        BookConfig book = parseBookConfig(bookRoot, bookRootYaml);

        // Cascade resolution: walk yamls book-first → file-closest.
        List<Path> cssChain = new ArrayList<>(book.globalCss());
        // Built-in vars seeded first so user yaml at any cascade level can
        // override (e.g. pin build_date in the book root yaml for reproducibility).
        Map<String, Object> vars = new LinkedHashMap<>(BuiltInVars.compute());
        vars.putAll(book.vars());
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

        // Resolve final page geometry: CLI --page-size picks the base preset;
        // a `vars.page` yaml block (book root or any folder in the cascade,
        // last-wins per field — same shallow-override semantics as the rest
        // of `vars`) can override size/margins/orientation/fontScale on top.
        // See PageConfigResolver for why fontScale is nullable.
        @SuppressWarnings("unchecked")
        Map<String, Object> pageNode = vars.get("page") instanceof Map<?, ?> pm
                ? (Map<String, Object>) pm
                : null;
        PageConfigResolver.Resolved page = PageConfigResolver.resolve(pageNode, PageSpec.forSizeName(size));

        return new RenderContext(book, cssChain, vars, layout, target, size, page.pageSpec(), page.fontScale());
    }

    // ---- internals ----

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

        return new BookConfig(bookRoot, title, axes, globalCss, vars, targets, theme,
                sectionLandingTemplate, cardSchema, cover, back, footer, header);
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
                            : NamedTemplates.bareTemplateName(bookRoot.resolve(template.toString())));
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
