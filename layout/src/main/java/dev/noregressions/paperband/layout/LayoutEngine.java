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
import dev.noregressions.paperband.config.SectionFolderConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.OutlineEntry;
import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.number.Numbering;
import dev.noregressions.paperband.number.SectionNumbering;
import dev.noregressions.paperband.model.PlacedPage;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.model.Watermark;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.pebble.LenientMap;
import dev.noregressions.paperband.pebble.LenientMapExtension;
import dev.noregressions.paperband.render.WatermarkHtml;


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
    /**
     * Where the book's own templates live — the POM-decided geography, or the
     * legacy {@code <bookRoot>/layouts} when the geography wasn't split. Also
     * kept for diagnostics: naming the dir a missing template should have
     * been in, and the base declared template paths strip against.
     */
    private final Path layoutsDir;

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

    /**
     * Declared position of the printed table of contents: the index of the
     * card the contents page renders in front of ({@code cards.size()} puts
     * it after the last card). Setting a position also turns the TOC on, the
     * way {@code vars.toc: true} does; null (the default) leaves placement to
     * the vars toggle alone — up front, before the first card. Same mutable-
     * field-not-parameter reasoning as {@link #setEdition}.
     */
    private Integer tocAt;

    /**
     * Place the printed table of contents for subsequent book renders
     * (a {@code <toc/>} marker inside a POM-declared {@code <sections>}).
     * @param cardIndex the card the contents page precedes, or null for none
     */
    public void setTocAt(Integer cardIndex) {
        this.tocAt = cardIndex;
    }

    /**
     * The book's bookmark tree, as of the last {@link #renderBook} call — the
     * structure a PDF viewer's outline pane shows, and the input the maven
     * plugin's {@code PdfOutline} writes into the finished file.
     *
     * <p>Same entries the printed contents page is made of (dividers, cards,
     * the index), whether or not the book prints one: a contents page is a
     * choice about paper, bookmarks are navigation, and a book can sensibly
     * want either without the other.
     */
    private List<OutlineEntry> outline = List.of();

    /**
     * The bookmark tree for the book most recently rendered by
     * {@link #renderBook}, in page order.
     *
     * <p>Empty before the first book render, and for card/site renders (a
     * single card has no book structure to bookmark). Anchors are named
     * destinations, so the caller resolves them against the rendered PDF
     * rather than against anything the engine knows — see {@code PdfOutline}.
     *
     * @return the outline entries, top-level and depth-1, in page order
     */
    public List<OutlineEntry> outline() {
        return outline;
    }

    /**
     * Card ids the book holds that this build leaves out — what a
     * {@code select:} or an edition filtered away.
     *
     * <p>Only used to tell a broken {@code card:} link apart from a deliberate
     * omission. Both fail; they need different words.
     */
    private java.util.Set<String> excludedCardIds = java.util.Set.of();

    /**
     * Tell the engine which cards the build filtered out.
     *
     * @param ids the excluded card ids, or null for none
     */
    public void setExcludedCardIds(java.util.Set<String> ids) {
        this.excludedCardIds = ids == null ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    /**
     * The watermark a goal resolved from its own parameters, when it had any.
     * Null means "whatever the book's vars say" — see {@link #watermarked}.
     */
    private Watermark watermark;

    /**
     * Override the book's own {@code vars.watermark} for this render.
     *
     * <p>The {@code site} goal resolves {@code <watermark>} and its knob
     * parameters the same way {@code build} does and hands the answer in, so a
     * one-off {@code -Dpaperband.watermark="REVIEW COPY"} marks the site and the
     * PDF alike. Left unset, the book's vars still apply.
     *
     * @param watermark the resolved spec, or null to fall back to vars
     */
    public void setWatermark(Watermark watermark) {
        this.watermark = watermark;
    }

    /**
     * Generated pages placed into the card flow ({@code <page>} markers inside
     * a POM-declared {@code <sections>}): each template renders with the whole
     * book model in scope — the same context {@code book.html} sees — in front
     * of the card its index names ({@code cards.size()} puts it after the last
     * card). Empty (the default) renders none.
     */
    private List<PlacedPage> pagesAt = List.of();

    /**
     * Place generated pages for subsequent book renders.
     * @param pages the pages, each with its card index resolved; null clears
     */
    public void setPagesAt(List<PlacedPage> pages) {
        this.pagesAt = pages == null ? List.of() : List.copyOf(pages);
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
        this(bookRoot, bookRoot == null ? null : bookRoot.resolve("layouts"), theme);
    }

    /**
     * Construct an engine with the geography split: the book's templates come
     * from {@code layoutsDir} (the POM-decided location, defaulting to
     * {@code src/main/paperband/layouts}) rather than being derived from the
     * content root. Section grouping and card ids keep reading the content
     * root through the model's {@link dev.noregressions.paperband.model.BookConfig#bookRoot()}.
     *
     * @param bookRoot   the content root, or null
     * @param layoutsDir the book's templates directory, or null for none
     * @param theme      the theme bundle
     */
    public LayoutEngine(Path bookRoot, Path layoutsDir, ThemeBundle theme) {
        this.theme = (theme == null) ? ThemeBundle.NONE : theme;
        this.layoutsDir = layoutsDir == null ? null : layoutsDir.toAbsolutePath().normalize();
        this.engine = buildEngine(this.layoutsDir, this.theme);
    }

    private static PebbleEngine buildEngine(Path layoutsDir, ThemeBundle theme) {
        ClasspathLoader cp = new ClasspathLoader();
        cp.setPrefix("templates/");
        cp.setSuffix(".html");

        List<Loader<?>> chain = new ArrayList<>();
        if (theme.templateLoader() != null) chain.add(theme.templateLoader());
        if (layoutsDir != null && Files.isDirectory(layoutsDir)) {
            // Pebble 4.1+ requires the prefix (an absolute path) at construction
            // time; setPrefix() alone is no longer sufficient (also now rejects
            // non-absolute paths — part of the CVE-2025-1686 traversal fix).
            FileLoader fs = new FileLoader(layoutsDir.toString() + "/");
            fs.setSuffix(".html");
            chain.add(fs);
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
        // One card is a preview of one card: resolve its references to the
        // print form, but don't check them against a book that isn't here.
        return CardLinks.of(List.of(card)).preview(html);
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
        return CardLinks.of(cards, excludedCardIds)
                .withNumbers(cardNumbers).print(html);
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
        List<Section> declaredSections = book.sections();

        // Same section discovery as buildBookModel: every card with a
        // resolvable section belongs to it, axis-labelled or not.
        Map<String, List<Integer>> bySection = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            String secId = sectionIdFor(bookRoot, declaredSections, cards.get(i).source());
            if (secId == null) continue;
            bySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bySection, bookRoot, declaredSections, book.sectionLandingTemplate(), new HashMap<>(),
                Map.of());   // the structure outline lists shape, not prose

        // The same index-term resolution the PDF's index page uses, so this
        // outline is the cheap way to review what `index: auto` picked —
        // veto a bad term with vars.indexStop and re-run, no render needed.
        Map<String, List<String>> indexTerms = resolvedIndexTerms(cards, bookCtx.vars());

        Map<String, String> prevValueKeyByAxis = new HashMap<>();
        String prevSectionId = null;
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            boolean grouped = false;

            // Axis dividers — one per axis this card is first-of-value for,
            // stacked in axes: declaration order (mirrors axesFirstOf).
            boolean axisDividerHere = false;
            for (AxisGrouping g : groupings) {
                String key = normalizeAxisId(g.perCardValue().get(i));
                if (key == null) continue;
                grouped = true;
                if (g.axis().dividers() && !key.equals(prevValueKeyByAxis.get(g.axis().name()))) {
                    axisDividerHere = true;
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

            // Section divider (mirrors sectionFirst): fires on the section's
            // first card unless an axis divider fired on that same card.
            String secId = sectionIdFor(bookRoot, declaredSections, card.source());
            if (secId != null) {
                grouped = true;
                if (!secId.equals(prevSectionId) && !axisDividerHere) {
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
            List<String> terms = indexTerms.getOrDefault(card.id(), List.of());
            if (!terms.isEmpty()) {
                sb.append(indent).append("  index: ").append(String.join(", ", terms)).append('\n');
            }
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
        String composedSiteCss = siteBaseCss() + composeCss(bookCtx.cssChain(), Target.SITE);
        String cssImports = extractCssImports(composedSiteCss);
        String css = stripCssImports(composedSiteCss);

        // The identity hook every site page stamps on <html>, mirroring what
        // book.html does for the PDF. Without it a theme has no way to say
        // "on the web, do this": noregressions ships an `html.target-web`
        // rule that never matched, so every site silently rendered at the
        // book's 10.5pt print scale.
        //
        // Deliberately NOT the `size-*` class the PDF stamps: themes hang
        // page-density type off it (`html.size-a4 { font-size: 10.5pt }`), and
        // a website has no page size to be. `paperband-site` is the stable
        // hook — `target-*` follows <siteTarget>, which a book may rename.
        String htmlClass = "paperband-site"
                + (bookCtx.target() == null ? "" : " target-" + bookCtx.target().toLowerCase());
        String measure = resolveMeasure(bookCtx.vars());
        Map<String, Object> bookModel = bookSiteModel(bookCtx);

        // Sections: any top-level folder under the book root (or under a
        // `content/` wrapper) whose cards have no value on ANY declared axis.
        // Each section gets its own landing page and a nav entry alongside
        // the axis value pages. Front matter, back matter, delaying-tactics,
        // etc. live here.
        Path bookRoot = bookCtx.book().bookRoot();
        List<Section> declaredSections = bookCtx.book().sections();
        Map<String, List<Integer>> bySection = new LinkedHashMap<>();
        Map<String, SectionFolderConfig> sectionFolderYamlCache = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            if (hasAnyAxisValue(i, groupings)) continue;
            String secId = sectionIdFor(bookRoot, declaredSections, cards.get(i).source());
            if (secId == null) continue;
            bySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bySection, bookRoot, declaredSections, bookCtx.book().sectionLandingTemplate(),
                sectionFolderYamlCache, sectionBodies);

        // The sidebar is book scope — structure, declared once, resolved by the
        // config loader (which also honours the deprecated vars spelling). It
        // used to be read straight off bookCtx.vars(), i.e. the vars of
        // whichever card was walked first, which made a whole-site switch
        // depend on walk order.
        dev.noregressions.paperband.model.Sidebar sidebarConfig = bookCtx.book().sidebar();
        boolean sidebar = sidebarConfig.enabled();
        boolean sidebarCollapsed = sidebarConfig.collapsed();
        boolean sidebarSectionsCollapsed = sidebarConfig.sectionsCollapsed();

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
        List<Map<String, Object>> navEntries = buildNavEntries(cards, groupings, bookRoot, declaredSections, sectionMetas);

        // A flat book — no axes, every card in the content root — groups into
        // nothing, so navEntries comes back empty and a declared sidebar would
        // render as an empty panel. Its cards are still a table of contents, so
        // the sidebar falls back to one unlabelled entry holding all of them.
        //
        // Deliberately a SEPARATE list: navEntries also drives the top nav and
        // the index's section grid, and neither wants a nameless catch-all tile.
        List<Map<String, Object>> sidebarEntries = navEntries;
        if (sidebar && navEntries.isEmpty() && !cards.isEmpty()) {
            List<Map<String, Object>> allCards = new ArrayList<>(cards.size());
            for (int i = 0; i < cards.size(); i++) {
                allCards.add(siteCardSummary(cards.get(i), groupings, i));
            }
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("kind", "section");
            flat.put("id", "all");
            flat.put("label", null);          // rendered as no heading at all
            flat.put("url", null);            // no landing page to link to
            flat.put("count", cards.size());
            flat.put("landingPage", Boolean.FALSE);
            flat.put("cards", allCards);
            sidebarEntries = List.of(flat);
        }

        Map<String, String> out = new LinkedHashMap<>();

        // index.html
        Map<String, Object> indexModel = new HashMap<>();
        indexModel.put("book", bookModel);
        indexModel.put("navEntries", navEntries);
        indexModel.put("sidebarEntries", sidebarEntries);
        indexModel.put("sections", sectionMetas);
        indexModel.put("axisGroupings", axisGroupingsModel(groupings));
        indexModel.put("stats", stats);
        indexModel.put("css", css);
        indexModel.put("cssImports", cssImports);
        indexModel.put("htmlClass", htmlClass);
        indexModel.put("bookBody", bookBodyHtml());
        indexModel.put("bookBodyKeepsDefault", bookBodyKeepsDefault());
        indexModel.put("measure", measure);
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
                    : templateNameOf(g.axis().landingTemplate());
            for (Map<String, Object> value : g.valueMetas()) {
                List<Integer> indices = g.byValue().getOrDefault(normalizeAxisId(value.get("id")), List.of());
                List<Map<String, Object>> valueCards = new ArrayList<>(indices.size());
                for (int i : indices) {
                    valueCards.add(siteCardSummary(cards.get(i), groupings, i));
                }
                Map<String, Object> valueModel = new HashMap<>();
                valueModel.put("book", bookModel);
                valueModel.put("navEntries", navEntries);
                valueModel.put("sidebarEntries", sidebarEntries);
                valueModel.put("sections", sectionMetas);
                valueModel.put("axis", axisMetaModel(g.axis()));
                valueModel.put("value", value);
                valueModel.put("cards", valueCards);
                valueModel.put("stats", stats);
            valueModel.put("css", css);
            valueModel.put("cssImports", cssImports);
            valueModel.put("htmlClass", htmlClass);
            valueModel.put("measure", measure);
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
        // declared section that opted out of having a page of its own. Its cards
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
                sectionModel.put("sidebarEntries", sidebarEntries);
            sectionModel.put("sections", sectionMetas);
            sectionModel.put("section", section);
            sectionModel.put("cards", sectionCards);
            sectionModel.put("stats", stats);
            sectionModel.put("css", css);
            sectionModel.put("cssImports", cssImports);
            sectionModel.put("htmlClass", htmlClass);
            sectionModel.put("measure", measure);
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
                String secId = sectionIdFor(bookRoot, declaredSections, card.source());
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
            cm.put("number", numberLabel(card.id()));
            model.put("book", bookModel);
            model.put("navEntries", navEntries);
            model.put("sidebarEntries", sidebarEntries);
            model.put("sections", sectionMetas);
            model.put("section", sectionMeta);
            model.put("axisBack", axisBack);
            model.put("card", cm);
            model.put("vars", LenientMap.of(contexts.get(i).vars()));
            model.put("prev", prev);
            model.put("next", next);
            model.put("stats", stats);
            model.put("css", css);
            model.put("cssImports", cssImports);
            model.put("htmlClass", htmlClass);
            // Whether this card has anything to put in the rail. Decided here
            // rather than in the template so the grid and the rail agree: an
            // empty rail beside a short page reads worse than no rail at all.
            model.put("hasRail", hasRail(card));
            model.put("measure", measure);
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

        CardLinks links = CardLinks.of(cards, excludedCardIds)
                .withNumbers(cardNumbers);
        for (Map.Entry<String, String> e : out.entrySet()) {
            e.setValue(links.site(e.getValue(), e.getKey()));
        }
        return watermarked(withContentAssets(out, bookCtx), bookCtx);
    }

    /**
     * Paint the book's {@code vars.watermark} over every site page.
     *
     * <p>Done to the finished pages rather than through the templates so that a
     * book with a hand-written theme, or a fully replaced {@code site-card},
     * gets the mark as well: a {@code DRAFT} that a custom template can silently
     * drop is worse than no watermark at all.
     *
     * <p>{@code pages:} is not honoured here — a site has no page one — and
     * neither is a print-only reading of the mark: the site copy is the one that
     * gets linked and forwarded, so if the book says DRAFT, the site says DRAFT.
     * A theme that wants it gone can hide {@code .pb-watermark}.
     *
     * @param pages   the rendered pages, keyed by output-relative path
     * @param bookCtx the book context whose vars carry the declaration
     * @return the same map, with the overlay injected into each page
     */
    /** Book-relative paths of the content images the last {@link #renderSite} rewrote. */
    private final LinkedHashSet<String> siteContentAssets = new LinkedHashSet<>();

    /** Refs the last {@link #renderSite} found no file for, for the caller to warn about. */
    private final LinkedHashSet<String> siteMissingAssets = new LinkedHashSet<>();

    /**
     * The content images the last {@link #renderSite} pointed at {@code assets/},
     * as book-root-relative paths — what the caller has to copy there for the
     * rewritten pages to resolve.
     *
     * <p>Rendering rewrites the markup but copies nothing: the engine composes
     * pages, and moving bytes into an output directory belongs to whatever is
     * writing that directory ({@code SiteMojo.copyContentAssets}). Same split
     * the cover already uses — see {@link #siteMatter}.
     *
     * @return the paths, in first-seen order; empty when no page referenced a
     *         local image
     */
    public Set<String> siteContentAssets() {
        return java.util.Collections.unmodifiableSet(siteContentAssets);
    }

    /**
     * Image references the last {@link #renderSite} resolved to no file on
     * disk. Left in the markup exactly as the author wrote them — a broken
     * image should look broken rather than silently point into {@code assets/}
     * — and reported here so the build can name them.
     *
     * @return the references, in first-seen order
     */
    public Set<String> siteMissingAssets() {
        return java.util.Collections.unmodifiableSet(siteMissingAssets);
    }

    /** Matches a whole {@code <img>} tag. */
    private static final java.util.regex.Pattern IMG_TAG =
            java.util.regex.Pattern.compile("(?i)<img\\b[^>]*>");

    /** Matches the {@code src="..."} inside one. */
    private static final java.util.regex.Pattern IMG_SRC =
            java.util.regex.Pattern.compile("(?i)\\bsrc\\s*=\\s*\"([^\"]*)\"");

    /**
     * Point every local content image at the site's {@code assets/} copy.
     *
     * <p>A card writes {@code ![alt](diagrams/gc.png)}, and the PDF resolves
     * that against the book root (the render's base URI). A site page lives at
     * {@code cards/<id>.html} and is served from wherever it was deployed, so
     * the same relative ref would look for {@code cards/diagrams/gc.png} and
     * 404. Rather than make authors write one path for print and another for
     * the web, the ref keeps meaning "from the book root" and this rewrites it
     * to {@code <urlPrefix>assets/diagrams/gc.png} — the tree mirrored under
     * {@code assets/}, so two cards can both have a {@code diagram.png}
     * without colliding.
     *
     * <p>Matched on the {@code <img>} tag rather than on {@code src="} alone,
     * which matters for a book that <em>documents</em> this: an example inside
     * a fenced block is escaped text ({@code &lt;img src="…"}) by the time it
     * gets here, so a tag regex cannot reach into it while a bare attribute
     * regex would have rewritten the example.
     *
     * <p>Left alone: remote refs, {@code data:} URIs, an author's absolute
     * {@code /path} (a deployment-root reference they chose deliberately),
     * anything already under {@code assets/} (the cover, back and watermark
     * copies the templates emit), and anything resolving outside the book root.
     *
     * @param pages   the rendered pages, keyed by output-relative path
     * @param bookCtx the book context, for the root refs resolve against
     * @return the same map, values rewritten
     */
    private Map<String, String> withContentAssets(Map<String, String> pages, RenderContext bookCtx) {
        siteContentAssets.clear();
        siteMissingAssets.clear();
        Path bookRoot = bookCtx.book() == null ? null : bookCtx.book().bookRoot();
        if (bookRoot == null) return pages;
        Path root = bookRoot.toAbsolutePath().normalize();
        for (Map.Entry<String, String> e : pages.entrySet()) {
            e.setValue(rewriteContentImages(e.getValue(), e.getKey(), root));
        }
        return pages;
    }

    /** {@link #withContentAssets} for one page. */
    private String rewriteContentImages(String html, String pageKey, Path root) {
        java.util.regex.Matcher tags = IMG_TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int last = 0;
        while (tags.find()) {
            String tag = tags.group();
            String replacement = tag;
            java.util.regex.Matcher src = IMG_SRC.matcher(tag);
            if (src.find()) {
                String ref = src.group(1);
                String rel = bookRelativeAsset(ref, root);
                if (rel != null) {
                    if (Files.isRegularFile(root.resolve(rel))) {
                        siteContentAssets.add(rel);
                        replacement = tag.substring(0, src.start(1))
                                + urlPrefixFor(pageKey) + SITE_ASSET_DIR + "/" + rel
                                + tag.substring(src.end(1));
                    } else {
                        siteMissingAssets.add(ref);
                    }
                }
            }
            out.append(html, last, tags.start()).append(replacement);
            last = tags.end();
        }
        out.append(html, last, html.length());
        return out.toString();
    }

    /**
     * The book-root-relative path an {@code <img src>} names, or null when it
     * is not a local content image this build should take over.
     *
     * @param ref  the raw attribute value
     * @param root the absolute, normalised book root
     * @return the relative path with {@code /} separators, or null
     */
    private static String bookRelativeAsset(String ref, Path root) {
        if (ref == null || ref.isBlank()) return null;
        String trimmed = ref.trim();
        if (trimmed.startsWith("data:") || trimmed.startsWith("#")) return null;
        if (trimmed.startsWith("//") || trimmed.contains("://")) return null;   // remote
        if (trimmed.startsWith("/")) return null;                               // author's own absolute ref
        // A query or fragment on a local file is not something to resolve.
        int cut = trimmed.indexOf('?');
        if (cut >= 0) trimmed = trimmed.substring(0, cut);
        cut = trimmed.indexOf('#');
        if (cut >= 0) trimmed = trimmed.substring(0, cut);
        if (trimmed.isEmpty()) return null;
        // Already ours: the templates emit the cover/back/watermark copies as
        // `<urlPrefix>assets/<file>`, and rewriting those would nest them.
        String bare = trimmed;
        while (bare.startsWith("../")) bare = bare.substring(3);
        if (bare.equals(SITE_ASSET_DIR) || bare.startsWith(SITE_ASSET_DIR + "/")) return null;
        try {
            Path resolved = root.resolve(trimmed).normalize();
            // Defence in depth, and the reason `../` is not simply stripped
            // above: a ref that climbs out of the book is not the build's to
            // copy, and must not be able to name a file outside it.
            if (!resolved.startsWith(root)) return null;
            String rel = root.relativize(resolved).toString().replace(java.io.File.separatorChar, '/');
            return rel.isEmpty() ? null : rel;
        } catch (RuntimeException ex) {
            return null;   // not a usable path: leave the ref as the author wrote it
        }
    }

    private Map<String, String> watermarked(Map<String, String> pages, RenderContext bookCtx) {
        Watermark watermark = this.watermark != null
                ? this.watermark
                : Watermark.fromYaml(bookCtx.vars().get("watermark"));
        if (watermark == null) return pages;
        for (Map.Entry<String, String> e : pages.entrySet()) {
            String imageUrl = watermark.hasImage()
                    ? urlPrefixFor(e.getKey()) + SITE_ASSET_DIR + "/"
                            + Path.of(watermark.image()).getFileName()
                    : null;
            e.setValue(WatermarkHtml.inject(e.getValue(),
                    WatermarkHtml.overlay(watermark, imageUrl, /*screenOnly=*/ false)));
        }
        return pages;
    }

    /**
     * The relative path back to the site root from an output-relative page key
     * — the same {@code urlPrefix} the templates are handed, recovered from the
     * key so a post-render pass doesn't need the model.
     *
     * @param pageKey e.g. {@code index.html} or {@code cards/intro.html}
     * @return {@code ""} for a root page, {@code "../"} per directory below it
     */
    static String urlPrefixFor(String pageKey) {
        int depth = 0;
        for (int i = 0; i < pageKey.length(); i++) {
            if (pageKey.charAt(i) == '/') depth++;
        }
        return "../".repeat(depth);
    }

    /** Strip a stored template path down to the bare name Pebble's loader expects (no extension). */
    /**
     * The template name for an axis's landing template — a path the config
     * already resolved, stripped against this engine's own layouts dir so
     * subdirectory names survive whatever geography placed the dir.
     */
    private String templateNameOf(Path landingTemplate) {
        return NamedTemplates.templateNameUnder(layoutsDir, landingTemplate);
    }

    /** Output filename stem for one axis value's landing page: {@code {axisName}-{valueId}}. */
    private static String axisPageId(Axis axis, Object valueId) {
        return axis.name() + "-" + valueId;
    }

    private static Map<String, Object> axisMetaModel(Axis axis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", axis.name());
        m.put("title", axis.title());
        m.put("dividers", axis.dividers());
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
     *       declared section that has no page of its own</li>
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
            List<Section> declaredSections,
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
            String secId = sectionIdFor(bookRoot, declaredSections, cards.get(i).source());
            if (secId == null) continue;
            String key = "section:" + secId;
            if (seen.containsKey(key)) continue;
            Map<String, Object> section = findSectionMeta(sectionMetas, secId);
            if (section == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>(section);
            entry.put("kind", "section");
            // A declared section with no page of its own has nothing to link to: its
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
     * <p>A declared section that claims this card <em>by path</em>
     * ({@link Section#cards()}, how the Maven plugin's pattern-declared
     * sections express membership) wins outright, before the folder is even
     * looked at: such a declaration exists precisely to group cards the
     * directory layout doesn't group, and two of them may draw different files
     * out of the same folder.
     */
    /**
     * Every card's chapter number, derived from book order.
     *
     * <p>Section resolution lives here, so numbering is assembled here too and
     * handed to {@link Numbering} — which owns the rules and knows nothing
     * about paths. The declarations come from each section's {@code _section.md}
     * frontmatter, carried on {@link SectionBody#numbering()}.
     *
     * <p>Cards in unnumbered sections are absent from the result rather than
     * mapped to null, so a caller asking for one gets nothing to render.
     *
     * <p>Numbering is opt-in: without {@code vars.numbering} the result is
     * empty and nothing downstream renders or checks a number, so an existing
     * book's output is untouched. The only mode today is {@code sequential} —
     * numbers derived from position, gaps closing themselves — which is what a
     * book wants until it publishes and its numbers escape into other people's
     * references.
     *
     * @param bookRoot the book root, for resolving a card's section
     * @param declared declared sections, which may claim a card or a folder
     * @param cards    every card in the build, in book order
     * @param vars     the book's vars, read for the {@code numbering} opt-in
     * @return card id to number, in book order; empty when numbering is off
     */
    public Map<String, CardNumber> cardNumbers(Path bookRoot, List<Section> declared,
            List<Card> cards, Map<String, Object> vars) {
        if (!numberingEnabled(vars)) return Map.of();
        if (cards == null || cards.isEmpty()) return Map.of();
        List<Numbering.Placement> placements = new ArrayList<>(cards.size());
        for (Card card : cards) {
            String secId = sectionIdFor(bookRoot, declared, card.source());
            // A card sitting directly in the root has no section; give it a
            // stable key of its own rather than dropping it from numbering.
            placements.add(new Numbering.Placement(card.id(), secId == null ? "" : secId));
        }
        Map<String, SectionNumbering> sections = new LinkedHashMap<>();
        sectionBodies.forEach((id, body) -> {
            if (body != null) sections.put(id, body.numbering());
        });
        return Numbering.resolve(placements, sections);
    }

    /**
     * Whether the book asked for chapter numbers. {@code vars.numbering:} takes
     * {@code sequential} (or plain truthiness, which means the same thing while
     * {@code sequential} is the only mode); anything else, including absent, is
     * off.
     *
     * <p>An unrecognised value is rejected rather than read as "off": a book
     * that wrote {@code numbering: pinned} expecting the mode this design
     * reserves for after publication should be told it does not exist yet, not
     * silently shipped with no numbers at all.
     */
    static boolean numberingEnabled(Map<String, Object> vars) {
        Object mode = vars == null ? null : vars.get("numbering");
        if (mode == null) return false;
        String s = String.valueOf(mode).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty() || s.equals("false") || s.equals("none") || s.equals("off")) return false;
        if (s.equals("sequential") || s.equals("true")) return true;
        throw new IllegalStateException("Unknown numbering mode `" + mode
                + "`. The only mode is `sequential`; omit `numbering:` to turn numbering off.");
    }

    private static String sectionIdFor(Path bookRoot, List<Section> declared, Path source) {
        String claimed = declaredSectionIdForCard(declared, source);
        if (claimed != null) return claimed;
        String folder = folderIdFor(bookRoot, source);
        if (folder == null) return null;
        // A declared section speaks for every folder it claims, so those cards
        // report the declared id and land in one group; unclaimed folders keep
        // reporting their own name and stay discovered sections.
        String declaredId = declaredSectionIdForFolder(declared, folder);
        return declaredId != null ? declaredId : folder;
    }

    /**
     * The raw top-level folder a card sits in, relative to the book root (or
     * to a {@code content/} wrapper) — the discovered section id, before any
     * {@code sections:} declaration gets a say.
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
     * The id of the declared section claiming this exact card file, or null
     * when none does. Only pattern-declared sections claim individual cards; a
     * yaml {@code sections:} entry claims folders and is skipped here.
     */
    private static String declaredSectionIdForCard(List<Section> declared, Path source) {
        if (declared == null || source == null) return null;
        for (Section section : declared) {
            if (section.claims(source)) return section.id();
        }
        return null;
    }

    /** The id of the declared section claiming {@code folder}, or null when none does. */
    private static String declaredSectionIdForFolder(List<Section> declared, String folder) {
        if (declared == null || folder == null) return null;
        for (Section section : declared) {
            if (section.folders().contains(folder)) return section.id();
        }
        return null;
    }

    /** The declared section with this id, or null — {@code id} may equally be a discovered folder's. */
    private static Section declaredSectionById(List<Section> declared, String id) {
        if (declared == null || id == null) return null;
        for (Section section : declared) {
            if (id.equals(section.id())) return section;
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
     * declared {@link Section} that opted out (see {@link Section#landingPage()}).
     * The entry itself still exists, so the group keeps its label, count and
     * card list in the nav, sidebar and index; what's dropped is its own page
     * and every link to one.
     */
    private static List<Map<String, Object>> buildSectionMetas(
            Map<String, List<Integer>> bySection,
            Path bookRoot,
            List<Section> declaredSections,
            String bookDefaultSectionTemplate,
            Map<String, SectionFolderConfig> folderYamlCache,
            Map<String, SectionBody> bodies) {
        List<Map<String, Object>> out = new ArrayList<>(bySection.size());
        for (var e : bySection.entrySet()) {
            String id = e.getKey();
            // A declared section carries its own title and landing template,
            // so it needs no folder yaml lookup -- it spans several folders
            // and no single one of them could speak for the group. Discovered
            // sections resolve from their folder's yaml exactly as before.
            Section declared = declaredSectionById(declaredSections, id);
            SectionFolderConfig info = declared != null
                    ? new SectionFolderConfig(declared.title(), declared.landingTemplate())
                    : lookupSectionFolderYaml(bookRoot, id, folderYamlCache);
            SectionBody body = bodies.get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            // Label precedence: the folder's (or declaration's) own title, then
            // the intro markdown's hoisted heading, then the folder name. The
            // middle step is what keeps a `# The Tools` in _section.md from
            // disappearing — the loader hoists it out of the body, so without
            // this it would be written and then silently dropped.
            m.put("label", info.title() != null ? info.title()
                    : body != null && body.title() != null ? body.title()
                    : formatSectionLabel(id));
            // The section's own content, when it wrote some. Present means the
            // default card grid is replaced, not decorated: `cards` is the
            // fallback for a section that says nothing about itself.
            m.put("body", body == null ? null : body.html());
            m.put("bodyCards", body != null && body.withCards());
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
            // A declared section opts out in the declaration; a discovered
            // folder does it with `landing: false` in its own _section.md,
            // which is where everything else about that folder is already said.
            m.put("landingPage", (declared == null || declared.landingPage())
                    && (body == null || body.landing()));
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
    /**
     * Look up {@code sectionId}'s folder yaml, cached per {@link #renderSite}
     * call. The reading itself is {@link SectionFolderConfig}'s — every
     * paperband.yaml reader lives in the config module.
     */
    private static SectionFolderConfig lookupSectionFolderYaml(
            Path bookRoot, String sectionId, Map<String, SectionFolderConfig> cache) {
        return cache.computeIfAbsent(sectionId, id -> SectionFolderConfig.read(bookRoot, id));
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
            if (messageOf(e).contains("Could not find template")) {
                throw new LayoutException(missingTemplate(layoutName), e);
            }
            throw new LayoutException(
                    "Site template '" + layoutName + "' failed"
                            + locationOf(e) + ": " + messageOf(e), e);
        }
    }

    /**
     * A "no such template" message that says where we looked.
     *
     * <p>Pebble's own error names the template and stops there, which for a
     * declared {@code landing.template} or {@code <sectionLandingTemplate>} is
     * the least useful half: the author knows what they wrote, and wants to
     * know which directory it was expected in and what is actually there.
     *
     * @param layoutName the bare template name Pebble was asked for
     * @return the message
     */
    private String missingTemplate(String layoutName) {
        StringBuilder sb = new StringBuilder("Template '" + layoutName + "' not found.");
        if (layoutsDir != null) {
            sb.append(" Declared template paths resolve against the book's layouts directory, ")
                    .append(layoutsDir).append(" — expected ")
                    .append(layoutsDir.resolve(layoutName + ".html")).append('.');
            try (var files = java.nio.file.Files.list(layoutsDir)) {
                List<String> found = files.map(f -> f.getFileName().toString()).sorted().toList();
                sb.append(found.isEmpty()
                        ? " That directory is empty."
                        : " It contains: " + String.join(", ", found) + ".");
            } catch (IOException | RuntimeException ignored) {
                // Can't list it; the path above is still the useful half.
            }
        }
        sb.append(" (A bare name like 'minimal' or 'default' is a built-in preset; "
                + "anything else is a path under layouts/, without the extension.)");
        return sb.toString();
    }

    /**
     * The book's own body — the content root's {@code _section.md}, rendered.
     *
     * <p>The root is the outermost section, so it needs no separate filename:
     * {@link SectionBodies#BOOK} is simply the empty section id.
     *
     * @return the rendered body, or null when the book wrote none
     */
    private String bookBodyHtml() {
        SectionBody b = sectionBodies.get("");
        return b == null ? null : b.html();
    }

    /** True when the book's own body asked for the default content to follow it. */
    private boolean bookBodyKeepsDefault() {
        SectionBody b = sectionBodies.get("");
        return b != null && b.withCards();
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
        m.put("cover",  siteMatter(ctx.book().cover(),  ctx.book().bookRoot()));
        m.put("back",   siteMatter(ctx.book().back(),   ctx.book().bookRoot()));
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
    /**
     * A page matter's site model: {@link #pageMatterModel} plus
     * {@code siteImage}, an output-relative path for a declared image.
     *
     * <p>The PDF resolves a cover image to an absolute {@code file:} URI,
     * which is right for a document rendered once from the source tree and
     * wrong for a site that gets served from somewhere else. The site names
     * the copy the build drops into {@code assets/} instead (see
     * {@code SiteMojo.copyMatterAssets}); templates prefix it with
     * {@code urlPrefix} for their depth.
     *
     * @param matter   the declared page matter, or null
     * @param bookRoot the book root images resolve against
     * @return the model, with {@code siteImage} null when no image is declared
     */
    private static Map<String, Object> siteMatter(
            dev.noregressions.paperband.model.PageMatter matter, Path bookRoot) {
        Map<String, Object> m = pageMatterModel(matter, bookRoot);
        m.put("siteImage", matter == null || matter.image() == null
                ? null
                : SITE_ASSET_DIR + "/" + Path.of(matter.image()).getFileName());
        return m;
    }

    /** Output-relative directory the site build copies book assets into. */
    public static final String SITE_ASSET_DIR = "assets";

    private static Map<String, Object> pageMatterModel(
            dev.noregressions.paperband.model.PageMatter matter, Path bookRoot) {
        Map<String, Object> m = new HashMap<>();
        if (matter == null) {
            m.put("image", null);
            m.put("template", null);
            m.put("present", false);
            m.put("hasText", false);
            m.put("title", null);
            m.put("subtitle", null);
            m.put("series", null);
            m.put("author", null);
            m.put("fullPage", false);
            return m;
        }
        String image = matter.image();
        if (image != null && bookRoot != null) {
            image = bookRoot.resolve(image).toUri().toString();
        }
        m.put("image", image);
        m.put("template", matter.template());
        m.put("present", !matter.isEmpty());
        // Cover-level text: hasText says whether the text block renders at
        // all (true / any declared line); the four lines are this element's
        // own overrides, null meaning "inherit the book's value" — the
        // fallback happens in _book-cover.html where both are in scope.
        m.put("hasText", matter.hasText());
        m.put("title", matter.title());
        m.put("subtitle", matter.subtitle());
        m.put("series", matter.series());
        m.put("author", matter.author());
        m.put("fullPage", matter.fullPage());
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
        if (layoutsDir != null) {
            hint.append(layoutsDir).append("/<name>.html, then ");
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
        String composedCardCss = composeCss(ctx.cssChain());
        model.put("cssImports", extractCssImports(composedCardCss));
        model.put("css", stripCssImports(composedCardCss));
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

        // Plain "section" groupings (see the Sections doc in CLAUDE.md).
        // Sections OWN cards; axes LABEL them: membership is independent of
        // axis values, so a section's card count and its divider's TOC list
        // every card the book placed in it, including cards that also carry
        // an axis value. (Ejecting labelled cards to the axis grouping — the
        // old rule — left "1 card" dividers wherever a section mixed plain
        // and labelled cards, and no divider at all for a section whose every
        // card was labelled.) What stays exclusive is the DIVIDER, below: an
        // axis divider firing on a card suppresses that card's section
        // divider, so at most one divider page still precedes any card.
        Path bookRoot = bookCtx.book().bookRoot();
        List<Section> declaredSections = bookCtx.book().sections();
        Map<String, List<Integer>> bookBySection = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            String secId = sectionIdFor(bookRoot, declaredSections, cards.get(i).source());
            if (secId == null) continue;
            bookBySection.computeIfAbsent(secId, k -> new ArrayList<>()).add(i);
        }
        // Reuse the same book-default/per-folder template resolution as the
        // site (there's no separate "PDF template" config) so a section
        // resolved to the minimal preset gets a minimal PDF divider too —
        // _section-divider-base.html reads the "minimal" flag this sets.
        List<Map<String, Object>> sectionMetas = buildSectionMetas(
                bookBySection, bookRoot, declaredSections, bookCtx.book().sectionLandingTemplate(),
                new HashMap<>(), sectionBodies);
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
        // in book.axes() declaration order. Every card with a resolvable
        // section also gets sectionMeta (membership); sectionFirst — the
        // section-divider trigger — fires on the section's first card unless
        // an axis divider fires on that same card, in which case the axis
        // divider wins and the section divider is skipped (not deferred:
        // rendering it before a later card would put it after content).
        List<Map<String, Object>> cardModels = new ArrayList<>(cards.size());
        // Printed table of contents — built alongside the divider bookkeeping
        // below so its entries appear in the exact order the PDF assembles
        // pages: each divider when it fires, then the cards under it. Anchors
        // are the same named destinations the anchor-bait div links, which is
        // what lets the build's second render pass fill in real page numbers
        // (see PageRefs in the maven plugin).
        boolean wantToc = tocAt != null || truthyVar(bookCtx.vars().get("toc"));
        // Built for every book, printed only when one was asked for: these same
        // entries are what the PDF's bookmark tree is made of (see outline()),
        // and bookmarks are worth having in a book that prints no contents
        // page. Only the model put below is gated on wantToc.
        List<Map<String, Object>> tocEntries = new ArrayList<>();
        // Section id -> the part divider that section opens. Empty for a book
        // that declares no part spanning more than one section, which is every
        // book that says nothing about parts.
        Map<String, Map<String, Object>> partDividers =
                partDividers(bookRoot, declaredSections, cards);
        Set<Integer> partsWithDividers = new LinkedHashSet<>();
        for (Map<String, Object> pd : partDividers.values()) {
            partsWithDividers.add((Integer) pd.get("part"));
        }
        // Where a declared mid-book contents page falls among those entries,
        // for the bookmark that points at it (tocAt names the card the printed
        // page precedes; -1 means "up front").
        int tocEntryIndex = -1;
        Map<String, String> prevValueKeyByAxis = new HashMap<>();
        String prevSectionId = null;
        for (int i = 0; i < cards.size(); i++) {
            // book.html emits a declared contents page in front of this card
            // AND in front of the dividers that fire on it, so the bookmark's
            // slot is taken before any of this card's entries are added.
            if (tocAt != null && i == tocAt) tocEntryIndex = tocEntries.size();
            Map<String, Object> axesForCard = cardAxesFromGroupings(i, groupings);
            Map<String, Object> cm = cardModel(cards.get(i), axesForCard);
            cm.put("number", numberLabel(cards.get(i).id()));
            // Card-scope page treatment: when this card's resolved orientation
            // differs from the book's sheet, name the rotation so book.html can
            // stamp a `page:` property on the article and Chromium gives the
            // card's whole run of sheets that rotation. Null when it matches —
            // the overwhelmingly common case, and no CSS is emitted for it.
            cm.put("sheet", rotationOf(contexts, i, sheetFor(bookCtx)));
            Map<String, Boolean> firstOf = new LinkedHashMap<>();
            for (AxisGrouping g : groupings) {
                String key = normalizeAxisId(g.perCardValue().get(i));
                boolean first = g.axis().dividers()
                        && key != null && !key.equals(prevValueKeyByAxis.get(g.axis().name()));
                firstOf.put(g.axis().name(), first);
                if (key != null) prevValueKeyByAxis.put(g.axis().name(), key);
                if (first
                        && axesForCard.get(g.axis().name()) instanceof Map<?, ?> valueMeta) {
                    tocEntries.add(tocEntry(
                            String.valueOf(valueMeta.get("label")),
                            "axis-divider-" + g.axis().name() + "-" + valueMeta.get("id"),
                            "divider", 0));
                }
            }
            cm.put("axesFirstOf", firstOf);
            boolean axisDividerHere = firstOf.containsValue(Boolean.TRUE);

            Map<String, Object> sectionMeta = null;
            Map<String, Object> partMeta = null;
            boolean sectionFirst = false;
            String secId = sectionIdFor(bookRoot, declaredSections, cards.get(i).source());
            // Whether this card's section sits inside a part that prints a
            // divider. That is what pushes it and its section one level deeper
            // in the contents, so the nesting the dividers imply is visible.
            boolean inPart = secId != null && partOf(secId) != null
                    && partsWithDividers.contains(partOf(secId));
            if (secId != null) {
                sectionMeta = findSectionMeta(sectionMetas, secId);
                sectionFirst = !secId.equals(prevSectionId) && !axisDividerHere;
                prevSectionId = secId;
                // The part divider precedes the section divider of the section
                // that opens the part, so a reader meets "Part 3 — The Failure
                // Catalogue" once and then its first level — rather than five
                // sections each announcing Part 3.
                partMeta = sectionFirst ? partDividers.get(secId) : null;
                if (partMeta != null) {
                    tocEntries.add(tocEntry(String.valueOf(partMeta.get("label")),
                            "section-divider-" + partMeta.get("id"), "divider", 0));
                }
                if (sectionFirst && sectionMeta != null
                        && Boolean.TRUE.equals(sectionMeta.get("landingPage"))) {
                    tocEntries.add(tocEntry(
                            String.valueOf(sectionMeta.get("label")),
                            "section-divider-" + secId, "divider", inPart ? 1 : 0));
                }
            }
            cm.put("sectionMeta", sectionMeta);
            cm.put("sectionFirst", sectionFirst);
            // Non-null on exactly one card per part: the first card of the
            // section that opens it. book.html renders it ahead of the section
            // divider.
            cm.put("partMeta", partMeta);
            int tocDepth = (sectionMeta != null || hasAnyAxisValue(i, groupings)) ? 1 : 0;
            if (inPart && tocDepth > 0) tocDepth++;
            // The number goes into the label rather than alongside it, so a
            // theme that knows nothing about numbering still prints a
            // numbered contents page.
            String num = numberLabel(cards.get(i).id());
            String tocLabel = num == null
                    ? cards.get(i).title() : num + " " + cards.get(i).title();
            tocEntries.add(tocEntry(
                    tocLabel, "card-" + cards.get(i).id(), "card", tocDepth));

            cardModels.add(cm);
        }

        // A trailing marker (tocAt at or past the last card) never came up in
        // the loop above: book.html emits that contents page after every card
        // and before the index, so its bookmark slot is the end of the cards.
        if (tocAt != null && tocAt >= cards.size()) tocEntryIndex = tocEntries.size();
        model.put("toc", wantToc ? tocEntries : null);
        // Where book.html drops the contents page: in front of this card
        // index, cards.size() meaning after the last card. A vars-toggled TOC
        // with no declared position keeps its traditional spot up front.
        model.put("tocAt", tocAt == null ? 0 : Math.min(tocAt, cards.size()));
        // Generated pages (<page> markers): book.html includes each entry's
        // template in front of the card its index names, with this whole model
        // in scope via include inheritance — the capability cards can't have,
        // since they load before the model exists. The n is a stable ordinal
        // for the page's anchor id (book-page-{n}).
        List<Map<String, Object>> pageModels = new ArrayList<>(pagesAt.size());
        for (int p = 0; p < pagesAt.size(); p++) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("n", p);
            pm.put("at", Math.min(pagesAt.get(p).cardIndex(), cards.size()));
            pm.put("template", pagesAt.get(p).template());
            pageModels.add(pm);
        }
        model.put("bookPages", pageModels);
        Map<String, List<String>> indexTerms = resolvedIndexTerms(cards, bookCtx.vars());
        Object bookIndex = indexTerms.isEmpty() ? null : buildIndexModel(cards, indexTerms);
        model.put("bookIndex", bookIndex);
        // The index is a page of the book like any other, so it belongs in the
        // contents — a reader who cannot find the index from the contents page
        // has to know it exists. Appended here rather than in the card loop
        // because that is where book.html emits it (last), and because whether
        // there is an index at all is only settled on the line above.
        // tocEntries is the same list the model already holds (when the book
        // prints one), so this reaches the template without re-putting it —
        // and reaches the bookmark tree either way.
        if (bookIndex != null) {
            tocEntries.add(tocEntry("Index", "book-index", "divider", 0));
        }
        // Same entries, minus the printing: the PDF's bookmark tree. Built
        // here (not by the caller from the model) because this is where the
        // contents page's own position is known.
        this.outline = buildOutline(tocEntries, wantToc, tocEntryIndex, bookCtx);
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
        // Book-level geometry comes from the book's sheet, never from bookCtx
        // (= the first card walked, which may carry its own rotation).
        dev.noregressions.paperband.render.PageSpec sheet =
                bookSheet != null ? bookSheet : bookCtx.pageSpec();
        model.put("orientation", sheet.orientation().name().toLowerCase());
        model.put("contentHeightMm", sheet.contentHeightMm());
        model.put("pageMarginsMm", pageMarginsModel(sheet));
        // Content height for the rotated sheet, so a card that turns its pages
        // gets a content box matching the page it will actually print on. Only
        // emitted when some card rotates; book.html keys its @page rules off it.
        model.put("rotatedContentHeightMm", new dev.noregressions.paperband.render.PageSpec(
                sheet.size(), sheet.margins(),
                sheet.orientation() == dev.noregressions.paperband.render.Orientation.LANDSCAPE
                        ? dev.noregressions.paperband.render.Orientation.PORTRAIT
                        : dev.noregressions.paperband.render.Orientation.LANDSCAPE).contentHeightMm());
        model.put("measure", resolveMeasure(bookCtx.vars()));
        model.put("bookBody", bookBodyHtml());
        model.put("bookBodyKeepsDefault", bookBodyKeepsDefault());
        String composedBookCss = composeCss(bookCtx.cssChain());
        model.put("cssImports", extractCssImports(composedBookCss));
        model.put("css", stripCssImports(composedBookCss));
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

    /**
     * The book's bookmark tree: every printed-contents entry, plus the two
     * pages a contents page can't list — itself, and the back page.
     *
     * <p>Both extras are places a reader navigates to and neither is a card,
     * so they belong in the outline pane even though the printed contents
     * page has no line for them (a contents page listing itself is noise on
     * paper; a bookmark for it is a way back).
     *
     * <p>The contents bookmark inherits the depth of the entry it displaces
     * rather than always sitting at the top level. A declared {@code <toc/>}
     * marker normally lands between sections, where the next entry is a
     * divider and the inherited depth is 0 — but a marker that lands inside a
     * divider's run of cards would otherwise drop a top-level bookmark in the
     * middle of that run and adopt the rest of it. Borrowing the depth keeps
     * the tree honest and the order exact.
     *
     * @param tocEntries    the printed-contents entries, in page order
     * @param wantToc       whether the book prints a contents page at all
     * @param tocEntryIndex where a declared contents page falls among them, or -1 for up front
     * @param bookCtx       the book context — supplies the contents label and the back page
     * @return the outline, in page order
     */
    private static List<OutlineEntry> buildOutline(
            List<Map<String, Object>> tocEntries, boolean wantToc, int tocEntryIndex,
            RenderContext bookCtx) {
        List<OutlineEntry> out = new ArrayList<>(tocEntries.size() + 2);
        for (Map<String, Object> e : tocEntries) {
            out.add(new OutlineEntry(
                    e.get("label") == null ? null : String.valueOf(e.get("label")),
                    String.valueOf(e.get("anchor")),
                    e.get("depth") instanceof Number n ? n.intValue() : 0));
        }
        if (wantToc) {
            // The same label the contents page prints (_book-toc.html), so the
            // bookmark and the page it opens say the same thing.
            Object declared = bookCtx.vars().get("tocTitle");
            String label = declared == null || String.valueOf(declared).isBlank()
                    ? "Contents" : String.valueOf(declared).trim();
            int at = tocEntryIndex < 0 || tocEntryIndex > out.size() ? 0 : tocEntryIndex;
            int depth = tocEntryIndex >= 0 && at < out.size() ? out.get(at).depth() : 0;
            out.add(at, new OutlineEntry(label, "book-toc", depth));
        }
        PageMatter back = bookCtx.book() == null ? null : bookCtx.book().back();
        if (back != null && !back.isEmpty()) {
            // Image- or template-only back matter has no title of its own;
            // "book-back" (what OutlineEntry would fall back to) is an anchor,
            // not a thing to show a reader.
            out.add(new OutlineEntry(
                    back.title() == null ? "Back page" : back.title(), "book-back", 0));
        }
        return List.copyOf(out);
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

    /** Whether {@code vars.index} asked for automatic term extraction. */
    private static boolean isAutoIndex(Object v) {
        return v != null && "auto".equalsIgnoreCase(String.valueOf(v).trim());
    }

    /**
     * Each card's final index terms, in card order — the resolution both the
     * index page and the {@code structure} outline print, so what the outline
     * shows is exactly what the PDF will index.
     *
     * <p>{@code vars.index: true} takes terms from each card's {@code index:}
     * frontmatter alone; {@code vars.index: auto} adds the terms
     * {@link IndexTermExtractor} judges distinctive of the card, minus any
     * the book vetoed via {@code vars.indexStop} (a list or comma-separated
     * string). A frontmatter spelling wins over an auto-pick of the same term.
     *
     * @return card id → terms; an empty map when indexing is off entirely
     */
    static Map<String, List<String>> resolvedIndexTerms(List<Card> cards, Map<String, Object> vars) {
        Object mode = vars == null ? null : vars.get("index");
        boolean auto = isAutoIndex(mode);
        if (!truthyVar(mode) && !auto) return Map.of();
        Map<String, List<String>> autoTerms = auto
                ? IndexTermExtractor.extract(cards, stopTerms(vars))
                : Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Card card : cards) {
            List<String> terms = new ArrayList<>(indexTermsOf(card));
            for (String t : autoTerms.getOrDefault(card.id(), List.of())) {
                if (terms.stream().noneMatch(x -> x.equalsIgnoreCase(t))) terms.add(t);
            }
            out.put(card.id(), terms);
        }
        return out;
    }

    /** The {@code vars.indexStop} veto list — terms auto-extraction must never pick. */
    private static Set<String> stopTerms(Map<String, Object> vars) {
        Object raw = vars.get("indexStop");
        if (raw == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) { if (o != null) addStop(out, String.valueOf(o)); }
        } else {
            for (String part : String.valueOf(raw).split(",")) addStop(out, part);
        }
        return out;
    }

    private static void addStop(Set<String> out, String s) {
        s = s.trim();
        if (!s.isEmpty()) out.add(s);
    }

    /**
     * Back-of-book index model from each card's resolved terms (see
     * {@link #resolvedIndexTerms}). Terms group under their first letter
     * (non-letters under {@code #}), sorted case-insensitively; each term
     * points at the cards that carry it, in book order, via the same
     * {@code card-<id>} anchors the TOC uses.
     *
     * @return letter groups: {letter, terms: [{term, refs: [{anchor, title}]}]},
     *         empty when no card carries any terms
     */
    private static List<Map<String, Object>> buildIndexModel(
            List<Card> cards, Map<String, List<String>> termsByCard) {
        Map<String, List<Map<String, Object>>> byTerm =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Card card : cards) {
            for (String term : termsByCard.getOrDefault(card.id(), List.of())) {
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

    /**
     * The parts this book declares, as section-id to part divider model.
     *
     * <p>A part groups sibling sections: the JDK guide's "Part 3 — The Failure
     * Catalogue" spans five level folders, each of which keeps its own divider
     * and its own contents entry. Sections join a part with {@code part:} in
     * their {@code _section.md} (the same key numbering reads), and one of them
     * names it with {@code part_title:}.
     *
     * <p>Only the section that <em>opens</em> a part gets an entry, and only
     * where the part spans more than one section. A part of one section is
     * already announced by that section's own divider; a second page saying
     * nearly the same thing reads as a mistake, which is exactly what five
     * dividers each headed "Part 3" looked like before this existed.
     *
     * @param bookRoot the book root, for resolving a card's section
     * @param declared declared sections, which may claim a card or a folder
     * @param cards    every card in the build, in book order
     * @return section id to the divider model for the part it opens; empty when
     *         the book declares no multi-section part
     */
    private Map<String, Map<String, Object>> partDividers(
            Path bookRoot, List<Section> declared, List<Card> cards) {
        if (cards == null || cards.isEmpty()) return Map.of();
        // Sections in book order, de-duplicated.
        List<String> order = new ArrayList<>();
        for (Card card : cards) {
            String secId = sectionIdFor(bookRoot, declared, card.source());
            if (secId != null && !order.contains(secId)) order.add(secId);
        }
        Map<Integer, List<String>> byPart = new LinkedHashMap<>();
        Map<Integer, String> titles = new LinkedHashMap<>();
        for (String secId : order) {
            SectionBody body = sectionBodies.get(secId);
            if (body == null) continue;
            Integer part = body.numbering().part();
            if (part == null) continue;
            byPart.computeIfAbsent(part, k -> new ArrayList<>()).add(secId);
            if (body.partTitle() != null) titles.putIfAbsent(part, body.partTitle());
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (var e : byPart.entrySet()) {
            String title = titles.get(e.getKey());
            if (title == null || e.getValue().size() < 2) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "part-" + e.getKey());
            m.put("label", title);
            m.put("part", e.getKey());
            // Title only: the sections below carry the counts and contents, and
            // repeating them here would preview the preview.
            m.put("minimal", true);
            m.put("count", 0);
            m.put("body", null);
            m.put("bodyCards", false);
            m.put("cards", List.of());
            out.put(e.getValue().get(0), m);
        }
        return out;
    }

    /** The part number a section declared, or null when it declared none. */
    private Integer partOf(String sectionId) {
        SectionBody body = sectionBodies.get(sectionId);
        return body == null ? null : body.numbering().part();
    }

    /** This card's number as a template-ready string, or null when unnumbered. */
    private String numberLabel(String cardId) {
        CardNumber n = cardNumbers.get(cardId);
        return n == null ? null : n.label();
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
        // Card-scope page rotation, filled in by buildBookModel where the book's
        // sheet is known. Defaulted here so every path that renders a card body
        // has the key: a single-card render has no book sheet to rotate against
        // (the renderer is handed that card's own geometry directly), and the
        // site has no sheets at all.
        m.put("sheet", null);
        // The chapter number, filled in by the callers that know it (the book
        // and site models). Defaulted here so `{% if card.number %}` is a
        // valid guard on every path, including a single-card preview that has
        // no book to number against.
        m.put("number", null);

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
    /**
     * The id a block can be linked to, or null when it has nothing to name it.
     *
     * <p>Blocks already slugify their heading into a class
     * ({@code <section class="block sun-misc-unsafe-memory-access">}); this
     * derives the same string for use as an anchor, so an on-this-page rail
     * has somewhere to point. An explicit {@code {#id}} always wins.
     *
     * @param b the block
     * @return the anchor, or null
     */
    // Package-private: CardLinks validates a card:id#fragment against the same
    // rule that puts the id on the <section>. Two implementations of "what is
    // this block's anchor" would drift, and the drift would show up as a link
    // check that passes and a link that doesn't work.
    static String blockAnchor(Block b) {
        if (b.id() != null && !b.id().isBlank()) return b.id();
        if (b.heading() == null) return null;
        String lower = b.heading().toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (c == '-' || c == '_' || Character.isWhitespace(c)) sb.append('-');
        }
        String out = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> blockModel(Block b) {
        Map<String, Object> bm = new HashMap<>();
        bm.put("kind", b.kind().name());
        bm.put("id", b.id());
        // A stable link target for this block: its declared {#id} when the
        // author gave one, else a slug of the heading — the same slug the
        // block already carries as a class, so the anchor and the styling
        // hook agree. Null for a block with no heading, which nothing links to.
        bm.put("anchor", blockAnchor(b));
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
    /**
     * Whether this card has enough headings to be worth an on-this-page rail.
     *
     * <p>A boolean, not a count: the template walks {@code card.blocks} itself,
     * and all the caller needs is whether there is enough to be worth a second
     * column. A card of one or two sections is better served by the plain
     * centred one. (A count would also be a trap here — Pebble's
     * {@code is not empty} reports a zero Integer as present.)
     *
     * @param card the card
     * @return true when a rail would list at least {@value #RAIL_MIN_ENTRIES} headings
     */
    private static boolean hasRail(Card card) {
        int n = 0;
        for (Block b : card.blocks()) {
            if (blockAnchor(b) == null) continue;
            n++;
            for (Block c : b.children()) {
                if (blockAnchor(c) != null) n++;
            }
        }
        return n >= RAIL_MIN_ENTRIES;
    }

    /** Below this many headings a rail is more furniture than help. */
    private static final int RAIL_MIN_ENTRIES = 3;

    /** The book's sheet, falling back to the book context when the build didn't declare one. */
    private dev.noregressions.paperband.render.PageSpec sheetFor(RenderContext bookCtx) {
        return bookSheet != null ? bookSheet : bookCtx.pageSpec();
    }

    /**
     * The name of card {@code i}'s rotation relative to the book's sheet, or
     * null when it prints the same way round as the rest of the book.
     *
     * @param contexts per-card contexts, positionally aligned with the cards
     * @param i        card index
     * @param sheet    the book's sheet
     * @return {@code "landscape"}, {@code "portrait"}, or null for no rotation
     */
    private static String rotationOf(List<RenderContext> contexts,
                                     int i,
                                     dev.noregressions.paperband.render.PageSpec sheet) {
        if (contexts == null || i >= contexts.size() || contexts.get(i) == null) return null;
        dev.noregressions.paperband.render.Orientation o = contexts.get(i).pageSpec().orientation();
        return o == sheet.orientation() ? null : o.name().toLowerCase();
    }

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

    /**
     * The book's own sheet — the geometry every page is printed on before any
     * card rotates its own. Null falls back to the book context's spec.
     *
     * <p>Set by the build from the config loader's book-scope resolution rather
     * than taken from {@code bookCtx}, which is only ever "whichever card was
     * walked first" and may carry that card's {@code page.orientation}.
     */
    private dev.noregressions.paperband.render.PageSpec bookSheet;

    /**
     * Markdown section bodies the build rendered, keyed by section id. Empty
     * when a book declares none. See {@link SectionBody}.
     */
    private java.util.Map<String, SectionBody> sectionBodies = java.util.Map.of();

    /**
     * Supply the section bodies for this render.
     *
     * <p>Rendered by the caller rather than here: turning markdown into HTML
     * needs the card loader and the include preprocessor, which live in the
     * build. The layout's job is to put the result on the page.
     *
     * @param bodies rendered bodies by section id; null clears
     */
    /**
     * Card id to chapter number, when the book numbers itself. Set by the
     * build, which computes it once via {@link #cardNumbers}, so the reference
     * resolver and the check see the same numbers.
     */
    private java.util.Map<String, CardNumber> cardNumbers = java.util.Map.of();

    /**
     * Supply the chapter numbers this render should use.
     *
     * @param numbers card id to number; empty or null turns numbering off
     */
    public void setCardNumbers(java.util.Map<String, CardNumber> numbers) {
        this.cardNumbers = numbers == null ? java.util.Map.of() : java.util.Map.copyOf(numbers);
    }

    public void setSectionBodies(java.util.Map<String, SectionBody> bodies) {
        this.sectionBodies = bodies == null ? java.util.Map.of() : java.util.Map.copyOf(bodies);
    }

    /**
     * Declare the book's sheet, so per-card rotation is expressed relative to
     * it rather than to the first card's geometry.
     *
     * @param sheet the book-scope page geometry; null keeps the bookCtx default
     */
    public void setBookSheet(dev.noregressions.paperband.render.PageSpec sheet) {
        this.bookSheet = sheet;
    }

    private String composeCss(List<Path> chain) {
        return composeCss(chain, Target.PRINT);
    }

    /**
     * The book's CSS chain, then the theme's stylesheets for {@code target},
     * then the build's own — weakest to strongest.
     *
     * @param chain  the book's css chain
     * @param target which output's theme layer to include
     * @return the composed CSS
     */
    private String composeCss(List<Path> chain, Target target) {
        StringBuilder sb = new StringBuilder(inlineCss(chain));
        for (String css : theme.stylesheets(target)) {
            sb.append(css);
            if (!css.endsWith("\n")) sb.append('\n');
        }
        sb.append(inlineCss(extraCss));
        return sb.toString();
    }

    /**
     * {@code @import} rules are only valid at the very top of a stylesheet,
     * and the composed chain is embedded mid-{@code <style>} after each
     * template's own rules — so a theme's font {@code @import} was silently
     * ignored and every themed font quietly fell back. The templates emit
     * {@code cssImports} in a separate {@code <style>} ahead of everything
     * (its own stylesheet, so top-of-sheet rules apply), and {@code css}
     * carries the rest.
     */
    // The URL itself may contain semicolons (Google Fonts weight lists:
    // "wght@0,400;0,500"), so the terminating ';' must be sought only OUTSIDE
    // the quoted string / url(...) — a naive [^;]*; truncates the import
    // mid-string and leaves garbage that breaks the whole stylesheet.
    private static final java.util.regex.Pattern CSS_IMPORT =
            java.util.regex.Pattern.compile(
                    "@import\\s+(?:url\\(\\s*(?:\"[^\"]*\"|'[^']*'|[^)]*)\\s*\\)|\"[^\"]*\"|'[^']*')[^;]*;");

    /** Every {@code @import} rule in {@code css}, newline-joined; empty when none. */
    static String extractCssImports(String css) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = CSS_IMPORT.matcher(css);
        while (m.find()) {
            out.append(m.group()).append('\n');
        }
        return out.toString();
    }

    /** {@code css} with its {@code @import} rules removed. */
    static String stripCssImports(String css) {
        return CSS_IMPORT.matcher(css).replaceAll("");
    }
}
