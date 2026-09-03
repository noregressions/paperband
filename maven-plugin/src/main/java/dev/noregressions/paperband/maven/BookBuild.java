package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.render.PageSpec;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.include.PebbleIncludePreprocessor;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.NumberCheck;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.maven.pdf.FullPageCover;
import dev.noregressions.paperband.maven.pdf.PageRefs;
import dev.noregressions.paperband.maven.pdf.PdfOutline;
import dev.noregressions.paperband.maven.pdf.WatermarkApplier;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.OutlineEntry;
import dev.noregressions.paperband.model.Watermark;
import dev.noregressions.paperband.model.PlacedPage;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.predicate.PredicateEvaluator;
import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.render.PdfMetadata;
import dev.noregressions.paperband.render.WatermarkHtml;

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
     * @param pageMarkers  {@code <page>} markers among the specs, each a
     *                     generated page placed at that point in the book;
     *                     empty when none is declared
     */
    record PlannedBook(Path root, List<BookPlan.SectionSpec> specs, Integer tocAfterSpec,
                       List<BookPlan.PageMarker> pageMarkers) {}

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
     * The book home — where {@code paperband.yaml}, {@code layouts/} and
     * {@code styles/} live when the POM's geography splits them from the
     * content root. Null keeps the self-contained behavior (home == content
     * root). See {@link Geography}.
     */
    Path home;

    /** The templates directory, or null to derive {@code <contentRoot>/layouts} as before. */
    Path layoutsDir;

    /**
     * True when {@link #input} is a POM-resolved content root
     * ({@code <content>} or the convention) rather than a legacy
     * {@code <input>}: the walk then takes everything there as content —
     * HTML cards on, no wrapper detection.
     */
    boolean contentDeclared;

    /**
     * Book-level config declared in the POM (title, cover, footer, vars …),
     * layered over whatever the root yaml supplied. Null when the build
     * declares none.
     */
    BookLayout bookDeclaration;

    // ---- watermark ----

    /**
     * The watermark the goal's own parameters declared. When set it REPLACES a
     * {@code vars.watermark} in the book's yaml rather than merging with it, so
     * a release build can stamp REVIEW COPY over a book whose yaml says DRAFT
     * without inheriting the yaml's colour or angle.
     */
    Watermark watermarkBase;
    /** Per-knob parameters, layered over whichever base spec won. */
    Watermark.Overrides watermarkOverrides = Watermark.Overrides.NONE;

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
                input, target, pageSize, margins, declaredRoot, home, declaredVars());
        ThemeBundle theme = Themes.resolve(themeName, ctx.book().theme(), themeDir);
        BlockTemplates blockTemplates = new BlockTemplates(theme.templateLoader(),
                layoutsDir != null ? layoutsDir : ctx.book().bookRoot().resolve("layouts"),
                BlockRenderers.discover(log), home != null ? home : ctx.book().bookRoot());
        MarkdownPreprocessor preprocessor = CardLoading.preprocessorFor(
                ctx.book().bookRoot(), layoutsDir, includeProviderConfig, ctx.vars(),
                "print", target);
        Card card = CardLoading.load(new CardLoader(), preprocessor, input, ctx.book().cardSchema(),
                ctx.vars(), log, blockTemplates);

        LayoutEngine layout = layoutsDir != null
                ? new LayoutEngine(ctx.book().bookRoot(), layoutsDir, theme)
                : new LayoutEngine(ctx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheets);
        String html = layoutOverride != null
                ? layout.render(card, ctx, layoutOverride)
                : layout.render(card, ctx);

        emitHtmlIfAsked(html, null, ctx, null);

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
        BookSource.Resolved source = plan == null && contentDeclared
                ? BookSource.walkContent(input, target, log)
                : BookSource.of(input, plan, target, log);
        renderBook(source.root(), source.cardFiles(), source.sections(), source.tocCardIndex(),
                source.pages());
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
     * @param pages         generated pages placed into the card flow
     *                      ({@code <page>} markers), each with its card index
     *                      already resolved; empty when none was declared
     */
    private void renderBook(Path bookRoot, List<Path> cardFiles, List<Section> declaredSections,
            Integer tocCardIndex, List<PlacedPage> pages) throws Exception {
        ConfigLoader configLoader = new ConfigLoader();
        // Constructed once the book root is known, on the first card: ids for
        // cards that declare none are derived from their path relative to it.
        CardLoader cardLoader = null;
        ThemeBundle theme = null;
        BlockTemplates blockTemplates = null;
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        int totalBlocks = 0;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(
                    cardFile, target, pageSize, margins, declaredRoot, home, declaredVars());
            if (bookCtx == null) {
                bookCtx = ctx;                 // book-root css/title captured once
            }
            if (cardLoader == null) cardLoader = new CardLoader(bookCtx.book().bookRoot());
            if (theme == null) {
                // Resolved on the first card (the yaml theme is only known
                // once the book config is): block templates walk the same
                // chain as every other template — theme, book layouts/,
                // bundled — so ```type blocks are theme- and book-overridable.
                theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDir);
                blockTemplates = new BlockTemplates(theme.templateLoader(),
                        layoutsDir != null ? layoutsDir
                                : bookCtx.book().bookRoot().resolve("layouts"),
                        BlockRenderers.discover(log),
                        home != null ? home : bookCtx.book().bookRoot());
            }
            contexts.add(ctx);
            Map<String, Object> vars = ctx.vars();
            if (editionVars != null && !editionVars.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(vars);
                merged.putAll(editionVars);    // edition vars sit topmost by design
                vars = merged;
            }
            MarkdownPreprocessor preprocessor = CardLoading.preprocessorFor(
                    bookCtx.book().bookRoot(), layoutsDir, includeProviderConfig, vars,
                    "print", target);
            Card card = CardLoading.load(cardLoader, preprocessor, cardFile, ctx.book().cardSchema(),
                    vars, log, blockTemplates);
            cards.add(card);
            totalBlocks += card.blocks().size();
        }

        CardLoading.requireUniqueIds(cards, bookRoot);

        Selection selected = applySelection(cards, contexts, tocCardIndex, pages);
        // The whole book, before the selection narrows it. Chapter numbers are
        // derived from this rather than from what survives: a sampler that
        // renumbered its extracts would give the same chapter two different
        // numbers in two editions, and its references to chapters it does not
        // carry could not name them at all.
        List<Card> allCards = cards;
        // Captured before the reassignment below: a card: link naming one of
        // these is a different mistake from a misspelling (see CardLinks).
        java.util.Set<String> excludedCardIds = new java.util.LinkedHashSet<>();
        for (Card c : cards) excludedCardIds.add(c.id());
        for (Card c : selected.cards()) excludedCardIds.remove(c.id());
        cards = selected.cards();
        contexts = selected.contexts();
        tocCardIndex = selected.tocCardIndex();
        pages = selected.pages();

        // Book-level config declared in the POM layers over the root yaml's,
        // field by field. A declaration wins over a default: the POM is the
        // file the author just edited.
        if (bookDeclaration != null && bookDeclaration.declaresBookConfig()) {
            bookCtx = bookCtx.withBook(bookDeclaration.mergeInto(bookCtx.book(),
                    home != null ? home : bookCtx.book().bookRoot()));
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

        if (theme == null) theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDir);
        LayoutEngine layout = layoutsDir != null
                ? new LayoutEngine(bookCtx.book().bookRoot(), layoutsDir, theme)
                : new LayoutEngine(bookCtx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheets);
        if (editionModel != null) layout.setEdition(editionModel);
        layout.setTocAt(tocCardIndex);
        layout.setPagesAt(pages);
        layout.setExcludedCardIds(excludedCardIds);
        // The sheet must reach the layout too: per-card rotation is expressed
        // relative to it, and the CSS @page rules restate its margins.
        layout.setBookSheet(configLoader.bookPageSpec());
        // A section's own _section.md is its content on the divider, just as it
        // is on the site's landing page — same file, same mechanism, and the
        // markdown branches on `output` where the two want different words.
        layout.setSectionBodies(SectionBodies.render(
                bookCtx, layoutsDir, includeProviderConfig, cards, "print", target));
        // Chapter numbers are derived from book order, so a number written into
        // a cross-reference label is a copy, and copies drift. Checked here,
        // before renderBook lets CardLinks rewrite the `card:` hrefs the check
        // matches on. A no-op for a book that has not asked for numbering.
        java.util.Map<String, dev.noregressions.paperband.model.CardNumber> numbers =
                layout.cardNumbers(bookCtx.book().bookRoot(), bookCtx.book().sections(),
                        allCards, bookCtx.vars());
        NumberCheck.verify(cards, numbers);
        layout.setCardNumbers(numbers);
        String html = layoutOverride != null
                ? layout.renderBook(cards, contexts, bookCtx, layoutOverride)
                : layout.renderBook(cards, contexts, bookCtx);

        URI baseUri = bookRoot.toAbsolutePath().toUri();
        // Whether a second pass is coming is knowable now — it is the same
        // check the toc/index block below makes on the same html — so the
        // label can say "1 of 2" only when that is true, rather than
        // promising a pass that a book without page refs never runs.
        boolean twoPass = PageRefs.present(html);
        emitHtmlIfAsked(html, baseUri.toString(), bookCtx,
                twoPass ? "pass 1 of 2, page numbers unresolved" : null);

        HtmlToPdfRenderer renderer = Renderers.require(rendererName);
        // The BOOK's sheet, not the first card's: a card may rotate its own
        // pages (page.orientation, card scope), and bookCtx is simply whichever
        // card was walked first. Taking geometry from it would let an opening
        // landscape card turn every sheet in the book landscape.
        PageSpec sheet = configLoader.bookPageSpec();
        String bookTitle = bookCtx.book().title();
        PdfMetadata metadata = bookTitle != null ? PdfMetadata.of(bookTitle) : PdfMetadata.empty();
        String footer = layout.renderFooter(bookCtx);
        String header = layout.renderHeader(bookCtx);

        ensureParentDir(output);
        renderer.render(new HtmlInput(html, baseUri, sheet, metadata, footer, header),
                output);

        // Two-pass page numbering: a printed TOC or index renders its page
        // numbers as placeholders (the layout can't know them — Chromium
        // decides pagination), so read each anchor's real page from the PDF
        // just written and render once more with the numbers filled in. The
        // substitution changes only text inside the placeholder spans, so
        // pagination holds between passes and the numbers are exact.
        if (twoPass) {
            PageRefs.Resolved refs = PageRefs.resolve(html, PageRefs.readAnchorPages(output));
            for (String anchor : refs.unresolved()) {
                log.warn("Page reference to #" + anchor + " matched no named destination"
                        + " — rendered as '?'");
            }
            html = refs.html();
            emitHtmlIfAsked(html, baseUri.toString(), bookCtx,
                    "pass 2 of 2, page numbers resolved — overwrites pass 1");
            renderer.render(new HtmlInput(html, baseUri, sheet, metadata, footer, header),
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
                renderer.render(new HtmlInput(html, baseUri, sheet, metadata,
                        null, null), barePdf);
                FullPageCover.replaceFirstPage(output, barePdf);
            } finally {
                Files.deleteIfExists(barePdf);
            }
            log.info("Replaced the cover page with a header/footer-free render (fullPage cover)");
        }
        applyWatermark(bookCtx);
        // Last of the PDF passes, deliberately: every step above rewrites
        // pages or the destinations bookmarks point at.
        applyBookmarks(bookCtx, layout.outline());

        log.info("Built book " + bookRoot + " -> " + output
                + " (renderer=" + renderer.name() + describeGeometry()
                + ", cards=" + cards.size()
                + ", blocks=" + totalBlocks + ")");

        new PageChecks(log, reportPages, maxPagesPerCard).run(cards, html, baseUri, bookCtx);
    }

    // ---- selection ----

    private record Selection(List<Card> cards, List<RenderContext> contexts,
                             Integer tocCardIndex, List<PlacedPage> pages) {}

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
            Integer tocCardIndex, List<PlacedPage> pages) throws MojoFailureException {
        List<String> inclusion = selectCards == null ? List.of() : selectCards;
        boolean hasWhere = selectWhere != null && !selectWhere.isBlank();
        boolean hasQuery = (selectClauses != null && !selectClauses.isEmpty()) || hasWhere;
        if (!hasQuery && inclusion.isEmpty()) {
            return new Selection(cards, contexts, tocCardIndex, pages);
        }

        PredicateEvaluator predicate = hasWhere ? new PredicateEvaluator() : null;
        List<Card> keptCards = new ArrayList<>();
        List<RenderContext> keptContexts = new ArrayList<>();
        int keptBeforeToc = 0;
        int[] keptBeforePage = new int[pages.size()];
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
                // A marker position survives selection as "before the first
                // kept card that followed the marker": kept cards ahead of
                // the marker are all that still precede its page.
                if (tocCardIndex != null && i < tocCardIndex) keptBeforeToc++;
                for (int p = 0; p < pages.size(); p++) {
                    if (i < pages.get(p).cardIndex()) keptBeforePage[p]++;
                }
            }
        }

        String description = describeSelection(inclusion);
        if (keptCards.isEmpty()) {
            throw new MojoFailureException("select " + description + " matched no cards");
        }
        log.info("Selected " + keptCards.size() + " of " + cards.size() + " cards " + description);
        List<PlacedPage> keptPages = new ArrayList<>(pages.size());
        for (int p = 0; p < pages.size(); p++) {
            keptPages.add(new PlacedPage(keptBeforePage[p], pages.get(p).template()));
        }
        return new Selection(keptCards, keptContexts,
                tocCardIndex == null ? null : keptBeforeToc, keptPages);
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
     * Write the intermediate HTML when asked. A book's copy is made
     * <em>standalone</em>: every local image reference (a cover image sitting
     * in the book's source tree, say) is inlined as a {@code data:} URI, so
     * the file renders the same wherever it's opened or copied — no
     * connection back to the project that built it. Only when something
     * can't be inlined (missing file, or an asset over the size cap) does the
     * copy fall back to a {@code <base href>} stamp so those references at
     * least resolve in place. The renderer receives the untouched html and
     * does its own base handling.
     *
     * <p>{@code pass} names which write this is, for the log. A book with a
     * printed toc or index is rendered twice — page numbers can only be read
     * back out of a finished PDF — and both passes write here, the second
     * overwriting the first. Saying so is the difference between two
     * deliberate writes and what looks like a duplicated message.
     *
     * @param html    the finished document
     * @param baseUri the base local references resolve against; null for a
     *                single card, whose copy is not made standalone
     * @param ctx     the render context, for the screen watermark
     * @param pass    a short description of which write this is, or null when
     *                the build only writes once
     */
    private void emitHtmlIfAsked(String html, String baseUri, RenderContext ctx, String pass)
            throws Exception {
        if (emitHtml == null) return;
        ensureParentDir(emitHtml);
        // Screen-only: this file's print path is the renderer plus the PDFBox
        // stamp, so a visible-in-print overlay would land twice on anyone who
        // re-rendered it with paperband:render.
        Watermark watermark = resolveWatermark(ctx);
        String marked = watermark == null ? html
                : WatermarkHtml.inject(html,
                        WatermarkHtml.overlay(watermark, watermark.image(), /*screenOnly=*/ true));
        // Inline assets after injecting, so an image watermark travels with the
        // file like every other local reference does.
        Files.writeString(emitHtml, baseUri == null ? marked : standaloneForDebug(marked, baseUri),
                StandardCharsets.UTF_8);
        log.info("Wrote intermediate HTML"
                + (pass == null || pass.isBlank() ? "" : " (" + pass + ")")
                + " -> " + emitHtml);
    }

    /** Assets larger than this stay a reference rather than a data: URI. */
    private static final long INLINE_ASSET_CAP_BYTES = 20L * 1024 * 1024;

    /**
     * Matches an opening tag — the scan's outer step.
     *
     * <p>{@link #SRC_ATTR} is applied <em>within</em> a tag rather than to the
     * whole document, because a book that documents this project shows markup
     * in a fenced block, and by the time it reaches here that example is
     * escaped text: {@code &lt;img src="diagrams/gc.png"&gt;}. A bare
     * {@code src="} scan cannot tell that from real markup and rewrote the
     * example — turning a card that teaches the syntax into one showing a
     * base64 blob. An escaped example has no literal {@code <img}, so a tag
     * match cannot reach into it.
     *
     * <p>Deliberately every tag rather than an element allow-list: a
     * {@code <script>}, {@code <video>} or {@code <source>} reference wants
     * inlining just as much as an image does, and naming them would silently
     * stop inlining whatever the list forgot.
     */
    private static final java.util.regex.Pattern TAG =
            java.util.regex.Pattern.compile("(?i)<[a-z][^>]*>");

    /** Matches the {@code src} attribute inside one tag. */
    private static final java.util.regex.Pattern SRC_ATTR =
            java.util.regex.Pattern.compile("(?i)\\bsrc\\s*=\\s*\"([^\"]+)\"");

    /**
     * Inline every local {@code src="..."} target as a data: URI, so the
     * {@code emitHtml} file is one self-contained document. References that
     * are already remote ({@code https:}), already inline ({@code data:}),
     * missing on disk, or over the size cap are left alone; if any local
     * reference survives un-inlined, a {@code <base href>} is stamped so it
     * still resolves when the file is opened in place.
     *
     * <p>Only real attributes are touched — see {@link #TAG} for why that is
     * a two-step scan rather than one pass for {@code src="}.
     *
     * <p>Package-private rather than private so the scan can be tested
     * directly, as {@code LayoutEngine.urlPrefixFor} is.
     *
     * @param html    the finished document
     * @param baseUri the {@code file:} URI local references resolve against
     * @return the document with its local references inlined
     */
    static String standaloneForDebug(String html, String baseUri) {
        Path baseDir = null;
        try {
            URI base = URI.create(baseUri);
            if ("file".equals(base.getScheme())) baseDir = Path.of(base);
        } catch (Exception ignored) {
            // an unusable base means nothing local can resolve; fall through
        }

        boolean unresolved = false;
        StringBuilder out = new StringBuilder(html.length());
        java.util.regex.Matcher tags = TAG.matcher(html);
        int last = 0;
        while (tags.find()) {
            String tag = tags.group();
            String replacement = tag;
            java.util.regex.Matcher m = SRC_ATTR.matcher(tag);
            if (m.find()) {
                String ref = m.group(1);
                String data = null;
                Path file = localFile(ref, baseDir);
                if (file != null) {
                    try {
                        if (Files.isRegularFile(file) && Files.size(file) <= INLINE_ASSET_CAP_BYTES) {
                            data = "data:" + mimeFor(file) + ";base64,"
                                    + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                        }
                    } catch (Exception ignored) {
                        // unreadable: treated the same as missing
                    }
                    if (data == null) unresolved = true;
                }
                // Only the attribute value is spliced, so whatever else the tag
                // carried (alt, class, a loader's async) survives verbatim.
                if (data != null) {
                    replacement = tag.substring(0, m.start(1)) + data + tag.substring(m.end(1));
                }
            }
            out.append(html, last, tags.start()).append(replacement);
            last = tags.end();
        }
        out.append(html, last, html.length());
        String result = out.toString();
        return unresolved ? withBaseForDebug(result, baseUri) : result;
    }

    /** The local file a src reference points at, or null when it isn't local. */
    private static Path localFile(String ref, Path baseDir) {
        try {
            if (ref.startsWith("data:")) return null;
            if (ref.startsWith("file://")) return Path.of(URI.create(ref));
            if (ref.contains("://") || ref.startsWith("//")) return null;   // remote
            return baseDir == null ? null : baseDir.resolve(ref).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String mimeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "png"          -> "image/png";
            case "jpg", "jpeg"  -> "image/jpeg";
            case "gif"          -> "image/gif";
            case "svg"          -> "image/svg+xml";
            case "webp"         -> "image/webp";
            case "avif"         -> "image/avif";
            default             -> "application/octet-stream";
        };
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
    /**
     * Write the book's bookmark tree into the finished PDF — a viewer's
     * outline pane, built from the same structure the printed contents page
     * is (dividers, cards, index), whether or not the book prints one.
     *
     * <p>On by default: a book-length PDF with no bookmarks is hard to move
     * around in, and there is no cost to a reader who ignores the pane.
     * {@code vars.pdfBookmarks: false} turns it off.
     *
     * @param ctx     the book context, whose vars carry the toggle
     * @param entries the tree the layout engine built for this render
     */
    private void applyBookmarks(RenderContext ctx, List<OutlineEntry> entries) throws Exception {
        Object declared = ctx == null ? null : ctx.vars().get("pdfBookmarks");
        if (declared != null && !truthy(declared)) return;
        if (entries.isEmpty()) return;

        PdfOutline.Result result = PdfOutline.apply(output, entries);
        for (String anchor : result.unresolved()) {
            log.warn("Bookmark for #" + anchor + " matched no named destination"
                    + " — left out of the PDF outline");
        }
        if (result.isEmpty()) {
            log.warn("Wrote no PDF bookmarks: none of the book's " + entries.size()
                    + " outline entries matched a named destination");
        } else {
            log.info("Wrote " + result.items() + " PDF bookmark(s)");
        }
    }

    /** Yaml truthiness, matching the rest of the pipeline: true/yes/1, or a real boolean. */
    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        String s = v.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    private void applyWatermark(RenderContext ctx) throws Exception {
        Watermark watermark = resolveWatermark(ctx);
        if (watermark == null) return;
        Path baseDir = ctx != null && ctx.book() != null ? ctx.book().bookRoot() : null;
        if (WatermarkApplier.apply(output, watermark, baseDir, log::warn)) {
            log.info("Applied watermark: " + watermark.describe());
        }
    }

    /**
     * The watermark this build should stamp, or null for none.
     *
     * <p>{@link #watermarkBase} — what {@code <watermark>} /
     * {@code <watermarkImage>} declared — wins; otherwise {@code vars.watermark}
     * supplies it (bare string or full map). The individual tuning parameters
     * then override whichever fields they name on whichever spec won.
     *
     * @param ctx the render context whose vars carry the book's declaration
     * @return the resolved spec, or null when nothing declared one
     */
    private Watermark resolveWatermark(RenderContext ctx) {
        Watermark base = watermarkBase;
        if (base == null && ctx != null) base = Watermark.fromYaml(ctx.vars().get("watermark"));
        return base == null ? null : base.withOverrides(watermarkOverrides);
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
