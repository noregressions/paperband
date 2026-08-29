package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.model.Section;
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
 * {@code theme}, and {@code sections}; these don't cascade because they're
 * book-level concepts. {@code sections} declares titled groups of top-level
 * folders (see {@link Section}) — the declared counterpart to
 * folder-discovered sections — and takes two shapes: a bare list of section
 * declarations, or a map whose {@code declare:} key holds that list and whose
 * {@code landing.template} key sets the book-wide default landing page for
 * every section, declared or discovered (see
 * {@link BookConfig#sectionLandingTemplate()}); an individual section folder's
 * own {@code paperband.yaml} can override the landing template per-folder with
 * a {@code landing.template} key of the same shape. The {@code theme} value is
 * the default theme name; the plugin's {@code <theme>} parameter overrides it
 * when supplied.
 */
public final class ConfigLoader {
    /** Creates a new config loader. */
    public ConfigLoader() {}

    private static final String CONFIG_FILENAME = "paperband.yaml";

    /**
     * The book home for the current load — where the book-level yaml and its
     * relative asset paths live when the geography splits it from the content
     * root; null when the content root is its own home (every load before
     * the split, and every self-contained book after it).
     */
    private Path homeDir;

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
        return load(mdFile, target, size, margins, declaredRoot, null, declaredVars);
    }

    /**
     * Load and resolve config for {@code mdFile}, with the book's
     * <em>geography</em> split in two: {@code contentRoot} is where the cards
     * live (the cascade boundary, and the {@link BookConfig#bookRoot()} the
     * model reports — ids, section grouping and fragment resolution are
     * content concerns), while {@code home} is where the book's own assets
     * live — the {@code paperband.yaml} carrying the book-level config, whose
     * relative paths (css, cover images) resolve against <em>home</em>, not
     * the content root.
     *
     * <p>Resolution of the book-level yaml: {@code home/paperband.yaml} when
     * {@code home} is given and has one; else {@code contentRoot/paperband.yaml}
     * (the self-contained book, exactly as before); else empty (the book is
     * declared in the POM). The per-folder cascade between card and
     * {@code contentRoot} applies unchanged either way.
     *
     * @param mdFile      the card file
     * @param target      current build target; may be null
     * @param size        current page size; may be null
     * @param margins     margins replacing the size preset's own; may be null
     * @param contentRoot the content root; null to discover as usual
     * @param home        the book home; null to treat the content root as home
     * @param declaredVars book-level vars from the caller; may be empty
     * @return resolved {@link RenderContext}
     */
    public RenderContext load(Path mdFile, String target, String size, Margins margins,
                              Path contentRoot, Path home, Map<String, Object> declaredVars) {
        Path declaredRoot = contentRoot;
        this.homeDir = home == null ? null : home.toAbsolutePath().normalize();
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

        Path homeYaml = homeDir == null ? null : homeDir.resolve(CONFIG_FILENAME);
        boolean homeHasYaml = homeYaml != null && Files.isRegularFile(homeYaml);

        if (chain.isEmpty() && !homeHasYaml) {
            PageConfigResolver.Resolved page = PageConfigResolver.resolve(null, basePageSpec(size, margins));
            // With a declared root, an absent yaml is expected rather than a
            // fallback: the book is described somewhere else entirely.
            return new RenderContext(BookConfig.empty(root != null ? root : startDir),
                    List.of(), seedVars(extraVars), null, target, size,
                    page.pageSpec(), page.fontScale());
        }

        // Book root: declared when the caller knows it, otherwise the
        // directory of the topmost yaml found. The book-level config is read
        // from the home's yaml (split geography), else the root's own (a
        // self-contained book), else nowhere -- with a declared root that has
        // neither, the book carries no yaml-declared config at all, which is
        // the point: it's declared elsewhere. bookYamlUsed marks which chain
        // entry (if any) already supplied the book level, so the cascade
        // below skips exactly that one — not blindly the first, which under a
        // declared root with no root yaml is a FOLDER yaml that must apply.
        Path bookRoot;
        BookConfig book;
        Path bookYamlUsed = null;
        if (root != null) {
            bookRoot = root;
            Path rootYaml = root.resolve(CONFIG_FILENAME);
            if (homeHasYaml) {
                book = parseBookConfig(homeDir, homeYaml, root);
                bookYamlUsed = rootYaml.equals(homeYaml) ? rootYaml : null;
            } else if (Files.isRegularFile(rootYaml)) {
                book = parseBookConfig(root, rootYaml, root);
                bookYamlUsed = rootYaml;
            } else {
                book = BookConfig.empty(root);
            }
        } else {
            Path bookRootYaml = chain.peekFirst();
            bookRoot = bookRootYaml.getParent();
            book = parseBookConfig(bookRoot, bookRootYaml, bookRoot);
            bookYamlUsed = bookRootYaml;
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

        for (Path yaml : chain) {
            if (yaml.equals(bookYamlUsed)) continue;      // book level already applied
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
    /** Parse a book-level yaml: relative paths resolve against {@code bookRoot} (the yaml's home), the model reports {@code recordRoot} (the content root). */
    private BookConfig parseBookConfig(Path bookRoot, Path bookYaml, Path recordRoot) {
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

        // The root `sections:` key takes two shapes. A bare list declares
        // sections — titled groups of top-level folders, the declared
        // counterpart to folder-discovered sections. The map form carries
        // `landing: { template: <name-or-path> }`, the book-wide default
        // landing template for every section (a section's own folder
        // paperband.yaml can override it per-folder with the same shape — see
        // LayoutEngine, which reads that file directly since section grouping
        // happens after the whole book's cards are loaded, not during this
        // per-card cascade walk), and a `declare:` key holding the same list
        // the bare form takes, for books that need both at once. The landing
        // template can be a built-in preset name (e.g. "minimal") or a custom
        // template file path -- NamedTemplates tells them apart.
        String sectionLandingTemplate = null;
        Object declaredSectionsNode = null;
        Object sectionsNode = data.get("sections");
        if (sectionsNode instanceof List<?>) {
            declaredSectionsNode = sectionsNode;
        } else if (sectionsNode instanceof Map<?, ?> sm) {
            declaredSectionsNode = sm.get("declare");
            Object landingNode = sm.get("landing");
            if (landingNode instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) {
                    sectionLandingTemplate = NamedTemplates.resolveSectionTemplate(bookRoot, t.toString());
                }
            }
        } else if (sectionsNode != null) {
            throw new ConfigParseException(bookYaml + ": 'sections' must be a list of section "
                    + "declarations, or a map with 'landing' and/or 'declare' keys");
        }
        if (data.containsKey("parts")) {
            throw new ConfigParseException(bookYaml
                    + ": 'parts' has been renamed — declare the groups under 'sections:' "
                    + "(a bare list, or the 'declare:' key of the sections map)");
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

        // Optional declared sections: titled groups of top-level folders.
        // Book-level only -- a declared section describes the shape of the
        // whole book, and the folders it names are resolved relative to the
        // book root.
        List<Section> sections = parseSections(bookRoot, bookYaml, declaredSectionsNode);

        return new BookConfig(recordRoot, title, axes, globalCss, vars, targets, theme,
                sectionLandingTemplate, cardSchema, cover, back, footer, header, sections);
    }

    /**
     * Parse the book root {@code sections:} list into {@link Section} records.
     *
     * <p>Shape — a list of maps, each with a {@code title}, a {@code folders}
     * list (a bare string is tolerated as a one-folder list), an optional
     * explicit {@code id}, and an optional {@code landing: { template: ... }}
     * of the same shape as a section folder's own override:
     * <pre>
     * sections:
     *   - title: "Foundations"
     *     folders: [01-getting-started, 02-authoring]
     * </pre>
     *
     * <p>The id defaults to a slug of the title. Validation is structural and
     * strict, because a mistake here silently reshapes the whole book: a
     * declared section must have a title (or explicit id) and at least one
     * folder, ids must be unique, and no two declarations may claim the same
     * folder — an ambiguous folder has no single correct group to report.
     */
    private static List<Section> parseSections(Path bookRoot, Path bookYaml, Object node) {
        if (node == null) return List.of();
        if (!(node instanceof List<?> list)) {
            throw new ConfigParseException(bookYaml
                    + ": 'sections' must declare a list of sections");
        }
        List<Section> sections = new ArrayList<>();
        Map<String, String> folderOwner = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            if (!(item instanceof Map<?, ?> m)) {
                throw new ConfigParseException(bookYaml
                        + ": each 'sections' entry must be a mapping, got: " + item);
            }
            String sectionTitle = string(m.get("title"));
            String rawId = string(m.get("id"));
            String id = rawId != null && !rawId.isBlank() ? rawId.trim() : Section.slug(sectionTitle);
            if (id == null) {
                throw new ConfigParseException(bookYaml
                        + ": section declares neither a usable 'title' nor an 'id': " + m);
            }
            if (ids.contains(id)) {
                throw new ConfigParseException(bookYaml
                        + ": duplicate section id '" + id + "'");
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
                        + ": section '" + id + "' declares no 'folders'");
            }
            for (String f : folders) {
                String owner = folderOwner.putIfAbsent(f, id);
                if (owner != null) {
                    throw new ConfigParseException(bookYaml + ": folder '" + f
                            + "' is claimed by both section '" + owner + "' and section '" + id + "'");
                }
            }

            String landing = null;
            boolean landingPage = true;
            Object landingNode = m.get("landing");
            if (landingNode instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) {
                    landing = NamedTemplates.resolveSectionTemplate(bookRoot, t.toString());
                }
            } else if (landingNode instanceof Boolean b) {
                // `landing: false` opts the section out of its page entirely —
                // no PDF divider, no site landing page — while keeping the
                // grouping, the ordering, and the nav/sidebar label. Mirrors
                // the Maven plugin's <section><landingPage>false</landingPage>.
                landingPage = b;
            } else if (landingNode != null) {
                // A bare scalar was silently ignored before; say what the two
                // valid shapes are instead of dropping a probable template.
                throw new ConfigParseException(bookYaml + ": section '" + id
                        + "' has landing: " + landingNode + " — use 'landing: false' to skip "
                        + "the section's divider/landing page, or 'landing: { template: ... }' "
                        + "to restyle it");
            }
            sections.add(new Section(id, sectionTitle, folders, landing, List.of(), landingPage));
        }
        return List.copyOf(sections);
    }

    /**
     * Parse a {@code cover:} / {@code back:} node. A bare string is shorthand
     * for {@code image:}; the map form takes {@code image} and/or
     * {@code template} keys. The template path is reduced to the bare Pebble
     * loader name here (same convention as section landing templates), so
     * downstream consumers never re-derive it.
     */
    /** The keys a {@code cover:}/{@code back:}/{@code header:}/{@code footer:} map may carry. */
    private static final java.util.Set<String> PAGE_MATTER_KEYS = java.util.Set.of(
            "image", "template", "title", "subtitle", "series", "author", "text",
            "fullPage", "fullpage");

    private static PageMatter parsePageMatter(Path bookRoot, Path bookYaml, String key, Object node) {
        if (node == null) return null;
        if (node instanceof Map<?, ?> m) {
            // Unknown keys are an error, not a silent accept: a typo'd
            // 'subtile:' would otherwise just not appear on the cover.
            for (Object k : m.keySet()) {
                if (!PAGE_MATTER_KEYS.contains(String.valueOf(k))) {
                    throw new ConfigParseException(bookYaml + ": '" + key + "' has unknown key '"
                            + k + "' — expected one of " + PAGE_MATTER_KEYS);
                }
            }
            Object image = m.get("image");
            Object template = m.get("template");
            boolean fullPage = truthy(m.get("fullPage")) || truthy(m.get("fullpage"));
            if (fullPage && !"cover".equals(key)) {
                // CSS can address the first page (:first) but not the last,
                // so full-page is a cover-only capability — erroring beats a
                // flag that silently does nothing.
                throw new ConfigParseException(bookYaml + ": '" + key
                        + "' cannot declare fullPage — only 'cover' can fill the sheet"
                        + " (CSS has @page :first, but no :last)");
            }
            PageMatter matter = new PageMatter(
                    requireImage(bookRoot, bookYaml, key, image == null ? null : image.toString()),
                    template == null ? null
                            : NamedTemplates.templateName(template.toString()),
                    string(m.get("title")),
                    string(m.get("subtitle")),
                    string(m.get("series")),
                    string(m.get("author")),
                    truthy(m.get("text")),
                    fullPage);
            if (matter.isEmpty()) {
                throw new ConfigParseException(bookYaml + ": '" + key
                        + "' declares neither 'image', 'template' nor any text field");
            }
            return matter;
        }
        String image = node.toString();
        if (image.isBlank()) return null;
        return new PageMatter(requireImage(bookRoot, bookYaml, key, image), null);
    }

    /**
     * A declared image that isn't on disk would otherwise surface only as a
     * blank spot in the PDF — Chromium renders a missing {@code file:} URI
     * as nothing, without failing the build.
     */
    private static String requireImage(Path bookRoot, Path bookYaml, String key, String image) {
        if (image == null) return null;
        Path resolved = bookRoot.resolve(image);
        if (!Files.isRegularFile(resolved)) {
            throw new ConfigParseException(bookYaml + ": '" + key + "' image '" + image
                    + "' not found (looked at " + resolved + ")");
        }
        // Stored resolved, not as written: the rendered HTML's base URI is the
        // CONTENT root, and under a split geography the image lives under the
        // book home — a relative src would silently resolve to nothing there
        // (Chromium renders a dead file: URI as a blank), which is exactly the
        // failure the existence check above exists to prevent.
        return resolved.toAbsolutePath().normalize().toString();
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

        // dividers: false makes the axis label-only — no PDF divider pages,
        // so section dividers fire as if the axis were not declared. Card
        // classes, badges, landing pages and nav entries are unaffected.
        Object dividersNode = data.get("dividers");
        boolean dividers = dividersNode == null || truthy(dividersNode);

        return new Axis(name, title, values, landingTemplate, dividers);
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

    /** Yaml-flexible boolean: a real {@code true} or the string spelling of one. */
    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

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
