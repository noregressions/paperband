package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.loader.DelegatingLoader;
import io.pebbletemplates.pebble.loader.FileLoader;
import io.pebbletemplates.pebble.loader.Loader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.Part;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.pebble.LenientMap;
import dev.noregressions.paperband.pebble.LenientMapExtension;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pebble-driven layout engine. Takes a {@link Card} and {@link RenderContext}
 * and produces a complete HTML page ready to feed into any
 * {@code HtmlToPdfRenderer}.
 *
 * <h2>Template resolution</h2>
 * <ol>
 *   <li>If a {@link ThemeBundle} is supplied and provides a template loader,
 *       theme overrides are tried first.</li>
 *   <li>If the book root contains a {@code layouts/} directory, that's the
 *       next lookup root.</li>
 *   <li>Otherwise (or as fallback), templates are loaded from the classpath
 *       under {@code /templates/}, where the bundled defaults live.</li>
 * </ol>
 *
 * <h2>Layout name resolution</h2>
 * <p>If {@link RenderContext#layout()} is non-null its filename
 * (without extension) is used. Otherwise the default layout name {@value #DEFAULT_LAYOUT}
 * is used, which resolves to the bundled {@code templates/card.html}.
 *
 * <h2>CSS</h2>
 * <p>The CSS chain from {@link RenderContext#cssChain()} is read and inlined
 * into the page as a single concatenated stylesheet, exposed to templates as
 * the {@code css} variable. Theme stylesheets (if any) are appended after the
 * user chain so they win on cascade order. Missing files are noted as CSS
 * comments rather than causing a hard failure — useful while a book's
 * stylesheets are still being authored.
 *
 * <h2>Axes</h2>
 * <p>Every axis declared in the book's {@code paperband.yaml} with at least
 * one declared value gets full structural treatment automatically — grouping,
 * site landing pages, PDF dividers, nav/sidebar entries, and a
 * {@code {axisName}-{valueId}} CSS class on each card — independently of every
 * other axis. There is no single hardcoded "primary" axis; see
 * {@link #resolveAxisValue} for the one rule used everywhere a card's value
 * along an axis is needed.
 */
public final class LayoutEngine {

    /** Default layout name used when {@link RenderContext#layout()} is null. */
    public static final String DEFAULT_LAYOUT = "card";

    private final PebbleEngine engine;
    private final ThemeBundle theme;
    /** Kept for diagnostics: naming the layouts dir a missing template should have been in. */
    private final Path bookRoot;

    /**
     * Optional edition identity for publication builds:
     * a map of {id, classes, title, vars} exposed to book templates as
     * {@code edition} and stamped onto {@code <html>} as {@code edition-{id}}
     * classes. Null for plain builds — templates must guard with
     * {@code is not null} / {@code default()}. A mutable field rather than a
     * renderBook parameter deliberately: the engine is constructed per build,
     * and threading one publish-only value through three overloads (and every
     * caller) taxes the common path for the rare one.
     */
    private Map<String, Object> edition;

    /**
     * Set the edition identity for subsequent renders (publish builds only).
     * @param edition the edition map
     */
    public void setEdition(Map<String, Object> edition) {
        this.edition = edition;
    }

    /** Construct an engine that resolves templates from the classpath only, no theme. */
    public LayoutEngine() {
        this(null, ThemeBundle.NONE);
    }

    /**
     * Construct an engine that first looks in {@code <bookRoot>/layouts/} and
     * falls back to the classpath. {@code bookRoot} may be null. No theme.
     * @param bookRoot the book root path
     */
    public LayoutEngine(Path bookRoot) {
        this(bookRoot, ThemeBundle.NONE);
    }

    /**
     * Construct an engine with both a book root and a theme. The theme's
     * template loader (if any) takes priority over both the book layouts dir
     * and the bundled defaults; the theme's stylesheets are appended after
     * the user's CSS chain.
     * @param bookRoot the book root path
     * @param theme the theme bundle
     */
    public LayoutEngine(Path bookRoot, ThemeBundle theme) {
        this.theme = (theme == null) ? ThemeBundle.NONE : theme;
        this.bookRoot = bookRoot;
        this.engine = buildEngine(bookRoot, this.theme);
    }

    private static PebbleEngine buildEngine(Path bookRoot, ThemeBundle theme) {
        ClasspathLoader cp = new ClasspathLoader();
        cp.setPrefix("templates/");
        cp.setSuffix(".html");

        List<Loader<?>> chain = new ArrayList<>();
        if (theme.templateLoader() != null) chain.add(theme.templateLoader());
        if (bookRoot != null) {
            Path layouts = bookRoot.resolve("layouts");
            if (Files.isDirectory(layouts)) {
                // Pebble 4.1+ requires the prefix (an absolute path) at construction
                // time; setPrefix() alone is no longer sufficient (also now rejects
                // non-absolute paths — part of the CVE-2025-1686 traversal fix).
                FileLoader fs = new FileLoader(layouts.toAbsolutePath().toString() + "/");
                fs.setSuffix(".html");
                chain.add(fs);
            }
        }
        chain.add(cp);

        Loader<?> loader = (chain.size() == 1) ? chain.get(0) : new DelegatingLoader(chain);

        // Variable-miss handling:
        //
        // Pebble's own strictVariables flag bundles three behaviours we can't
        // unbundle: missing-root-var, missing-nested-map-key, AND treating
        // null inside {% if X %} as an error. The third is fatal for our
        // template style (~30 {% if optional %} guards on sparse data), so
        // strictVariables stays false.
        //
        // Instead we install StrictNestedMapAttributeResolver (via the
        // extension below) which fires only on non-LenientMap Map instances
        // and throws AttributeNotFoundException — carrying file:line — when
        // a key isn't present. {@link LenientMap} (wrapping frontmatter and
        // vars) opts out so sparse {% if card.frontmatter.X %} still works.
        // The throw type matters: Pebble's DefaultFilter and DefinedTest
        // special-case AttributeNotFoundException, so `| default(...)` and
        // `is defined` guards still behave correctly.
        //
        // What this catches: typos on structural maps, e.g. {{ card.tytle }},
        // {{ block.headign }}, {{ value.lable }}. What it does NOT catch:
        // unknown root names like {{ crd.id }} (resolves to null silently)
        // and typos within frontmatter/vars (lenient by design).
        return new PebbleEngine.Builder()
                .loader(loader)
                .extension(new LenientMapExtension())
                .strictVariables(false)
                .autoEscaping(true)
                .build();
    }

    /**
     * Render {@code card} using the layout referenced by {@code ctx} (or the default).
     * @param card the card to render
     * @param ctx the render context
     * @return the rendered HTML
     */
    public String render(Card card, RenderContext ctx) {
        return render(card, ctx, layoutName(ctx));
    }

    /**
     * Render {@code card} using the named layout.
     * @param card the card to render
     * @param ctx the render context
     * @param layoutName the name of the layout template to use
     * @return the rendered HTML
     */
    @SuppressWarnings("unchecked")
    public String render(Card card, RenderContext ctx, String layoutName) {
        Map<String, Object> model;
        String html;
        try {
            PebbleTemplate tmpl = engine.getTemplate(layoutName);
            model = buildModel(card, ctx);
            StringWriter out = new StringWriter();
            tmpl.evaluate(out, model);
            html = out.toString();
        } catch (IOException e) {
            throw new LayoutException(
                    "Failed to render layout '" + layoutName + "' for card " + card.id(), e);
        } catch (RuntimeException e) {
            throw new LayoutException(
                    "Layout '" + layoutName + "' failed for card " + card.id()
                            + locationOf(e) + ": " + explain(e), e);
        }
        checkSlots(layoutName, List.of((Map<String, Object>) model.get("card")));
        return html;
    }

    /**
     * Render a list of cards as a single aggregated HTML document using the
     * {@code book} layout. CSS chain is inlined once at the top; cards are
     * separated by page-break boundaries inside the template.
     *
     * <p>This overload synthesises empty per-card contexts — axis resolution
     * relies on each card's frontmatter alone. Use the three-arg overload to
     * pass the real per-card {@link RenderContext} list and surface
     * folder-yaml axis bindings (preferred for full-book renders).
     *
     * @param cards the cards to render
     * @param ctx the book-level render context
     * @return the rendered HTML book
     */
    public String renderBook(List<Card> cards, RenderContext ctx) {
        return renderBook(cards, repeatedContexts(ctx, cards.size()), ctx, "book");
    }

    /**
     * Render a list of cards as a single aggregated HTML document with per-card
     * contexts. Per-card contexts let the engine resolve each card's axis values
     * from the folder-yaml axis cascade (not just the markdown frontmatter), which
     * is what the multi-folder guide structure needs.
     *
     * @param cards     ordered card list
     * @param contexts  parallel list of per-card render contexts (must match {@code cards.size()})
     * @param bookCtx   book-level context (title, axes, css chain)
     * @return the rendered HTML book
     */
    public String renderBook(List<Card> cards, List<RenderContext> contexts, RenderContext bookCtx) {
        return renderBook(cards, contexts, bookCtx, "book");
    }

    /**
     * Render {@code cards} using the named book-level layout, with per-card contexts.
     * @param cards ordered card list
     * @param contexts parallel list of per-card render contexts
     * @param bookCtx book-level context
     * @param layoutName name of the book layout template
     * @return the rendered HTML book
     */
    public String renderBook(
            List<Card> cards,
            List<RenderContext> contexts,
            RenderContext bookCtx,
            String layoutName) {
        if (cards.size() != contexts.size()) {
            throw new IllegalArgumentException(
                    "cards (" + cards.size() + ") and contexts (" + contexts.size() + ") size mismatch");
        }
        Map<String, Object> model;
        String html;
        try {
            PebbleTemplate tmpl = engine.getTemplate(layoutName);
            model = buildBookModel(cards, contexts, bookCtx);
            StringWriter out = new StringWriter();
            tmpl.evaluate(out, model);
            html = out.toString();
        } catch (IOException e) {
            throw new LayoutException(
                    "Failed to render book layout '" + layoutName + "' (" + cards.size() + " cards)", e);
        } catch (RuntimeException e) {
            throw new LayoutException(
                    "Book layout '" + layoutName + "' failed"
                            + locationOf(e) + ": " + explain(e), e);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cardModels = (List<Map<String, Object>>) model.get("cards");
        checkSlots(layoutName, cardModels);
        return html;
    }

    private static List<RenderContext> repeatedContexts(RenderContext ctx, int n) {
        List<RenderContext> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(ctx);
        return list;
    }

    // ---------------------------------------------------------------------
    // Structure description (the `paperband structure` command).
    // ---------------------------------------------------------------------

    /**
     * Describe a book's document structure as an indented text outline —
     * cover, axis dividers, sections, cards, and each card's block tree —
     * in the exact order the PDF assembles them.
     *
     * <p>The model here is deliberately honest about what paperband actually
     * builds: cards are a <em>flat, ordered walk</em> (BookWalker order), and
     * "structure" is derived from it — an axis DIVIDER line appears whenever a
     * card is first-of-value for that axis (stacked per axis, declaration
     * order, exactly when the PDF injects a divider page), and a SECTION line
     * appears when an axis-less card is first of its top-level folder. If
     * cards of the same value are interleaved in walk order, the same divider
     * repeats — because it does in the PDF too.
     *
     * <p><b>Keep in sync with {@link #buildBookModel}:</b> the first-of-value /
     * first-of-section bookkeeping below mirrors the {@code axesFirstOf} /
     * {@code sectionFirst} loop there, which is what {@code book.html}
     * dispatches divider pages on.
     *
     * @param cards ordered card list
     * @param contexts parallel list of per-card render contexts
     * @param bookCtx book-level context
     * @return the text outline
     */
    public static String describeBook(
            List<Card> cards, List<RenderContext> contexts, RenderContext bookCtx) {
        if (cards.size() != contexts.size()) {
            throw new IllegalArgumentException(
                    "cards (" + cards.size() + ") and contexts (" + contexts.size() + ") size mismatch");
        }
        StringBuilder sb = new StringBuilder();
        var book = bookCtx.book();
        sb.append("BOOK ")
                .append(book.title() == null ? "(untitled)" : "\"" + book.title() + "\"")
                .append("  [").append(cards.size()).append(cards.size() == 1 ? " card]" : " cards]")
                .append('\n');

        appendPageMatter(sb, "COVER", book.cover());

        List<AxisGrouping> groupings = computeAxisGroupings(bookCtx, cards, contexts);
        Path bookRoot = book.bookRoot();
        List<Part> bookParts = book.parts();

        // Same section discovery as buildBookModel: axis-less cards grouped
        // by top-level folder.
        Map<String, List<Integer>> bySection = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            if (hasAnyAxisValue(i, groupings)) continue;
            String secId = sectionIdFor(bookRoot, bookParts, cards.get(i).source());
            if (secId == null) continue;
            bySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bySection, bookRoot, bookParts, book.sectionLandingTemplate(), new HashMap<>());

        Map<String, String> prevValueKeyByAxis = new HashMap<>();
        String prevSectionId = null;
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            boolean grouped = false;

            // Axis dividers — one per axis this card is first-of-value for,
            // stacked in axes: declaration order (mirrors axesFirstOf).
            for (AxisGrouping g : groupings) {
                String key = normalizeAxisId(g.perCardValue().get(i));
                if (key == null) continue;
                grouped = true;
                if (!key.equals(prevValueKeyByAxis.get(g.axis().name()))) {
                    Map<String, Object> meta = g.metaFor(i);
                    sb.append("  DIVIDER ").append(g.axis().name()).append('=').append(key);
                    if (meta != null) {
                        sb.append("  \"").append(meta.get("label")).append('"')
                                .append("  [").append(meta.get("count")).append(" cards]");
                    }
                    sb.append('\n');
                }
                prevValueKeyByAxis.put(g.axis().name(), key);
            }

            // Section divider — the axis-less fallback (mirrors sectionFirst).
            if (!hasAnyAxisValue(i, groupings)) {
                String secId = sectionIdFor(bookRoot, bookParts, card.source());
                if (secId != null) {
                    grouped = true;
                    if (!secId.equals(prevSectionId)) {
                        Map<String, Object> meta = findSectionMeta(sectionMetas, secId);
                        sb.append("  SECTION ").append(secId);
                        if (meta != null) {
                            sb.append("  \"").append(meta.get("label")).append('"')
                                    .append("  [").append(meta.get("count")).append(" cards]");
                        }
                        sb.append('\n');
                    }
                    prevSectionId = secId;
                }
            }

            String indent = grouped ? "    " : "  ";
            sb.append(indent).append("CARD ").append(card.id());
            if (card.title() != null) sb.append("  \"").append(card.title()).append('"');
            StringBuilder ax = new StringBuilder();
            for (AxisGrouping g : groupings) {
                Object v = g.perCardValue().get(i);
                if (v == null) continue;
                if (ax.length() > 0) ax.append(", ");
                ax.append(g.axis().name()).append('=').append(v);
            }
            if (ax.length() > 0) sb.append("  {").append(ax).append('}');
            sb.append("  (").append(relativeSource(bookRoot, card.source())).append(')').append('\n');
            appendBlockOutline(sb, card.blocks(), indent + "  ");
        }

        appendPageMatter(sb, "BACK", book.back());
        return sb.toString();
    }

    /** Single-card variant of {@link #describeBook}: the card and its block tree. */
    public static String describeCard(Card card) {
        StringBuilder sb = new StringBuilder();
        sb.append("CARD ").append(card.id());
        if (card.title() != null) sb.append("  \"").append(card.title()).append('"');
        sb.append("  (").append(card.source()).append(')').append('\n');
        appendBlockOutline(sb, card.blocks(), "  ");
        return sb.toString();
    }

    private static void appendPageMatter(
            StringBuilder sb, String label, dev.noregressions.paperband.model.PageMatter matter) {
        if (matter == null || matter.isEmpty()) return;
        sb.append("  ").append(label);
        List<String> bits = new ArrayList<>(2);
        if (matter.template() != null) bits.add("template: " + matter.template());
        if (matter.image() != null) bits.add("image: " + matter.image());
        if (!bits.isEmpty()) sb.append("  (").append(String.join(", ", bits)).append(')');
        sb.append('\n');
    }

    private static void appendBlockOutline(StringBuilder sb, List<Block> blocks, String indent) {
        for (Block b : blocks) {
            sb.append(indent).append("- ");
            sb.append(b.heading() != null ? b.heading()
                    : (b.kind() == Block.Kind.FENCED_DIV ? "(fenced div)" : "(intro)"));
            if (b.id() != null) sb.append("  #").append(b.id());
            if (!b.classes().isEmpty()) sb.append("  .").append(String.join(" .", b.classes()));
            sb.append('\n');
            appendBlockOutline(sb, b.children(), indent + "  ");
        }
    }

    private static String relativeSource(Path bookRoot, Path source) {
        if (bookRoot == null || source == null) return String.valueOf(source);
        try {
            Path abs = source.toAbsolutePath().normalize();
            Path root = bookRoot.toAbsolutePath().normalize();
            return abs.startsWith(root) ? root.relativize(abs).toString() : source.toString();
        } catch (IllegalArgumentException e) {
            return source.toString();
        }
    }

    /**
     * Render a list of cards as a multi-file static site.
     *
     * <p>Returns a map keyed by output-relative path (e.g. {@code index.html},
     * {@code tier-1.html}, {@code cards/foo.html}) to fully-formed HTML strings.
     * The caller is responsible for writing each entry to disk.
     *
     * <p>Templates resolved (each falls back to the bundled defaults, unless an
     * axis declares its own {@link Axis#landingTemplate()}):
     * <ul>
     *   <li>{@code site-index} — the landing page</li>
     *   <li>{@code site-tier} — one page per axis value, for every axis
     *       declared with values. Kept under its historical filename, but the
     *       template itself is axis-generic: it renders any axis's value
     *       pages given {@code axis}+{@code value}, not just a "tier" axis.</li>
     *   <li>{@code site-card} — one page per card</li>
     * </ul>
     *
     * <p>The model includes pre-computed groupings for every declared axis
     * independently: cards-by-value, value metadata (id, label, count,
     * colour), and prev/next pointers per card derived from the supplied list
     * order. Each card's value along an axis comes from
     * {@link #resolveAxisValue} — its own frontmatter field of the same name,
     * falling back to the folder-cascaded {@code vars} entry.
     *
     * @param cards     ordered card list (book walk order — drives prev/next)
     * @param contexts  parallel list of per-card {@link RenderContext}; must be the same size as {@code cards}
     * @param bookCtx   book-level context (title, axes, css chain)
     */
    public Map<String, String> renderSite(
            List<Card> cards,
            List<RenderContext> contexts,
            RenderContext bookCtx) {
        if (cards.size() != contexts.size()) {
            throw new IllegalArgumentException(
                    "cards (" + cards.size() + ") and contexts (" + contexts.size() + ") size mismatch");
        }

        List<AxisGrouping> groupings = computeAxisGroupings(bookCtx, cards, contexts);

        // Build site-wide model fragments shared across all pages.
        Map<String, Object> stats = buildStats(cards, groupings);
        // Site pages share built-in, theme-independent templates, so they get a
        // built-in scaffold stylesheet first (weakest), then the user chain, then
        // the theme (strongest) — themes restyle colours without re-implementing
        // the sidebar/column layout.
        String css = siteBaseCss() + composeCss(bookCtx.cssChain());
        Map<String, Object> bookModel = bookSiteModel(bookCtx);

        // Sections: any top-level folder under the book root (or under a
        // `content/` wrapper) whose cards have no value on ANY declared axis.
        // Each section gets its own landing page and a nav entry alongside
        // the axis value pages. Front matter, back matter, delaying-tactics,
        // etc. live here.
        Path bookRoot = bookCtx.book().bookRoot();
        List<Part> bookParts = bookCtx.book().parts();
        Map<String, List<Integer>> bySection = new LinkedHashMap<>();
        Map<String, FolderYamlInfo> sectionFolderYamlCache = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            if (hasAnyAxisValue(i, groupings)) continue;
            String secId = sectionIdFor(bookRoot, bookParts, cards.get(i).source());
            if (secId == null) continue;
            bySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bySection, bookRoot, bookParts, bookCtx.book().sectionLandingTemplate(), sectionFolderYamlCache);

        // Sidebar opt-in lives in bookCtx.vars() so it cascades through the
        // standard yaml chain. Boolean / string-truthy both accepted.
        // sidebar_sections_collapsed controls the initial open/closed state
        // of each axis-value/section row's card list. Default is true
        // (closed), matching the "table of contents that opens what you
        // need" pattern; set to false in book vars to ship every section
        // expanded.
        boolean sidebar = truthy(bookCtx.vars().get("sidebar"));
        boolean sidebarCollapsed = truthy(bookCtx.vars().get("sidebar_collapsed"));
        boolean sidebarSectionsCollapsed = truthyOrDefault(
                bookCtx.vars().get("sidebar_sections_collapsed"), true);

        // Sidebar axis-value cards: each value needs its `cards` filled with
        // the card summaries belonging to it so the partial can render the
        // tree. Same treatment for sections so the sidebar can list
        // front/back/etc. Done before buildNavEntries so nav entries
        // snapshot the `cards` field too (sidebar partial reads e.cards from
        // nav entries).
        if (sidebar) {
            attachValueCards(groupings, cards);
            attachSectionCards(sectionMetas, bySection, cards);
        }

        // Unified, walk-ordered nav list. The index/sidebar/top-nav iterate
        // this single list so every axis value and section entry appears in
        // the order cards were walked (driven by the book's paperband.yaml
        // `order:` chain) rather than always "axes first, sections last".
        List<Map<String, Object>> navEntries = buildNavEntries(cards, groupings, bookRoot, bookParts, sectionMetas);

        Map<String, String> out = new LinkedHashMap<>();

        // index.html
        Map<String, Object> indexModel = new HashMap<>();
        indexModel.put("book", bookModel);
        indexModel.put("navEntries", navEntries);
        indexModel.put("sections", sectionMetas);
        indexModel.put("axisGroupings", axisGroupingsModel(groupings));
        indexModel.put("stats", stats);
        indexModel.put("css", css);
        indexModel.put("urlPrefix", "");
        indexModel.put("page", Map.of("kind", "index"));
        indexModel.put("sidebar", sidebar);
        indexModel.put("sidebar_collapsed", sidebarCollapsed);
        indexModel.put("sidebar_sections_collapsed", sidebarSectionsCollapsed);
        out.put("index.html", renderSiteTemplate("site-index", indexModel));

        // {axisName}-{valueId}.html — one per axis value, for every declared axis.
        // Rendered by "site-tier" (its historical filename — the template
        // itself is axis-generic) unless the axis overrides with its own
        // landingTemplate.
        for (AxisGrouping g : groupings) {
            String landingTemplate = g.axis().landingTemplate() == null
                    ? "site-tier"
                    : templateNameOf(bookRoot, g.axis().landingTemplate());
            for (Map<String, Object> value : g.valueMetas()) {
                List<Integer> indices = g.byValue().getOrDefault(normalizeAxisId(value.get("id")), List.of());
                List<Map<String, Object>> valueCards = new ArrayList<>(indices.size());
                for (int i : indices) {
                    valueCards.add(siteCardSummary(cards.get(i), groupings, i));
                }
                Map<String, Object> valueModel = new HashMap<>();
                valueModel.put("book", bookModel);
                valueModel.put("navEntries", navEntries);
                valueModel.put("sections", sectionMetas);
                valueModel.put("axis", axisMetaModel(g.axis()));
                valueModel.put("value", value);
                valueModel.put("cards", valueCards);
                valueModel.put("stats", stats);
                valueModel.put("css", css);
                valueModel.put("urlPrefix", "");
                valueModel.put("page", Map.of("kind", "axis", "axis", g.axis().name(), "id", value.get("id")));
                valueModel.put("sidebar", sidebar);
                valueModel.put("sidebar_collapsed", sidebarCollapsed);
                valueModel.put("sidebar_sections_collapsed", sidebarSectionsCollapsed);
                out.put(axisPageId(g.axis(), value.get("id")) + ".html",
                        renderSiteTemplate(landingTemplate, valueModel));
            }
        }

        // <section-id>.html — one per section (front, back, ...), except a
        // declared part that opted out of having a page of its own. Its cards
        // are already in the book and still listed under its label in the
        // nav; there's just no page to land on (buildNavEntries drops the
        // link to match).
        for (Map<String, Object> section : sectionMetas) {
            String id = (String) section.get("id");
            if (Boolean.FALSE.equals(section.get("landingPage"))) continue;
            List<Integer> indices = bySection.getOrDefault(id, List.of());
            List<Map<String, Object>> sectionCards = new ArrayList<>(indices.size());
            for (int i : indices) {
                sectionCards.add(siteCardSummary(cards.get(i), groupings, i));
            }
            Map<String, Object> sectionModel = new HashMap<>();
            sectionModel.put("book", bookModel);
            sectionModel.put("navEntries", navEntries);
            sectionModel.put("sections", sectionMetas);
            sectionModel.put("section", section);
            sectionModel.put("cards", sectionCards);
            sectionModel.put("stats", stats);
            sectionModel.put("css", css);
            sectionModel.put("urlPrefix", "");
            sectionModel.put("page", Map.of("kind", "section", "id", id));
            sectionModel.put("sidebar", sidebar);
            sectionModel.put("sidebar_collapsed", sidebarCollapsed);
            sectionModel.put("sidebar_sections_collapsed", sidebarSectionsCollapsed);
            String sectionTemplate = (String) section.getOrDefault("landingTemplate", "site-section");
            out.put(id + ".html", renderSiteTemplate(sectionTemplate, sectionModel));
        }

        // cards/{id}.html — one per card
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            // Section meta for axis-less cards so the per-card page can show
            // a back-link to its containing section in nav.
            Map<String, Object> sectionMeta = null;
            if (!hasAnyAxisValue(i, groupings)) {
                String secId = sectionIdFor(bookRoot, bookParts, card.source());
                if (secId != null) sectionMeta = findSectionMeta(sectionMetas, secId);
            }
            Map<String, Object> prev = (i > 0) ? siteCardLink(cards.get(i - 1), groupings, i - 1) : null;
            Map<String, Object> next = (i + 1 < cards.size()) ? siteCardLink(cards.get(i + 1), groupings, i + 1) : null;

            // Card-page back-link target: the first axis (in book.axes()
            // declaration order) this card has a value for, else its
            // section. A card can belong to more than one axis at once —
            // the top-of-page back-link only has room for one, so it picks
            // the first declared axis, same tie-break as the PDF divider
            // stacking order.
            Map<String, Object> axisBack = null;
            for (AxisGrouping g : groupings) {
                Map<String, Object> vm = g.metaFor(i);
                if (vm == null) continue;
                axisBack = new LinkedHashMap<>();
                axisBack.put("axis", g.axis().name());
                axisBack.put("axisTitle", g.axis().title());
                axisBack.put("id", vm.get("id"));
                axisBack.put("label", vm.get("label"));
                axisBack.put("color", vm.get("color"));
                axisBack.put("url", axisPageId(g.axis(), vm.get("id")) + ".html");
                break;
            }

            Map<String, Object> model = new HashMap<>();
            Map<String, Object> cm = cardModel(card, cardAxesFromGroupings(i, groupings));
            model.put("book", bookModel);
            model.put("navEntries", navEntries);
            model.put("sections", sectionMetas);
            model.put("section", sectionMeta);
            model.put("axisBack", axisBack);
            model.put("card", cm);
            model.put("vars", LenientMap.of(contexts.get(i).vars()));
            model.put("prev", prev);
            model.put("next", next);
            model.put("stats", stats);
            model.put("css", css);
            model.put("urlPrefix", "../");
            model.put("page", Map.of("kind", "card", "id", card.id()));
            model.put("sidebar", sidebar);
            model.put("sidebar_collapsed", sidebarCollapsed);
            model.put("sidebar_sections_collapsed", sidebarSectionsCollapsed);

            // Optional auto-cards block. When a card's frontmatter sets
            // `auto_cards: true`, attach a sorted list of every other card in
            // the book whose `tech` frontmatter overlaps with this page's
            // `tech`, sorted by the book's first declared axis (if any).
            // Used by the by-technology index pages under content/tech/, but
            // the mechanism is generic — any card with the flag gets the
            // cross-cut list. site-card.html renders it via _tech-row.html
            // after the regular card body.
            if (truthy(card.frontmatter().values().get("auto_cards"))) {
                model.put("autoCards", buildAutoCardsList(card, cards, groupings));
            }

            out.put("cards/" + card.id() + ".html",
                    renderSiteTemplate("site-card", model));
            checkSlots("site-card", List.of(cm));
        }

        return out;
    }

    /** Strip a stored template path down to the bare name Pebble's loader expects (no extension). */
    /**
     * The template name for an axis's landing template — a path the config
     * already resolved, so it goes through the {@code Path} form of
     * {@link NamedTemplates#templateName} to keep any {@code layouts/}
     * subdirectory in the name.
     */
    private static String templateNameOf(Path bookRoot, Path landingTemplate) {
        return NamedTemplates.templateName(bookRoot, landingTemplate);
    }

    /** Output filename stem for one axis value's landing page: {@code {axisName}-{valueId}}. */
    private static String axisPageId(Axis axis, Object valueId) {
        return axis.name() + "-" + valueId;
    }

    private static Map<String, Object> axisMetaModel(Axis axis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", axis.name());
        m.put("title", axis.title());
        return m;
    }

    /**
     * Attach a slim card-summary list to every axis value's metadata entry
     * (across every grouping) so the sidebar partial can render a per-value
     * card list. Mirrors the structure already attached for divider TOCs in
     * {@code buildBookModel}.
     */
    private static void attachValueCards(List<AxisGrouping> groupings, List<Card> cards) {
        for (AxisGrouping g : groupings) {
            for (Map<String, Object> value : g.valueMetas()) {
                List<Integer> indices = g.byValue().getOrDefault(normalizeAxisId(value.get("id")), List.of());
                List<Map<String, Object>> valueCards = new ArrayList<>(indices.size());
                for (int i : indices) {
                    valueCards.add(siteCardSummary(cards.get(i), groupings, i));
                }
                value.put("cards", valueCards);
            }
        }
    }

    /**
     * Build a unified, walk-ordered list of navigation entries spanning
     * every axis value (across every declared axis, independently) and every
     * section. The output preserves the order in which each (axis, value) or
     * section first appears in the card walk — which itself follows the
     * book's {@code paperband.yaml order:} chain.
     *
     * <p>A card can contribute more than one entry (once per axis it has a
     * value for) at its first-occurrence position; a card with no axis value
     * at all contributes its section entry instead.
     *
     * <p>Each entry is a {@link LinkedHashMap} with keys:
     * <ul>
     *   <li>{@code kind} — either {@code "axis"} or {@code "section"}</li>
     *   <li>{@code axis}, {@code axisTitle} — present for axis entries only</li>
     *   <li>{@code id} — value id (axis entries) or section id</li>
     *   <li>{@code label}, {@code count} — copied from the source meta</li>
     *   <li>{@code color} — present for axis entries, absent for sections</li>
     *   <li>{@code url} — relative URL of the landing page
     *       ({@code "tier-1.html"} or {@code "front.html"}); absent for a
     *       declared part that has no page of its own</li>
     * </ul>
     *
     * <p>Templates iterate this single list so the index, sidebar and top
     * nav render axis values and sections in their natural book order —
     * front matter first, axis groups in the middle, back matter last —
     * rather than always grouping axes before sections.
     */
    private static List<Map<String, Object>> buildNavEntries(
            List<Card> cards,
            List<AxisGrouping> groupings,
            Path bookRoot,
            List<Part> bookParts,
            List<Map<String, Object>> sectionMetas) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            boolean any = false;
            for (AxisGrouping g : groupings) {
                Map<String, Object> valueMeta = g.metaFor(i);
                if (valueMeta == null) continue;
                any = true;
                String key = "axis:" + g.axis().name() + ":" + valueMeta.get("id");
                if (seen.containsKey(key)) continue;
                Map<String, Object> entry = new LinkedHashMap<>(valueMeta);
                entry.put("kind", "axis");
                entry.put("axis", g.axis().name());
                entry.put("axisTitle", g.axis().title());
                entry.put("url", axisPageId(g.axis(), valueMeta.get("id")) + ".html");
                seen.put(key, entry);
            }
            if (any) continue;
            String secId = sectionIdFor(bookRoot, bookParts, cards.get(i).source());
            if (secId == null) continue;
            String key = "section:" + secId;
            if (seen.containsKey(key)) continue;
            Map<String, Object> section = findSectionMeta(sectionMetas, secId);
            if (section == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>(section);
            entry.put("kind", "section");
            // A part with no page of its own has nothing to link to, so its
            // url is null and the templates render the label as plain text
            // (its cards still link to their own pages). The key is always
            // present, null or not — Pebble raises on a missing attribute,
            // so an absent key would break every template reading e.url.
            entry.put("url", Boolean.FALSE.equals(section.get("landingPage"))
                    ? null : secId + ".html");
            seen.put(key, entry);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Derive a section identifier for a card's source path. Returns the first
     * path component beneath the book root (skipping an optional {@code content/}
     * wrapper) — e.g. {@code /guide/content/front/foreword.md} → {@code "front"}.
     * Returns {@code null} when the card lies directly in the book root or
     * directly in {@code content/} (those cards have no enclosing section).
     *
     * <p>A part that claims this card <em>by path</em> ({@link Part#cards()},
     * how the Maven plugin's pattern-declared parts express membership) wins
     * outright, before the folder is even looked at: such a part exists
     * precisely to group cards the directory layout doesn't group, and two of
     * them may draw different files out of the same folder.
     */
    private static String sectionIdFor(Path bookRoot, List<Part> parts, Path source) {
        String claimed = partIdForCard(parts, source);
        if (claimed != null) return claimed;
        String folder = folderIdFor(bookRoot, source);
        if (folder == null) return null;
        // A declared part speaks for every folder it claims, so those cards
        // report the part's id and land in one group; unclaimed folders keep
        // reporting their own name and stay discovered sections.
        String partId = partIdForFolder(parts, folder);
        return partId != null ? partId : folder;
    }

    /**
     * The raw top-level folder a card sits in, relative to the book root (or
     * to a {@code content/} wrapper) — the discovered section id, before any
     * {@code parts:} declaration gets a say.
     */
    private static String folderIdFor(Path bookRoot, Path source) {
        if (bookRoot == null || source == null) return null;
        Path abs = source.toAbsolutePath().normalize();
        Path root = bookRoot.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) return null;
        Path rel = root.relativize(abs);
        int n = rel.getNameCount();
        int start = (n > 0 && "content".equals(rel.getName(0).toString())) ? 1 : 0;
        // Need at least <section>/<card>.md beneath the start point.
        if (n <= start + 1) return null;
        return rel.getName(start).toString();
    }

    /**
     * The id of the declared part claiming this exact card file, or null when
     * no part does. Only pattern-declared parts claim individual cards; a
     * yaml {@code parts:} entry claims folders and is skipped here.
     */
    private static String partIdForCard(List<Part> parts, Path source) {
        if (parts == null || source == null) return null;
        for (Part part : parts) {
            if (part.claims(source)) return part.id();
        }
        return null;
    }

    /** The id of the declared part claiming {@code folder}, or null when no part does. */
    private static String partIdForFolder(List<Part> parts, String folder) {
        if (parts == null || folder == null) return null;
        for (Part part : parts) {
            if (part.folders().contains(folder)) return part.id();
        }
        return null;
    }

    /** The declared part with this id, or null — {@code id} may equally be a discovered folder's. */
    private static Part partById(List<Part> parts, String id) {
        if (parts == null || id == null) return null;
        for (Part part : parts) {
            if (id.equals(part.id())) return part;
        }
        return null;
    }

    /**
     * Build section meta entries — one per discovered axis-less top-level
     * folder. Preserves discovery order so the index/sidebar lists sections
     * in the order cards were walked (front before back, etc.).
     *
     * <p>Each entry also gets a resolved {@code landingTemplate} (a bare
     * Pebble template name, ready to pass to {@link #renderSiteTemplate}):
     * the section folder's own {@code paperband.yaml} {@code landing.template}
     * wins, falling back to the book root's {@code sections.landing.template}
     * default, falling back to the built-in {@code "site-section"} template.
     * Either config value may be a built-in preset name (see
     * {@link NamedTemplates}, e.g. {@code "minimal"}) or a custom template
     * file path — both are already resolved to a bare template name by the
     * time they reach this method. Mirrors the equivalent per-axis
     * {@link Axis#landingTemplate()} override.
     *
     * <p>Each entry also carries {@code landingPage} — false only for a
     * declared {@link Part} that opted out (see {@link Part#landingPage()}).
     * The entry itself still exists, so the group keeps its label, count and
     * card list in the nav, sidebar and index; what's dropped is its own page
     * and every link to one.
     */
    private static List<Map<String, Object>> buildSectionMetas(
            Map<String, List<Integer>> bySection,
            Path bookRoot,
            List<Part> parts,
            String bookDefaultSectionTemplate,
            Map<String, FolderYamlInfo> folderYamlCache) {
        List<Map<String, Object>> out = new ArrayList<>(bySection.size());
        for (var e : bySection.entrySet()) {
            String id = e.getKey();
            // A declared part carries its own title and landing template, so
            // it needs no folder yaml lookup -- it spans several folders and
            // no single one of them could speak for the group. Discovered
            // sections resolve from their folder's yaml exactly as before.
            Part part = partById(parts, id);
            FolderYamlInfo info = part != null
                    ? new FolderYamlInfo(part.title(), part.landingTemplate())
                    : lookupSectionFolderYaml(bookRoot, id, folderYamlCache);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", info.title() != null ? info.title() : formatSectionLabel(id));
            m.put("count", e.getValue().size());
            String template = info.landingTemplate() != null ? info.landingTemplate() : bookDefaultSectionTemplate;
            template = template == null ? "site-section" : template;
            m.put("landingTemplate", template);
            // Exposed for _section-divider(-base).html: the PDF divider has no
            // separate HTML template to dispatch on the way the site landing
            // page does, so it reads this flag directly to decide whether to
            // show the card count + TOC or just the title, keeping the PDF
            // and site output in sync for the same section.
            m.put("minimal", NamedTemplates.MINIMAL_SECTION_TEMPLATE.equals(template));
            // Whether this group fronts its cards with a page of its own: the
            // PDF divider (book.html) and the site's <id>.html landing page.
            // Only a declared part can opt out — a discovered folder has
            // nowhere to say so, so it always gets one.
            m.put("landingPage", part == null || part.landingPage());
            out.add(m);
        }
        return out;
    }

    /**
     * A section folder's own {@code paperband.yaml}, as much of it as this
     * class cares about: the {@code title} scalar (section label override)
     * and the resolved {@code landing.template} name (section landing-page
     * template override, already run through {@link NamedTemplates} — same
     * shape and same key name as a book axis's own per-value
     * {@code landing.template}), just read from the section folder's yaml
     * directly instead of via {@code ConfigLoader}'s per-card cascade
     * (section grouping happens once, after every card in the book is
     * already loaded, not while walking one card's parent chain).
     */
    private record FolderYamlInfo(String title, String landingTemplate) {
        static final FolderYamlInfo EMPTY = new FolderYamlInfo(null, null);
    }

    /**
     * Look up {@code sectionId}'s folder yaml. Tries
     * {@code <bookRoot>/<id>/paperband.yaml} first, then
     * {@code <bookRoot>/content/<id>/paperband.yaml}. Results are cached
     * per call to {@link #renderSite}.
     */
    private static FolderYamlInfo lookupSectionFolderYaml(
            Path bookRoot, String sectionId, Map<String, FolderYamlInfo> cache) {
        return cache.computeIfAbsent(sectionId, id -> {
            if (bookRoot == null) return FolderYamlInfo.EMPTY;
            FolderYamlInfo info = readFolderYaml(bookRoot, bookRoot.resolve(id).resolve("paperband.yaml"));
            if (info != null) return info;
            info = readFolderYaml(bookRoot, bookRoot.resolve("content").resolve(id).resolve("paperband.yaml"));
            return info != null ? info : FolderYamlInfo.EMPTY;
        });
    }

    /** Parse one folder's {@code paperband.yaml} for {@code title} and {@code landing.template}, or null if absent/unreadable. */
    private static FolderYamlInfo readFolderYaml(Path bookRoot, Path yamlFile) {
        if (yamlFile == null || !Files.isRegularFile(yamlFile)) return null;
        try (Reader r = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
            Object data = new Yaml().load(r);
            if (!(data instanceof Map<?, ?> map)) return null;
            Object titleNode = map.get("title");
            String title = titleNode == null ? null : titleNode.toString();
            String landingTemplate = null;
            Object landingNode = map.get("landing");
            if (landingNode instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) landingTemplate = NamedTemplates.resolveSectionTemplate(bookRoot, t.toString());
            }
            return (title == null && landingTemplate == null) ? null : new FolderYamlInfo(title, landingTemplate);
        } catch (IOException | RuntimeException ignored) {
            // Malformed/unreadable folder yaml shouldn't break the whole site
            // build — fall back to the auto-formatted label and default template.
            return null;
        }
    }

    /** {@code "delaying-tactics"} → {@code "Delaying Tactics"}; safe fallback. */
    private static String formatSectionLabel(String id) {
        String[] parts = id.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    /** Mirror of axis-value lookup, for sections. */
    private static Map<String, Object> findSectionMeta(
            List<Map<String, Object>> metas, String id) {
        if (id == null) return null;
        for (var m : metas) if (id.equals(m.get("id"))) return m;
        return null;
    }

    /**
     * Attach a slim card-summary list to each section so the sidebar partial
     * can render a per-section card list. Mirrors {@link #attachValueCards}.
     */
    private static void attachSectionCards(
            List<Map<String, Object>> sectionMetas,
            Map<String, List<Integer>> bySection,
            List<Card> cards) {
        for (Map<String, Object> section : sectionMetas) {
            String id = (String) section.get("id");
            List<Integer> indices = bySection.getOrDefault(id, List.of());
            List<Map<String, Object>> sectionCards = new ArrayList<>(indices.size());
            for (int i : indices) {
                sectionCards.add(siteCardSummary(cards.get(i), List.of(), i));
            }
            section.put("cards", sectionCards);
        }
    }

    private String renderSiteTemplate(String layoutName, Map<String, Object> model) {
        try {
            PebbleTemplate tmpl = engine.getTemplate(layoutName);
            StringWriter w = new StringWriter();
            tmpl.evaluate(w, model);
            return w.toString();
        } catch (IOException e) {
            throw new LayoutException("Failed to render site template '" + layoutName + "'", e);
        } catch (RuntimeException e) {
            throw new LayoutException(
                    "Site template '" + layoutName + "' failed"
                            + locationOf(e) + ": " + messageOf(e), e);
        }
    }

    private static Map<String, Object> bookSiteModel(RenderContext ctx) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", ctx.book().title());
        m.put("vars", LenientMap.of(ctx.vars()));
        // Allow root yaml to expose subtitle, series, author via vars.
        m.put("subtitle", ctx.vars().get("subtitle"));
        m.put("series", ctx.vars().get("series"));
        m.put("author", ctx.vars().get("author"));
        // Front-cover / back-page declarations (see PageMatter). Always maps
        // (never null) so templates can test book.cover.image etc. directly
        // without short-circuit guards.
        m.put("cover",  pageMatterModel(ctx.book().cover(),  ctx.book().bookRoot()));
        m.put("back",   pageMatterModel(ctx.book().back(),   ctx.book().bookRoot()));
        m.put("footer", pageMatterModel(ctx.book().footer(), ctx.book().bookRoot()));
        m.put("header", pageMatterModel(ctx.book().header(), ctx.book().bookRoot()));
        return m;
    }

    /**
     * Pre-render a book's declared {@code footer: { template: ... }} to a
     * standalone HTML string, or {@code null} if no footer is declared / the
     * footer declares no template. Call once per book build and thread the
     * result into {@code HtmlInput.footerHtml}.
     *
     * <p>Playwright is the only renderer, and Chromium's print engine has no
     * CSS Paged Media support at all — the one way to get a running footer
     * repeating on every page is {@code Page.pdf()}'s own header/footer
     * option, which evaluates the template in total isolation from the main
     * document's stylesheet (no {@code <link>} access, only inline styles
     * render). That's why this is rendered standalone here rather than just
     * included inline in {@code book.html} — see {@link #renderHeader} for
     * the {@code header:} counterpart, and the inline-style convention any
     * header/footer template should follow.
     */
    public String renderFooter(RenderContext bookCtx) {
        dev.noregressions.paperband.model.PageMatter footer = bookCtx.book().footer();
        if (footer == null || footer.template() == null || footer.template().isBlank()) {
            return null;
        }
        Map<String, Object> model = new HashMap<>();
        model.put("book", bookSiteModel(bookCtx));
        model.put("vars", LenientMap.of(bookCtx.vars()));
        return renderSiteTemplate(footer.template(), model);
    }

    /**
     * Pre-render a book's declared {@code header: { template: ... }} to a
     * standalone HTML string, or {@code null} if no header is declared / the
     * header declares no template. Call once per book build and thread the
     * result into {@code HtmlInput.headerHtml}. See {@link #renderFooter} for
     * the full rationale (same Playwright header/footer mechanism, same
     * self-contained-inline-styles constraint, same real top-margin-space
     * requirement — just the top band instead of the bottom one).
     */
    public String renderHeader(RenderContext bookCtx) {
        dev.noregressions.paperband.model.PageMatter header = bookCtx.book().header();
        if (header == null || header.template() == null || header.template().isBlank()) {
            return null;
        }
        Map<String, Object> model = new HashMap<>();
        model.put("book", bookSiteModel(bookCtx));
        model.put("vars", LenientMap.of(bookCtx.vars()));
        return renderSiteTemplate(header.template(), model);
    }

    /**
     * Pebble-facing shape of a {@link dev.noregressions.paperband.model.PageMatter}.
     * The image path (book-root relative in the yaml) is resolved to an
     * absolute {@code file:} URI so it loads regardless of the renderer's
     * base URI (which is the build <em>input</em> directory — not necessarily
     * the book root when a subfolder is built).
     */
    private static Map<String, Object> pageMatterModel(
            dev.noregressions.paperband.model.PageMatter matter, Path bookRoot) {
        Map<String, Object> m = new HashMap<>();
        if (matter == null) {
            m.put("image", null);
            m.put("template", null);
            m.put("present", false);
            return m;
        }
        String image = matter.image();
        if (image != null && bookRoot != null) {
            image = bookRoot.resolve(image).toUri().toString();
        }
        m.put("image", image);
        m.put("template", matter.template());
        m.put("present", !matter.isEmpty());
        return m;
    }

    // ---------------------------------------------------------------------
    // Axis grouping. Every axis declared in the book's paperband.yaml with
    // at least one declared value gets full structural treatment (grouping,
    // landing pages, dividers, nav, sidebar, CSS class) automatically and
    // independently of every other axis — there is no hardcoded "primary"
    // axis anywhere below this point.
    // ---------------------------------------------------------------------

    /** Default colour palette for axis values that don't declare their own via {@code meta.color}. */
    private static final List<String> DEFAULT_AXIS_PALETTE = List.of(
            "#c0392b", "#e67e22", "#8e44ad", "#2980b9", "#16a085", "#7f8c8d");

    /**
     * Resolve one card's value along one axis: the card's own frontmatter
     * field of the same name wins; the folder-cascaded {@code ctx.vars()}
     * entry (from a {@code paperband.yaml axis: {name: value}} binding) is
     * the fallback.
     *
     * <p>Both paths existed before this method unified them, but only the
     * frontmatter path was ever documented ({@link Axis}'s own javadoc:
     * "cards classify themselves ... by frontmatter field of the same
     * name"), while only the {@code vars} path was ever wired into grouping,
     * dividers, and site pages. A card that set its axis value only via its
     * own frontmatter used to badge itself correctly on its own page while
     * being invisible to every landing page and divider for that axis — this
     * one rule, used everywhere below, is what fixes that desync.
     */
    private static Object resolveAxisValue(Axis axis, Card card, RenderContext ctx) {
        Object fromCard = card.frontmatter().values().get(axis.name());
        if (fromCard != null) return fromCard;
        return ctx.vars().get(axis.name());
    }

    /**
     * Values are keyed by their string form for grouping/lookup, so YAML type
     * wobble between a card's frontmatter (e.g. {@code tier: 1}, parsed as an
     * {@code Integer}) and a declared axis value's id doesn't split one
     * logical value into two separate, silently-orphaned groups.
     */
    private static String normalizeAxisId(Object id) {
        return id == null ? null : String.valueOf(id);
    }

    /**
     * Per-axis grouping computed once per site/book render: each card's
     * resolved value along this axis (aligned with the supplied card list;
     * null when the card has no value for this axis), cards grouped by that
     * value (keyed by {@link #normalizeAxisId}), and ready-to-render
     * per-value metadata in {@link Axis#values()} declaration order (so an
     * empty group still gets a page), followed by any value actually found
     * on a card but never declared in the axis's config.
     */
    private record AxisGrouping(
            Axis axis,
            List<Object> perCardValue,
            Map<String, List<Integer>> byValue,
            List<Map<String, Object>> valueMetas) {

        /** This card's resolved value metadata (id/label/color/count), or null if it has none for this axis. */
        Map<String, Object> metaFor(int cardIndex) {
            String key = normalizeAxisId(perCardValue.get(cardIndex));
            if (key == null) return null;
            for (Map<String, Object> m : valueMetas) {
                if (key.equals(normalizeAxisId(m.get("id")))) return m;
            }
            return null;
        }
    }

    /** Compute one {@link AxisGrouping} per declared book axis that has at least one declared value. */
    private static List<AxisGrouping> computeAxisGroupings(
            RenderContext bookCtx, List<Card> cards, List<RenderContext> contexts) {
        List<AxisGrouping> out = new ArrayList<>();
        for (Axis axis : bookCtx.book().axes()) {
            if (axis.values().isEmpty()) continue;
            List<Object> perCard = new ArrayList<>(cards.size());
            for (int i = 0; i < cards.size(); i++) {
                perCard.add(resolveAxisValue(axis, cards.get(i), contexts.get(i)));
            }
            Map<String, List<Integer>> byValue = new LinkedHashMap<>();
            for (int i = 0; i < perCard.size(); i++) {
                String key = normalizeAxisId(perCard.get(i));
                if (key == null) continue;
                byValue.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
            out.add(new AxisGrouping(axis, perCard, byValue, buildAxisValueMetas(axis, byValue)));
        }
        return out;
    }

    /**
     * One metadata map per axis value: declared values first (in
     * {@link Axis#values()} order, so a zero-count value still appears on
     * the site), then any value actually found on a card but never declared
     * in the book's {@code axes:} config — surfaced with a generated
     * label/colour rather than silently dropped. (The previous tier-only
     * code dropped these: a card with an undeclared tier value would badge
     * itself but never appear on any tier landing page — same class of bug
     * as the frontmatter/vars desync {@link #resolveAxisValue} fixes.)
     */
    private static List<Map<String, Object>> buildAxisValueMetas(Axis axis, Map<String, List<Integer>> byValue) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        int position = 0;
        for (AxisValue v : axis.values()) {
            String key = normalizeAxisId(v.id());
            if (key == null || !covered.add(key)) {
                position++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id());
            m.put("label", v.label() == null ? axis.title() + " " + v.id() : v.label());
            m.put("color", axisValueColor(v, position));
            m.put("count", byValue.getOrDefault(key, List.of()).size());
            out.add(m);
            position++;
        }
        for (var e : byValue.entrySet()) {
            if (covered.contains(e.getKey())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("label", axis.title() + " " + e.getKey());
            m.put("color", axisValueColor(null, position));
            m.put("count", e.getValue().size());
            out.add(m);
            position++;
        }
        return out;
    }

    private static String axisValueColor(AxisValue v, int position) {
        if (v != null) {
            Object c = v.meta().get("color");
            if (c != null) return c.toString();
        }
        return DEFAULT_AXIS_PALETTE.get(position % DEFAULT_AXIS_PALETTE.size());
    }

    private static boolean hasAnyAxisValue(int cardIndex, List<AxisGrouping> groupings) {
        for (AxisGrouping g : groupings) {
            if (g.perCardValue().get(cardIndex) != null) return true;
        }
        return false;
    }

    /** A card's resolved value metadata across every axis, keyed by axis name; used for the card model's {@code axes} map. */
    private static Map<String, Object> cardAxesFromGroupings(int cardIndex, List<AxisGrouping> groupings) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (AxisGrouping g : groupings) {
            Map<String, Object> meta = g.metaFor(cardIndex);
            if (meta != null) out.put(g.axis().name(), meta);
        }
        return out;
    }

    /**
     * Same shape as {@link #cardAxesFromGroupings}, computed without a
     * precomputed book-wide grouping — used by the single-card render path
     * ({@link #buildModel}), which has no full card list to group against.
     * Colour/label derivation matches {@link #buildAxisValueMetas} exactly
     * (declared value found by position, else a generated fallback) so a
     * card's own badge never disagrees with its axis's landing page.
     */
    private static Map<String, Object> resolveCardAxes(Card card, RenderContext ctx, List<Axis> axes) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Axis axis : axes) {
            if (axis.values().isEmpty()) continue;
            Object raw = resolveAxisValue(axis, card, ctx);
            if (raw == null) continue;
            out.put(axis.name(), resolveCardAxisMeta(axis, raw));
        }
        return out;
    }

    private static Map<String, Object> resolveCardAxisMeta(Axis axis, Object rawValue) {
        String key = normalizeAxisId(rawValue);
        int position = 0;
        for (AxisValue v : axis.values()) {
            if (key.equals(normalizeAxisId(v.id()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", v.id());
                m.put("label", v.label() == null ? axis.title() + " " + v.id() : v.label());
                m.put("color", axisValueColor(v, position));
                return m;
            }
            position++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rawValue);
        m.put("label", axis.title() + " " + rawValue);
        m.put("color", axisValueColor(null, position));
        return m;
    }

    private static Map<String, Object> buildStats(List<Card> cards, List<AxisGrouping> groupings) {
        int openrewrite = 0;
        for (Card c : cards) {
            if (truthy(c.frontmatter().values().get("openrewrite"))) openrewrite++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", cards.size());
        stats.put("openrewrite", openrewrite);
        Map<String, Object> byAxis = new LinkedHashMap<>();
        for (AxisGrouping g : groupings) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map<String, Object> v : g.valueMetas()) {
                counts.put(String.valueOf(v.get("id")), (Integer) v.get("count"));
            }
            byAxis.put(g.axis().name(), counts);
        }
        stats.put("byAxis", byAxis);
        return stats;
    }

    /** Subset of cardModel suitable for grid items + nav links. */
    private static Map<String, Object> siteCardSummary(Card card, List<AxisGrouping> groupings, int cardIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", card.id());
        m.put("title", card.title());
        m.put("axes", cardAxesFromGroupings(cardIndex, groupings));
        Map<String, Object> fm = card.frontmatter().values();
        m.put("oneliner", fm.get("oneliner"));
        m.put("effort", fm.get("effort"));
        m.put("openrewrite", truthy(fm.get("openrewrite")));
        m.put("subsystem", fm.get("subsystem"));
        return m;
    }

    /** Even leaner: just enough for prev/next links. */
    private static Map<String, Object> siteCardLink(Card card, List<AxisGrouping> groupings, int cardIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", card.id());
        m.put("title", card.title());
        m.put("axes", cardAxesFromGroupings(cardIndex, groupings));
        return m;
    }

    /**
     * Build the row models for a tech-index page's auto-cards block.
     *
     * <p>Triggered when a card's frontmatter sets {@code auto_cards: true}.
     * Returns every card in the book whose {@code tech} frontmatter list
     * overlaps with the host card's {@code tech} list, sorted by the book's
     * first declared axis (if any) — matching the previous tier-only
     * behaviour's intent ("most severe first") without hardcoding which axis
     * plays that role. Cards with no value on that axis sort after cards
     * that have one; if the book declares no axes at all, the original book
     * order is kept.
     */
    private static List<Map<String, Object>> buildAutoCardsList(
            Card techCard,
            List<Card> allCards,
            List<AxisGrouping> groupings) {
        List<String> myTech = readStringList(techCard.frontmatter().values().get("tech"));
        if (myTech.isEmpty()) return List.of();

        AxisGrouping sortAxis = groupings.isEmpty() ? null : groupings.get(0);

        record Match(int idx, Card card) {}
        List<Match> matched = new ArrayList<>();
        for (int i = 0; i < allCards.size(); i++) {
            Card c = allCards.get(i);
            List<String> cardTech = readStringList(c.frontmatter().values().get("tech"));
            if (anyOverlap(cardTech, myTech)) {
                matched.add(new Match(i, c));
            }
        }

        if (sortAxis != null) {
            matched.sort((a, b) -> {
                Map<String, Object> ma = sortAxis.metaFor(a.idx());
                Map<String, Object> mb = sortAxis.metaFor(b.idx());
                if (ma == null && mb == null) return Integer.compare(a.idx(), b.idx());
                if (ma == null) return 1;   // no value on the sort axis sorts after cards that have one
                if (mb == null) return -1;
                int cmp = sortAxis.valueMetas().indexOf(ma) - sortAxis.valueMetas().indexOf(mb);
                if (cmp != 0) return cmp;
                return Integer.compare(a.idx(), b.idx());
            });
        }

        List<Map<String, Object>> rows = new ArrayList<>(matched.size());
        for (Match m : matched) {
            rows.add(techRowModel(m.card(), sortAxis, m.idx()));
        }
        return rows;
    }

    /**
     * Row model consumed by {@code _tech-row.html}.
     *
     * <p>Mirrors {@link #siteCardSummary} but adds the book's sort axis
     * label/colour (when one is declared) and impact/verify fields. Kept
     * separate so the by-technology row template can evolve independently of
     * the grid cards on axis-value and section pages.
     */
    private static Map<String, Object> techRowModel(Card card, AxisGrouping sortAxis, int cardIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", card.id());
        m.put("title", card.title());
        Map<String, Object> sortValue = sortAxis == null ? null : sortAxis.metaFor(cardIndex);
        m.put("sortValue", sortValue);
        Map<String, Object> fm = card.frontmatter().values();
        m.put("oneliner",    fm.get("oneliner"));
        m.put("impact",      fm.get("impact"));
        m.put("effort",      fm.get("effort"));
        m.put("openrewrite", truthy(fm.get("openrewrite")));
        m.put("verify",      truthy(fm.get("verify")));
        return m;
    }

    /** Read a frontmatter value that may be a string, a list of strings, or null into a {@code List<String>}. */
    private static List<String> readStringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        return List.of(v.toString());
    }

    /** True iff the two lists share at least one element. */
    private static boolean anyOverlap(List<String> a, List<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    /**
     * Extract the template file and line number from a Pebble exception chain
     * so build failures can point straight at the offending {@code .html}
     * source. Walks the cause chain because Pebble sometimes wraps the
     * originating {@link PebbleException} inside another {@code PebbleException}
     * (e.g. when a parent template evaluation surfaces a child template's
     * AttributeNotFoundException). Returns the deepest non-null location.
     *
     * <p>Returned format is {@code " at <filename>:<line>"} (leading space,
     * empty when no location is available) so callers can splice it directly
     * into an error message.
     */
    private static String locationOf(Throwable t) {
        String filename = null;
        Integer line = null;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof PebbleException pe) {
                if (pe.getFileName() != null) filename = pe.getFileName();
                if (pe.getLineNumber() != null) line = pe.getLineNumber();
            }
        }
        if (filename == null && line == null) return "";
        StringBuilder sb = new StringBuilder(" at ");
        sb.append(filename == null ? "?" : filename);
        sb.append(':');
        sb.append(line == null ? "?" : line);
        return sb.toString();
    }

    /**
     * Prefer Pebble's structured message ({@code getPebbleMessage()}) over the
     * default {@code getMessage()} because the latter prepends the same
     * location info we've already extracted, producing duplicate
     * "at <filename>:<line>" fragments in the final error message.
     */
    /**
     * {@link #messageOf} plus, for the one failure that reliably misleads, the
     * places a template was actually looked for.
     *
     * <p>Pebble reports only the name it couldn't resolve, which is a path
     * relative to the book's {@code layouts/} directory (see
     * {@link NamedTemplates#templateName}) — so the bare message names
     * something that looks unrelated to what the author wrote, and says nothing
     * about where it was expected to be.
     */
    private String explain(Throwable t) {
        String message = messageOf(t);
        if (message == null || !message.contains("Could not find template")) return message;
        StringBuilder hint = new StringBuilder(message).append(" — looked in: ");
        if (!theme.isEmpty() && theme.templateLoader() != null) {
            hint.append("theme '").append(theme.name()).append("' overrides, then ");
        }
        if (bookRoot != null) {
            hint.append(bookRoot.resolve(NamedTemplates.LAYOUTS_DIR)).append("/<name>.html, then ");
        }
        hint.append("the bundled templates. A book's own templates live under its ")
                .append(NamedTemplates.LAYOUTS_DIR)
                .append("/ directory and are named by their path relative to it, so declare "
                        + "the path as the file sits there — or name a bundled template.");
        return hint.toString();
    }

    private static String messageOf(Throwable t) {
        Throwable deepest = t;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof PebbleException) deepest = cur;
        }
        if (deepest instanceof PebbleException pe && pe.getPebbleMessage() != null) {
            return pe.getPebbleMessage();
        }
        return deepest.getMessage();
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    /** Like {@link #truthy} but returns {@code defaultValue} when {@code v} is null. */
    private static boolean truthyOrDefault(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        return truthy(v);
    }

    private static String layoutName(RenderContext ctx) {
        if (ctx.layout() == null) return DEFAULT_LAYOUT;
        String filename = ctx.layout().getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private Map<String, Object> buildModel(Card card, RenderContext ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("card", cardModel(card, resolveCardAxes(card, ctx, ctx.book().axes())));
        model.put("ctx", contextModel(ctx));
        model.put("vars", LenientMap.of(ctx.vars()));
        model.put("target", ctx.target());
        model.put("size", ctx.size());
        model.put("fontScale", ctx.fontScale());
        model.put("orientation", ctx.pageSpec().orientation().name().toLowerCase());
        model.put("contentHeightMm", ctx.pageSpec().contentHeightMm());
        model.put("pageMarginsMm", pageMarginsModel(ctx.pageSpec()));
        model.put("measure", resolveMeasure(ctx.vars()));
        model.put("css", composeCss(ctx.cssChain()));
        return model;
    }

    private Map<String, Object> buildBookModel(
            List<Card> cards,
            List<RenderContext> contexts,
            RenderContext bookCtx) {
        Map<String, Object> model = new HashMap<>();
        model.put("edition", edition);   // null for plain builds; see setEdition

        List<AxisGrouping> groupings = computeAxisGroupings(bookCtx, cards, contexts);
        Map<String, Object> stats = buildStats(cards, groupings);
        Map<String, Object> bookModel = bookSiteModel(bookCtx);

        // Attach a slim card-list to each axis value's metadata entry so the
        // divider page can render a per-value table of contents.
        for (AxisGrouping g : groupings) {
            for (Map<String, Object> value : g.valueMetas()) {
                List<Integer> indices = g.byValue().getOrDefault(normalizeAxisId(value.get("id")), List.of());
                List<Map<String, Object>> valueCards = new ArrayList<>(indices.size());
                for (int i : indices) {
                    Card c = cards.get(i);
                    Map<String, Object> sum = new LinkedHashMap<>();
                    sum.put("id", c.id());
                    sum.put("title", c.title());
                    sum.put("oneliner", c.frontmatter().values().get("oneliner"));
                    sum.put("effort", c.frontmatter().values().get("effort"));
                    valueCards.add(sum);
                }
                value.put("cards", valueCards);
            }
        }

        // Plain "section" groupings — the axis-less fallback (see the Sections
        // doc in CLAUDE.md): any top-level folder whose cards have no value on
        // ANY declared axis. Mirrors renderSite's bySection/buildSectionMetas
        // treatment so the book/PDF gets a divider page per section too, not
        // just per axis value. A card is never in both an axis group and a
        // section, so this and the axis dividers above are mutually exclusive
        // per card.
        Path bookRoot = bookCtx.book().bookRoot();
        List<Part> bookParts = bookCtx.book().parts();
        Map<String, List<Integer>> bookBySection = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            if (hasAnyAxisValue(i, groupings)) continue;
            String secId = sectionIdFor(bookRoot, bookParts, cards.get(i).source());
            if (secId == null) continue;
            bookBySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        // Reuse the same book-default/per-folder template resolution as the
        // site (there's no separate "PDF template" config) so a section
        // resolved to the minimal preset gets a minimal PDF divider too —
        // _section-divider-base.html reads the "minimal" flag this sets.
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bookBySection, bookRoot, bookParts, bookCtx.book().sectionLandingTemplate(), new HashMap<>());
        for (Map<String, Object> section : sectionMetas) {
            String id = (String) section.get("id");
            List<Integer> indices = bookBySection.getOrDefault(id, List.of());
            List<Map<String, Object>> secCards = new ArrayList<>(indices.size());
            for (int i : indices) {
                Card c = cards.get(i);
                Map<String, Object> sum = new LinkedHashMap<>();
                sum.put("id", c.id());
                sum.put("title", c.title());
                sum.put("oneliner", c.frontmatter().values().get("oneliner"));
                sum.put("effort", c.frontmatter().values().get("effort"));
                secCards.add(sum);
            }
            section.put("cards", secCards);
        }

        // Enrich card models with axes + axesFirstOf so the template can drop
        // one divider per axis before the first card of each of that axis's
        // value groups — a card can be "first of value" for more than one
        // axis at once, in which case the template stacks a divider per axis,
        // in book.axes() declaration order. Axis-less cards instead get
        // sectionMeta + sectionFirst, driving the section-divider check.
        List<Map<String, Object>> cardModels = new ArrayList<>(cards.size());
        // Printed table of contents — built alongside the divider bookkeeping
        // below so its entries appear in the exact order the PDF assembles
        // pages: each divider when it fires, then the cards under it. Anchors
        // are the same named destinations the anchor-bait div links, which is
        // what lets the build's second render pass fill in real page numbers
        // (see PageRefs in the maven plugin).
        boolean wantToc = truthyVar(bookCtx.vars().get("toc"));
        List<Map<String, Object>> tocEntries = wantToc ? new ArrayList<>() : null;
        Map<String, String> prevValueKeyByAxis = new HashMap<>();
        String prevSectionId = null;
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> axesForCard = cardAxesFromGroupings(i, groupings);
            Map<String, Object> cm = cardModel(cards.get(i), axesForCard);
            Map<String, Boolean> firstOf = new LinkedHashMap<>();
            for (AxisGrouping g : groupings) {
                String key = normalizeAxisId(g.perCardValue().get(i));
                boolean first = key != null && !key.equals(prevValueKeyByAxis.get(g.axis().name()));
                firstOf.put(g.axis().name(), first);
                if (key != null) prevValueKeyByAxis.put(g.axis().name(), key);
                if (first && tocEntries != null
                        && axesForCard.get(g.axis().name()) instanceof Map<?, ?> valueMeta) {
                    tocEntries.add(tocEntry(
                            String.valueOf(valueMeta.get("label")),
                            "axis-divider-" + g.axis().name() + "-" + valueMeta.get("id"),
                            "divider", 0));
                }
            }
            cm.put("axesFirstOf", firstOf);

            Map<String, Object> sectionMeta = null;
            boolean sectionFirst = false;
            if (!hasAnyAxisValue(i, groupings)) {
                String secId = sectionIdFor(bookRoot, bookParts, cards.get(i).source());
                if (secId != null) {
                    sectionMeta = findSectionMeta(sectionMetas, secId);
                    sectionFirst = !secId.equals(prevSectionId);
                    prevSectionId = secId;
                    if (sectionFirst && tocEntries != null && sectionMeta != null
                            && Boolean.TRUE.equals(sectionMeta.get("landingPage"))) {
                        tocEntries.add(tocEntry(
                                String.valueOf(sectionMeta.get("label")),
                                "section-divider-" + secId, "divider", 0));
                    }
                }
            }
            cm.put("sectionMeta", sectionMeta);
            cm.put("sectionFirst", sectionFirst);
            if (tocEntries != null) {
                int depth = (sectionMeta != null || hasAnyAxisValue(i, groupings)) ? 1 : 0;
                tocEntries.add(tocEntry(
                        cards.get(i).title(), "card-" + cards.get(i).id(), "card", depth));
            }

            cardModels.add(cm);
        }

        model.put("toc", tocEntries);
        model.put("bookIndex",
                truthyVar(bookCtx.vars().get("index")) ? buildIndexModel(cards) : null);
        model.put("cards", cardModels);
        model.put("axisGroupings", axisGroupingsModel(groupings));
        model.put("sections", sectionMetas);
        model.put("stats", stats);
        model.put("book", bookModel);
        model.put("ctx", contextModel(bookCtx));
        model.put("vars", LenientMap.of(bookCtx.vars()));
        model.put("target", bookCtx.target());
        model.put("size", bookCtx.size());
        model.put("fontScale", bookCtx.fontScale());
        model.put("orientation", bookCtx.pageSpec().orientation().name().toLowerCase());
        model.put("contentHeightMm", bookCtx.pageSpec().contentHeightMm());
        model.put("pageMarginsMm", pageMarginsModel(bookCtx.pageSpec()));
        model.put("measure", resolveMeasure(bookCtx.vars()));
        model.put("css", composeCss(bookCtx.cssChain()));
        return model;
    }

    /** Pebble-facing shape for {@code book.html}'s divider loop and named-destination anchor list. */
    private static List<Map<String, Object>> axisGroupingsModel(List<AxisGrouping> groupings) {
        List<Map<String, Object>> out = new ArrayList<>(groupings.size());
        for (AxisGrouping g : groupings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("axis", axisMetaModel(g.axis()));
            m.put("values", g.valueMetas());
            out.add(m);
        }
        return out;
    }

    /**
     * A book-level feature toggle read from the vars cascade ({@code toc},
     * {@code index}). Vars arrive as yaml natives from {@code paperband.yaml}
     * but as strings from {@code <book><vars>}, so both spellings of true
     * count.
     */
    private static boolean truthyVar(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    /** One printed-TOC line: what to say, where it points, and how deep it sits. */
    private static Map<String, Object> tocEntry(String label, String anchor, String kind, int depth) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("label", label);
        e.put("anchor", anchor);
        e.put("kind", kind);
        e.put("depth", depth);
        return e;
    }

    /**
     * Back-of-book index model from each card's {@code index:} frontmatter —
     * a list (or comma-separated string) of terms the card wants indexed.
     * Terms group under their first letter (non-letters under {@code #}),
     * sorted case-insensitively; each term points at the cards that declared
     * it, in book order, via the same {@code card-<id>} anchors the TOC uses.
     *
     * @return letter groups: {letter, terms: [{term, refs: [{anchor, title}]}]},
     *         empty when no card declares any terms
     */
    private static List<Map<String, Object>> buildIndexModel(List<Card> cards) {
        Map<String, List<Map<String, Object>>> byTerm =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Card card : cards) {
            for (String term : indexTermsOf(card)) {
                List<Map<String, Object>> refs =
                        byTerm.computeIfAbsent(term, k -> new ArrayList<>());
                String anchor = "card-" + card.id();
                // A term repeated inside one card still gets one reference.
                if (refs.stream().anyMatch(r -> anchor.equals(r.get("anchor")))) continue;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("anchor", anchor);
                ref.put("title", card.title());
                refs.add(ref);
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> current = null;
        for (var e : byTerm.entrySet()) {
            char first = Character.toUpperCase(e.getKey().charAt(0));
            String letter = Character.isLetter(first) ? String.valueOf(first) : "#";
            if (current == null || !letter.equals(current.get("letter"))) {
                current = new LinkedHashMap<>();
                current.put("letter", letter);
                current.put("terms", new ArrayList<Map<String, Object>>());
                groups.add(current);
            }
            Map<String, Object> term = new LinkedHashMap<>();
            term.put("term", e.getKey());
            term.put("refs", e.getValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> terms = (List<Map<String, Object>>) current.get("terms");
            terms.add(term);
        }
        return groups;
    }

    /** A card's declared index terms: {@code index:} as a yaml list or a comma-separated string. */
    private static List<String> indexTermsOf(Card card) {
        Object raw = card.frontmatter().values().get("index");
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) addTerm(out, o);
        } else {
            for (String part : String.valueOf(raw).split(",")) addTerm(out, part);
        }
        return out;
    }

    private static void addTerm(List<String> out, Object o) {
        if (o == null) return;
        String s = String.valueOf(o).trim();
        if (!s.isEmpty()) out.add(s);
    }

    private static Map<String, Object> cardModel(Card card, Map<String, Object> axes) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", card.id());
        m.put("title", card.title());
        m.put("source", card.source().toString());
        // Wrap in LenientMap: frontmatter is sparse by design and templates
        // check it with {% if card.frontmatter.X %} guards expecting null on absence.
        m.put("frontmatter", LenientMap.of(card.frontmatter().values()));
        // axes is just as sparse — most cards have a value for only some (or
        // none) of the book's declared axes — so dot-access like
        // card.axes.tier must return null on absence rather than throwing,
        // same reasoning as frontmatter above.
        m.put("axes", LenientMap.of(axes));

        List<Map<String, Object>> blocks = new ArrayList<>(card.blocks().size());
        for (Block b : card.blocks()) {
            blocks.add(blockModel(b));
        }
        m.put("blocks", blocks);
        // Slot-based templates pull blocks out of this tracker instead of
        // looping card.blocks; after the render the engine checks it (see
        // checkSlots). Templates that never touch it are unaffected.
        m.put("slots", new SlotTracker(blocks));
        return m;
    }

    /**
     * Post-render slot accounting. For each full card model whose template
     * actually used {@code card.slots}, every top-level block must have been
     * consumed and every {@code require(...)} satisfied — otherwise fail with
     * one aggregated {@link SlotPlacementException} covering every offending
     * card, so authors see all structural problems in a single build.
     */
    private static void checkSlots(String layoutName, List<Map<String, Object>> cardModels) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> cm : cardModels) {
            if (!(cm.get("slots") instanceof SlotTracker t) || !t.used()) continue;
            List<Map<String, Object>> unplaced = t.unplaced();
            List<String> missing = t.missingRequired();
            if (unplaced.isEmpty() && missing.isEmpty()) continue;
            sb.append("Slot placement failed in card '").append(cm.get("id"))
                    .append("' (layout '").append(layoutName).append("'):\n");
            for (Map<String, Object> b : unplaced) {
                Object heading = b.get("heading");
                sb.append("  unplaced: ")
                        .append(heading != null ? "\"" + heading + "\"" : "(intro)")
                        .append(" (classes [").append(b.get("classAttr")).append("]");
                if (b.get("id") != null) sb.append(", id ").append(b.get("id"));
                sb.append(")\n");
            }
            for (String name : missing) {
                sb.append("  missing required slot: ").append(name).append("\n");
            }
        }
        if (sb.length() > 0) {
            sb.append("Add a matching card.slots.take(...) to the layout, "
                    + "or a card.slots.rest() catch-all for unexpected blocks.");
            throw new SlotPlacementException(sb.toString());
        }
    }

    /**
     * Recursively convert a {@link Block} (and its {@link Block#children()})
     * into the Pebble-facing model shape. {@code _block-section.html} renders
     * one level and self-includes for each entry in {@code children}, so the
     * nesting here has to survive all the way down, not just at the top.
     */
    private static Map<String, Object> blockModel(Block b) {
        Map<String, Object> bm = new HashMap<>();
        bm.put("kind", b.kind().name());
        bm.put("id", b.id());
        bm.put("classes", new ArrayList<>(b.classes()));
        bm.put("classAttr", String.join(" ", b.classes()));
        bm.put("heading", b.heading());
        bm.put("level", b.level());
        bm.put("html", b.html());
        List<Map<String, Object>> children = new ArrayList<>(b.children().size());
        for (Block c : b.children()) {
            children.add(blockModel(c));
        }
        bm.put("children", children);
        return bm;
    }

    private static Map<String, Object> contextModel(RenderContext ctx) {
        Map<String, Object> m = new HashMap<>();
        m.put("target", ctx.target());
        m.put("size", ctx.size());
        m.put("layout", ctx.layout() == null ? null : ctx.layout().toString());

        Map<String, Object> book = new HashMap<>();
        book.put("title", ctx.book().title());
        book.put("root", ctx.book().bookRoot() == null ? null : ctx.book().bookRoot().toString());
        m.put("book", book);

        return m;
    }

    private static String inlineCss(List<Path> chain) {
        StringBuilder out = new StringBuilder();
        for (Path p : chain) {
            if (!Files.isRegularFile(p)) {
                out.append("/* missing: ").append(p).append(" */\n");
                continue;
            }
            try {
                out.append("/* === ").append(p.getFileName()).append(" === */\n");
                out.append(Files.readString(p, StandardCharsets.UTF_8));
                if (!out.toString().endsWith("\n")) out.append("\n");
            } catch (IOException e) {
                out.append("/* failed to read ").append(p).append(": ").append(e.getMessage()).append(" */\n");
            }
        }
        return out.toString();
    }

    /**
     * Inline the user CSS chain, then append the active theme's stylesheets so
     * they win on cascade order without needing to bump selector specificity.
     */
    /**
     * The built-in, theme-neutral scaffold stylesheet for static-site pages,
     * loaded from the classpath. Returns an empty string (with a marker comment)
     * if the resource is somehow absent, so a packaging slip degrades gracefully
     * rather than throwing mid-render.
     */
    /** CSS lengths a {@code page.measure} may be expressed in, plus {@code none}. */
    private static final java.util.regex.Pattern MEASURE_PATTERN =
            java.util.regex.Pattern.compile("(?i)none|[0-9]+(\\.[0-9]+)?(rem|em|mm|cm|in|pt|px|%)");

    /**
     * The book's {@code vars.page.measure} — the text measure (line length)
     * themes read as {@code --card-max-width}.
     *
     * <p>It is stamped inline on {@code <html>} rather than added to the CSS
     * chain because of where the chain puts a book's own stylesheet: theme CSS
     * is inlined <em>after</em> it, so a theme's {@code :root
     * { --card-max-width: 38rem }} wins over a book that tries to set the same
     * property in its css. An inline style on the element outranks any
     * selector, so declaring the measure here is the one way a book retunes a
     * themed measure without resorting to {@code !important}.
     *
     * <p>This is the largest of the insets that make a full-bleed page still
     * look margined — a 38rem measure on a 210mm page leaves 23mm of gutter
     * either side, which is a line-length decision rather than a margin, and
     * only the book can say whether it wants it.
     *
     * @param vars the resolved vars for this render
     * @return the measure as a CSS length, or null when the book declares none
     *         (leaving the theme's own default in force)
     * @throws IllegalArgumentException if the value isn't a plain CSS length —
     *         it lands in a style attribute, so it is never passed through
     *         unvalidated
     */
    private static String resolveMeasure(Map<String, Object> vars) {
        Object page = vars == null ? null : vars.get("page");
        if (!(page instanceof Map<?, ?> pm)) return null;
        Object measure = pm.get("measure");
        if (measure == null) return null;
        String value = measure.toString().trim();
        if (value.isEmpty()) return null;
        if (!MEASURE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "page.measure: expected a CSS length (e.g. 42rem, 160mm) or 'none', got: " + value);
        }
        return value;
    }

    /**
     * The build's page margins in millimetres, keyed for the template that
     * stamps them as {@code --pw-page-margin-*} custom properties. Themes
     * read them to size insets the page margin isn't already providing — see
     * {@link dev.noregressions.paperband.render.PageSpec#marginsMm()}.
     */
    private static Map<String, Object> pageMarginsModel(
            dev.noregressions.paperband.render.PageSpec pageSpec) {
        double[] mm = pageSpec.marginsMm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("top", mm[0]);
        out.put("right", mm[1]);
        out.put("bottom", mm[2]);
        out.put("left", mm[3]);
        return out;
    }

    private static String siteBaseCss() {
        try (java.io.InputStream in =
                     LayoutEngine.class.getResourceAsStream("/site/site-base.css")) {
            if (in == null) return "/* site-base.css not found on classpath */\n";
            return "/* === site-base.css === */\n"
                    + new String(in.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (IOException e) {
            return "/* failed to read site-base.css: " + e.getMessage() + " */\n";
        }
    }

    /**
     * Stylesheets inlined <em>after</em> the theme's own — the strongest layer
     * in the cascade.
     *
     * <p>The book's {@code css:} chain goes first and the theme second, which
     * is what lets a theme restyle any book. That leaves nothing above the
     * theme, so a build that wants to correct one of its rules has to resort to
     * {@code !important}. This layer is that missing level: stylesheets named
     * by the build (the Maven plugin's {@code <stylesheets>}), applied last
     * because they're the most specific thing anyone declared.
     */
    private List<Path> extraCss = List.of();

    /**
     * Declare the build's own stylesheets, inlined after the theme.
     *
     * @param stylesheets css files in application order; null or empty clears
     */
    public void setExtraCss(List<Path> stylesheets) {
        this.extraCss = stylesheets == null ? List.of() : List.copyOf(stylesheets);
    }

    private String composeCss(List<Path> chain) {
        StringBuilder sb = new StringBuilder(inlineCss(chain));
        for (String css : theme.stylesheets()) {
            sb.append(css);
            if (!css.endsWith("\n")) sb.append('\n');
        }
        sb.append(inlineCss(extraCss));
        return sb.toString();
    }
}
