package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.maven.pdf.FullPageCover;
import dev.noregressions.paperband.maven.pdf.PageRefs;
import dev.noregressions.paperband.maven.pdf.Watermark;
import dev.noregressions.paperband.maven.pdf.WatermarkApplier;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.predicate.PredicateEvaluator;
import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.render.PdfMetadata;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One PDF build, start to finish: card selection, config cascade, card
 * loading, layout, render, watermark, page-count checks.
 *
 * <p>A plain class rather than a mojo because two goals drive it: {@code build}
 * fills it from the POM, and {@code publish} fills it once per declared
 * edition (with that edition's theme, size, selection, vars and page
 * contract). Keeping the pipeline here means an edition build and a plain
 * build can't drift apart — the way they would if {@code publish} reimplemented
 * any of it.
 *
 * <p>Fields are set directly by the goal that owns the build; everything is
 * optional except {@link #output} and one of {@link #input} / {@link #plan}.
 */
final class BookBuild {

    /** Where diagnostics go — the driving mojo's log. */
    private final Log log;

    BookBuild(Log log) {
        this.log = log;
    }

    // ---- what to build ----

    /** Single card file, or a book directory to walk. Mutually exclusive with {@link #plan}. */
    Path input;

    /** A POM-declared book: root plus the section specs selecting its cards. */
    PlannedBook plan;

    /** Output PDF. */
    Path output;

    /**
     * A book whose card list was decided by pattern rather than by walking.
     *
     * @param root         the book root every pattern resolves against
     * @param specs        the declared sections, in emission order
     * @param tocAfterSpec how many specs precede the {@code <toc/>} marker
     *                     placing the printed table of contents, or null when
     *                     the declaration carries no marker
     */
    record PlannedBook(Path root, List<BookPlan.SectionSpec> specs, Integer tocAfterSpec) {}

    // ---- how to build it ----

    String target = "pdf-a4";
    String pageSize = "a4";
    Margins margins;
    String marginsLabel;          // the shorthand as written, for the log line
    String rendererName = "playwright";
    String themeName;
    Path themeDir;
    String layoutOverride;
    Path emitHtml;
    Map<String, Map<String, Object>> includeProviderConfig = Map.of();

    /** Stylesheets inlined after the theme — the build's own layer of the cascade. */
    List<Path> stylesheets = List.of();

    /**
     * The book root, pinned rather than discovered. Set whenever the build
     * declares a book, so a book with no {@code paperband.yaml} still resolves
     * one root for every card instead of one per directory.
     */
    Path declaredRoot;

    /**
     * Book-level config declared in the POM (title, cover, footer, vars …),
     * layered over whatever the root yaml supplied. Null when the build
     * declares none.
     */
    BookLayout bookDeclaration;

    // ---- watermark ----

    String watermarkText;
    String watermarkColor;
    Float watermarkOpacity;
    Float watermarkAngle;
    Integer watermarkFontSize;

    // ---- selection (book path only) ----

    /** Equality clauses, AND-ed: frontmatter field (falling back to vars) must equal the value. */
    Map<String, String> selectClauses;
    /** Card ids hand-picked into the build, unioned with whatever the query matched. */
    List<String> selectCards;
    /** Pebble predicate over each card, AND-ed with {@link #selectClauses}. */
    String selectWhere;

    // ---- editions (publish path only) ----

    /** Vars layered on top of every card's own, topmost in the cascade. */
    Map<String, Object> editionVars;
    /** The {@code edition} model exposed to templates. */
    Map<String, Object> editionModel;

    // ---- page contract ----

    boolean reportPages;
    Integer maxPagesPerCard;

    /**
     * Run the build.
     *
     * @throws MojoFailureException   when the book is empty, a selection matches
     *                                nothing, or a page-count check fails — the
     *                                build was well-formed, the content isn't
     * @throws MojoExecutionException when the build itself cannot proceed
     */
    void run() throws Exception {
        if (plan == null && Files.isRegularFile(input)) {
            buildSingle();
        } else if (plan != null || Files.isDirectory(input)) {
            buildBook();
        } else {
            throw new MojoExecutionException("Input does not exist: " + input);
        }
    }

    // ---- single card ----

    private void buildSingle() throws Exception {
        RenderContext ctx = new ConfigLoader().load(
                input, target, pageSize, margins, declaredRoot, declaredVars());
        MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                ctx.book().bookRoot(), includeProviderConfig, ctx.vars());
        Card card = CardLoading.load(new CardLoader(), preprocessor, input, ctx.book().cardSchema());

        ThemeBundle theme = Themes.resolve(themeName, ctx.book().theme(), themeDir);
        LayoutEngine layout = new LayoutEngine(ctx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheets);
        String html = layoutOverride != null
                ? layout.render(card, ctx, layoutOverride)
                : layout.render(card, ctx);

        emitHtmlIfAsked(html, null);

        HtmlToPdfRenderer renderer = Renderers.require(rendererName);
        URI baseUri = input.toAbsolutePath().getParent().toUri();
        PdfMetadata metadata = card.title() != null ? PdfMetadata.of(card.title()) : PdfMetadata.empty();

        ensureParentDir(output);
        renderer.render(new HtmlInput(html, baseUri, ctx.pageSpec(), metadata), output);
        applyWatermark(ctx);

        log.info("Built " + input + " -> " + output
                + " (renderer=" + renderer.name() + describeGeometry()
                + ", blocks=" + card.blocks().size() + ")");
    }

    // ---- book ----

    private void buildBook() throws Exception {
        BookSource.Resolved source = BookSource.of(input, plan, target, log);
        renderBook(source.root(), source.cardFiles(), source.sections(), source.tocCardIndex());
    }

    /**
     * Load every card in {@code cardFiles}, in order, apply any selection, and
     * render them as one book PDF.
     *
     * @param bookRoot      directory the build was rooted at — the PDF's base URI
     *                      for relative asset references
     * @param cardFiles     ordered card files, however they were selected
     * @param declaredSections sections to declare on the book, replacing anything the
     *                      root {@code paperband.yaml} declared; empty to leave
     *                      the yaml's own {@code sections:} alone
     * @param tocCardIndex  index into {@code cardFiles} before which the printed
     *                      table of contents renders, or null when the build
     *                      declares no position (yaml {@code vars.toc} then
     *                      decides whether one renders at all, up front)
     */
    private void renderBook(Path bookRoot, List<Path> cardFiles, List<Section> declaredSections,
            Integer tocCardIndex) throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        // Constructed once the book root is known, on the first card: ids for
        // cards that declare none are derived from their path relative to it.
        CardLoader cardLoader = null;
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        int totalBlocks = 0;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(
                    cardFile, target, pageSize, margins, declaredRoot, declaredVars());
            if (bookCtx == null) {
                bookCtx = ctx;                 // book-root css/title captured once
            }
            if (cardLoader == null) cardLoader = new CardLoader(bookCtx.book().bookRoot());
            contexts.add(ctx);
            Map<String, Object> vars = ctx.vars();
            if (editionVars != null && !editionVars.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(vars);
                merged.putAll(editionVars);    // edition vars sit topmost by design
                vars = merged;
            }
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), includeProviderConfig, vars);
            Card card = CardLoading.load(cardLoader, preprocessor, cardFile, ctx.book().cardSchema());
            cards.add(card);
            totalBlocks += card.blocks().size();
        }

        CardLoading.requireUniqueIds(cards, bookRoot);

        Selection selected = applySelection(cards, contexts, tocCardIndex);
        cards = selected.cards();
        contexts = selected.contexts();
        tocCardIndex = selected.tocCardIndex();

        // Book-level config declared in the POM layers over the root yaml's,
        // field by field. A declaration wins over a default: the POM is the
        // file the author just edited.
        if (bookDeclaration != null && bookDeclaration.declaresBookConfig()) {
            bookCtx = bookCtx.withBook(
                    bookDeclaration.mergeInto(bookCtx.book(), bookCtx.book().bookRoot()));
        }

        // Declared sections replace whatever the root yaml said: two sources for
        // one book's top-level structure can only disagree.
        if (!declaredSections.isEmpty()) {
            if (!bookCtx.book().sections().isEmpty()) {
                log.warn("<book><sections> overrides the 'sections:' declared in "
                        + bookCtx.book().bookRoot().resolve("paperband.yaml"));
            }
            bookCtx = bookCtx.withBook(bookCtx.book().withSections(declaredSections));
        }

        ThemeBundle theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDir);
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheets);
        if (editionModel != null) layout.setEdition(editionModel);
        layout.setTocAt(tocCardIndex);
        String html = layoutOverride != null
                ? layout.renderBook(cards, contexts, bookCtx, layoutOverride)
                : layout.renderBook(cards, contexts, bookCtx);

        URI baseUri = bookRoot.toAbsolutePath().toUri();
        emitHtmlIfAsked(html, baseUri.toString());

        HtmlToPdfRenderer renderer = Renderers.require(rendererName);
        String bookTitle = bookCtx.book().title();
        PdfMetadata metadata = bookTitle != null ? PdfMetadata.of(bookTitle) : PdfMetadata.empty();
        String footer = layout.renderFooter(bookCtx);
        String header = layout.renderHeader(bookCtx);

        ensureParentDir(output);
        renderer.render(new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata, footer, header),
                output);

        // Two-pass page numbering: a printed TOC or index renders its page
        // numbers as placeholders (the layout can't know them — Chromium
        // decides pagination), so read each anchor's real page from the PDF
        // just written and render once more with the numbers filled in. The
        // substitution changes only text inside the placeholder spans, so
        // pagination holds between passes and the numbers are exact.
        if (PageRefs.present(html)) {
            PageRefs.Resolved refs = PageRefs.resolve(html, PageRefs.readAnchorPages(output));
            for (String anchor : refs.unresolved()) {
                log.warn("Page reference to #" + anchor + " matched no named destination"
                        + " — rendered as '?'");
            }
            html = refs.html();
            emitHtmlIfAsked(html, baseUri.toString());
            renderer.render(new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata, footer, header),
                    output);
            log.info("Resolved " + refs.resolved() + " page reference(s) (toc/index)"
                    + " in a second render pass");
        }

        // A full-page cover must not carry the running header/footer, but
        // Chromium paints those bands onto every page with no per-page
        // switch. Render once more with no bands at all — same HTML, same
        // pagination — and splice that version's first page in. Runs after
        // the TOC pass so the spliced page is the final cover.
        if (FullPageCover.needsBareFirstPage(html, footer, header)) {
            Path barePdf = FullPageCover.bareRenderPath(output);
            try {
                renderer.render(new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata,
                        null, null), barePdf);
                FullPageCover.replaceFirstPage(output, barePdf);
            } finally {
                Files.deleteIfExists(barePdf);
            }
            log.info("Replaced the cover page with a header/footer-free render (fullPage cover)");
        }
        applyWatermark(bookCtx);

        log.info("Built book " + bookRoot + " -> " + output
                + " (renderer=" + renderer.name() + describeGeometry()
                + ", cards=" + cards.size()
                + ", blocks=" + totalBlocks + ")");

        new PageChecks(log, reportPages, maxPagesPerCard).run(cards, html, baseUri, bookCtx);
    }

    // ---- selection ----

    private record Selection(List<Card> cards, List<RenderContext> contexts,
                             Integer tocCardIndex) {}

    /**
     * Narrow the book to the cards a selection asks for. Union semantics: the
     * hand-picked {@code cards} list is added to whatever the clauses-plus-where
     * query matched, rather than intersected with it.
     *
     * <p>Clause resolution mirrors {@code LayoutEngine.resolveAxisValue} — the
     * card's own frontmatter field wins, the folder-cascaded vars entry is the
     * fallback — and compares as strings so a numeric yaml value ({@code tier: 1})
     * matches its config spelling.
     */
    private Selection applySelection(List<Card> cards, List<RenderContext> contexts,
            Integer tocCardIndex) throws MojoFailureException {
        List<String> inclusion = selectCards == null ? List.of() : selectCards;
        boolean hasWhere = selectWhere != null && !selectWhere.isBlank();
        boolean hasQuery = (selectClauses != null && !selectClauses.isEmpty()) || hasWhere;
        if (!hasQuery && inclusion.isEmpty()) {
            return new Selection(cards, contexts, tocCardIndex);
        }

        PredicateEvaluator predicate = hasWhere ? new PredicateEvaluator() : null;
        List<Card> keptCards = new ArrayList<>();
        List<RenderContext> keptContexts = new ArrayList<>();
        int keptBeforeToc = 0;
        for (int i = 0; i < cards.size(); i++) {
            boolean keep = inclusion.contains(cards.get(i).id());
            if (!keep && hasQuery) {
                keep = true;
                if (selectClauses != null) {
                    for (Map.Entry<String, String> clause : selectClauses.entrySet()) {
                        Object v = cards.get(i).frontmatter().get(clause.getKey())
                                .orElse(contexts.get(i).vars().get(clause.getKey()));
                        if (v == null || !clause.getValue().equals(String.valueOf(v))) {
                            keep = false;
                            break;
                        }
                    }
                }
                if (keep && predicate != null) {
                    Map<String, Object> cardModel = new LinkedHashMap<>();
                    cardModel.put("id", cards.get(i).id());
                    cardModel.put("title", cards.get(i).title());
                    cardModel.put("frontmatter", cards.get(i).frontmatter().values());
                    Map<String, Object> pctx = new LinkedHashMap<>();
                    pctx.put("card", cardModel);
                    pctx.put("vars", contexts.get(i).vars());
                    keep = predicate.evaluate(selectWhere, pctx);
                }
            }
            if (keep) {
                keptCards.add(cards.get(i));
                keptContexts.add(contexts.get(i));
                // The TOC position survives selection as "before the first
                // kept card that followed the marker": kept cards ahead of
                // the marker are all that still precede the contents page.
                if (tocCardIndex != null && i < tocCardIndex) keptBeforeToc++;
            }
        }

        String description = describeSelection(inclusion);
        if (keptCards.isEmpty()) {
            throw new MojoFailureException("select " + description + " matched no cards");
        }
        log.info("Selected " + keptCards.size() + " of " + cards.size() + " cards " + description);
        return new Selection(keptCards, keptContexts,
                tocCardIndex == null ? null : keptBeforeToc);
    }

    private String describeSelection(List<String> inclusion) {
        StringBuilder sb = new StringBuilder("(");
        if (selectClauses != null && !selectClauses.isEmpty()) sb.append("where ").append(selectClauses);
        if (selectWhere != null && !selectWhere.isBlank()) {
            if (sb.length() > 1) sb.append(" and ");
            sb.append("[").append(selectWhere).append("]");
        }
        if (!inclusion.isEmpty()) {
            if (sb.length() > 1) sb.append(" plus ");
            sb.append("cards ").append(inclusion);
        }
        return sb.append(")").toString();
    }

    // ---- side outputs ----

    /**
     * Write the intermediate HTML when asked. A book's copy gets a
     * {@code <base href>} stamped in so relative assets (cover images and the
     * like) still resolve when the file is opened in a browser from wherever it
     * was written; the renderer receives the un-stamped html and does its own
     * base handling.
     */
    private void emitHtmlIfAsked(String html, String baseUri) throws Exception {
        if (emitHtml == null) return;
        ensureParentDir(emitHtml);
        Files.writeString(emitHtml, baseUri == null ? html : withBaseForDebug(html, baseUri),
                StandardCharsets.UTF_8);
        log.info("Wrote intermediate HTML -> " + emitHtml);
    }

    private static String withBaseForDebug(String html, String baseUri) {
        String lower = html.toLowerCase();
        if (lower.contains("<base ") || lower.contains("<base>")) return html;
        int head = lower.indexOf("<head>");
        if (head < 0) return html;
        int at = head + "<head>".length();
        return html.substring(0, at) + "<base href=\"" + baseUri + "\">" + html.substring(at);
    }

    /**
     * Stamp the watermark the POM or the book asked for.
     *
     * <p>{@code <watermark>} in the POM wins as the base spec; otherwise
     * {@code vars.watermark} supplies it (bare string or full map). The
     * individual tuning parameters then override whichever fields they name on
     * whichever spec won.
     */
    private void applyWatermark(RenderContext ctx) throws Exception {
        Watermark base = null;
        if (watermarkText != null && !watermarkText.isBlank()) {
            base = Watermark.withDefaults(watermarkText);
        } else if (ctx != null) {
            base = Watermark.fromYaml(ctx.vars().get("watermark"));
        }
        if (base == null) return;
        Watermark watermark = base.withOverrides(
                watermarkColor, watermarkOpacity, watermarkAngle, watermarkFontSize, null);
        WatermarkApplier.apply(output, watermark);
        log.info("Applied watermark: \"" + watermark.text() + "\"");
    }

    // ---- helpers ----

    /** Book-level vars the POM declared, as the loader's map type. */
    private Map<String, Object> declaredVars() {
        return bookDeclaration == null ? Map.of() : bookDeclaration.declaredVars();
    }

    /** The geometry half of the "Built ..." line, so a full-bleed build is visible in the log. */
    private String describeGeometry() {
        return ", target=" + target
                + ", size=" + pageSize
                + (marginsLabel == null ? "" : ", margins=" + marginsLabel);
    }

    private static void ensureParentDir(Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
