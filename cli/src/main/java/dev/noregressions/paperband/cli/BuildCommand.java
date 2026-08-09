package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.CardParseException;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.cards.YamlCardTranspiler;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.PdfMetadata;
import dev.noregressions.paperband.render.RendererRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * End-to-end markdown &rarr; PDF render. Handles single cards or whole
 * directory trees.
 *
 * <p><b>Single card mode</b> (input is a {@code .md} file):
 * <ol>
 *   <li>{@link ConfigLoader} resolves the {@code pagewright.yaml} cascade.</li>
 *   <li>{@link CardLoader} parses the markdown.</li>
 *   <li>{@link LayoutEngine#render(Card, RenderContext)} produces a styled HTML page.</li>
 *   <li>The selected renderer converts that HTML to PDF.</li>
 * </ol>
 *
 * <p><b>Multi-card / book mode</b> (input is a directory):
 * <ol>
 *   <li>{@link BookWalker} flattens the directory tree into an ordered list of cards,
 *       honouring {@code order:} declarations in each {@code pagewright.yaml}.</li>
 *   <li>Each card is parsed individually so its frontmatter and folder-level
 *       axis bindings flow into a per-card render context.</li>
 *   <li>{@link LayoutEngine#renderBook(List, RenderContext)} aggregates everything into
 *       a single HTML document with the book CSS chain inlined once.</li>
 *   <li>The selected renderer converts that HTML to a single PDF.</li>
 * </ol>
 *
 * <pre>
 * pagewright build path/to/card.md out.pdf
 * pagewright build guide/content/tier1/framework topic.pdf
 * pagewright build guide whole-book.pdf
 * </pre>
 */
@Command(
        name = "build",
        mixinStandardHelpOptions = true,
        description = "Build a PDF from a single markdown card or a directory tree of cards.")
public final class BuildCommand implements Callable<Integer> {

    @Option(
            names = {"-r", "--renderer"},
            description = "Renderer name. Use `pagewright renderers` to list. Default: ${DEFAULT-VALUE}",
            defaultValue = "playwright")
    String rendererName;

    @Option(
            names = {"-t", "--target"},
            description = "Build target. Default: ${DEFAULT-VALUE}",
            defaultValue = "pdf-a4")
    String target;

    @Option(
            names = {"-s", "--page-size"},
            description = "Page size. Values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}",
            defaultValue = "A4")
    RenderCommand.PageSizeOption pageSize;

    @Option(
            names = {"--layout"},
            description = "Override the layout name (defaults to context layout, or '" + LayoutEngine.DEFAULT_LAYOUT + "' for cards / 'book' for directories).")
    String layoutOverride;

    @Option(
            names = {"--emit-html"},
            description = "Optional path to also write the rendered HTML alongside the PDF (debug).")
    Path emitHtml;

    @Option(
            names = {"--report-pages"},
            description = "After rendering, print a per-card page-count table.")
    boolean reportPages;

    @Option(
            names = {"--max-pages-per-card"},
            description = "After rendering, fail if any card spans more than N pages. "
                    + "Overrides vars.maxPagesPerCard in pagewright.yaml if both are set.")
    Integer maxPagesPerCard;

    @Option(
            names = {"--theme"},
            description = "Apply a named theme. Use `pagewright themes` to list what's available. "
                    + "User themes resolved via --theme-dir take priority over built-ins of the same name. "
                    + "Overrides any `theme:` declared in the book's pagewright.yaml.")
    String themeName;

    @Option(
            names = {"--theme-dir"},
            description = "Directory of user themes (each in its own subfolder with a manifest.txt). "
                    + "Looked up before classpath built-ins.")
    Path themeDir;

    @Option(
            names = {"--external-include-dir"},
            paramLabel = "<dir>",
            description = "Permit {{#include}} directives to read files under this directory, even "
                    + "though it is outside the book root. Repeatable. Off by default; name only "
                    + "directories you trust, since any card can then read files beneath them.")
    List<Path> externalIncludeDirs;

    @Option(
            names = {"--external-include-file"},
            paramLabel = "<file>",
            description = "Permit {{#include}} directives to read this specific file, even though it "
                    + "is outside the book root. Repeatable. Narrower than --external-include-dir.")
    List<Path> externalIncludeFiles;

    @Option(
            names = {"--select"},
            paramLabel = "<field=value>",
            description = "Book builds only: keep just the cards whose frontmatter field (falling "
                    + "back to the folder-cascaded vars entry, same resolution as axis values) "
                    + "equals the given value. E.g. --select subsystem=batch cuts a per-subsystem "
                    + "mini-guide from the full book; dividers and theme front matter rescope "
                    + "automatically since they derive from the surviving card list.")
    String select;

    @Option(
            names = {"--watermark"},
            description = "Stamp this text on every page after rendering (e.g. \"DRAFT\", \"SAMPLE\"). "
                    + "Overrides the yaml-declared watermark if any.")
    String watermarkText;

    @Option(
            names = {"--watermark-color"},
            description = "Watermark fill colour as #RRGGBB. Default: " + Watermark.DEFAULT_COLOR + ".")
    String watermarkColor;

    @Option(
            names = {"--watermark-opacity"},
            description = "Watermark fill alpha in [0, 1]. Default: 0.12.")
    Float watermarkOpacity;

    @Option(
            names = {"--watermark-angle"},
            description = "Watermark rotation in degrees. Default: -30.")
    Float watermarkAngle;

    @Option(
            names = {"--watermark-font-size"},
            description = "Watermark font size in points. Default: 96.")
    Integer watermarkFontSize;

    @Parameters(index = "0", description = "Input markdown file or directory.")
    Path input;

    @Parameters(index = "1", description = "Output PDF file.")
    Path output;

    // ── Publish-only fields (not CLI options) ──────────────────────────────
    // PublishCommand drives book builds by populating this command's fields
    // and calling buildBook() directly (same package). These three carry the
    // edition's identity and content switches; see DESIGN-publications.md.

    /** Equality select clauses (field → value, AND-ed). Overrides --select when set. */
    Map<String, String> selectClauses;

    /** Explicit card ids, unioned with the clause/predicate query result. */
    java.util.List<String> selectCards;

    /** Pebble predicate over {card, vars}; AND-ed with the field clauses. */
    String selectWhere;

    /** Edition identity map ({id, classes, title, vars}) exposed to templates as {@code edition}. */
    Map<String, Object> editionModel;

    /** Edition vars, merged topmost over each card's cascaded vars (design decision: edition wins). */
    Map<String, Object> editionVars;

    @Override
    public Integer call() throws Exception {
        try {
            if (Files.isRegularFile(input)) {
                return buildSingle();
            }
            if (Files.isDirectory(input)) {
                return buildBook();
            }
        } catch (dev.noregressions.paperband.layout.SlotPlacementException e) {
            // Structural check failed: a slot-using layout couldn't place
            // every block (or a required slot was empty). Own exit code so
            // CI can tell "card has the wrong shape" (4) apart from
            // "page budget exceeded" (3) and "build broken" (2).
            System.err.println(e.getMessage());
            return 4;
        }
        System.err.println("Input not found: " + input);
        return 2;
    }

    // ---- single-card path ----

    private Integer buildSingle() throws Exception {
        RenderContext ctx = new ConfigLoader().load(input, target, pageSize.slug());
        MarkdownPreprocessor preprocessor =
                Includes.defaultPreprocessor(ctx.book().bookRoot(),
                        includeProviderConfig(externalIncludeDirs, externalIncludeFiles),
                        ctx.vars());
        Card card = loadCard(new CardLoader(), preprocessor, input, ctx.book().cardSchema());

        ThemeBundle theme = ThemeResolver.resolve(resolveThemeName(ctx), themeDir);
        LayoutEngine layout = new LayoutEngine(ctx.book().bookRoot(), theme);
        String html = (layoutOverride != null)
                ? layout.render(card, ctx, layoutOverride)
                : layout.render(card, ctx);

        if (emitHtml != null) {
            Files.writeString(emitHtml, html, StandardCharsets.UTF_8);
            System.out.println("Wrote intermediate HTML -> " + emitHtml);
        }

        HtmlToPdfRenderer renderer = resolveRenderer();
        if (renderer == null) return 2;

        URI baseUri = input.toAbsolutePath().getParent().toUri();
        PdfMetadata metadata = card.title() != null
                ? PdfMetadata.of(card.title())
                : PdfMetadata.empty();
        HtmlInput htmlInput = new HtmlInput(html, baseUri, ctx.pageSpec(), metadata);

        renderer.render(htmlInput, output);

        Watermark watermark = resolveWatermark(ctx);
        if (watermark != null) {
            WatermarkApplier.apply(output, watermark);
            System.out.println("Applied watermark: \"" + watermark.text() + "\"");
        }

        System.out.println("Built " + input + " -> " + output
                + " (renderer=" + renderer.name()
                + ", target=" + target
                + ", size=" + pageSize.slug()
                + ", blocks=" + card.blocks().size() + ")");
        return 0;
    }

    // ---- book / multi-card path ----

    /**
     * Debug-emit helper: prepend a {@code <base href>} so the emitted HTML is
     * browser-openable from anywhere. Skips documents that already declare a
     * {@code <base>} or have no {@code <head>}. The renderer receives the
     * un-stamped html and applies its own base handling.
     */
    private static String withBaseForDebug(String html, String baseUri) {
        String lower = html.toLowerCase();
        if (lower.contains("<base ") || lower.contains("<base>")) return html;
        int head = lower.indexOf("<head>");
        if (head < 0) return html;
        int at = head + "<head>".length();
        return html.substring(0, at) + "<base href=\"" + baseUri + "\">" + html.substring(at);
    }

    private static String describeSelect(Map<String, String> clauses,
                                         List<String> inclusion, String where) {
        StringBuilder sb = new StringBuilder("(");
        if (clauses != null && !clauses.isEmpty()) sb.append("where ").append(clauses);
        if (where != null && !where.isBlank()) {
            if (sb.length() > 1) sb.append(" and ");
            sb.append("[").append(where).append("]");
        }
        if (!inclusion.isEmpty()) {
            if (sb.length() > 1) sb.append(" plus ");
            sb.append("cards ").append(inclusion);
        }
        return sb.append(")").toString();
    }

    /** Package-private so PublishCommand can drive book builds directly. */
    Integer buildBook() throws Exception {
        BookWalker walker = new BookWalker(target);
        List<Path> cardFiles = walker.walk(input);
        if (cardFiles.isEmpty()) {
            System.err.println("No cards found under " + input);
            return 2;
        }
        System.out.println("Found " + cardFiles.size() + " cards under " + input);

        ConfigLoader configLoader = new ConfigLoader();

        // Load each card's context first to discover the book root (captured
        // once, from the first card) and that card's own vars (which varies
        // per card via the folder-yaml axis cascade). The preprocessor is
        // rebuilt per card because it binds vars at construction time —
        // fragment resolution AND vars/conditionals now run in a single
        // Pebble pass (see PebbleIncludePreprocessor for why they can't be
        // split into separate passes).
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        CardLoader cardLoader = new CardLoader();
        Map<String, Map<String, Object>> providerConfig =
                includeProviderConfig(externalIncludeDirs, externalIncludeFiles);
        int totalBlocks = 0;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(cardFile, target, pageSize.slug());
            if (bookCtx == null) {
                bookCtx = ctx;     // capture book-root css/title once
            }
            contexts.add(ctx);
            Map<String, Object> vars = ctx.vars();
            if (editionVars != null && !editionVars.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(vars);
                merged.putAll(editionVars);   // edition vars sit topmost by design
                vars = merged;
            }
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), providerConfig, vars);
            Card card = loadCard(cardLoader, preprocessor, cardFile, ctx.book().cardSchema());
            cards.add(card);
            totalBlocks += card.blocks().size();
        }

        // Selection: equality clauses (field → value), AND-ed. Sources: the
        // publish path sets selectClauses directly; the --select flag supplies
        // a single clause. Resolution mirrors LayoutEngine.resolveAxisValue:
        // the card's own frontmatter field wins, the folder-cascaded vars
        // entry is the fallback. Compared as strings so numeric yaml values
        // (tier: 1) match their config spelling.
        Map<String, String> clauses = selectClauses;
        if (clauses == null && select != null) {
            int eq = select.indexOf('=');
            if (eq <= 0 || eq == select.length() - 1) {
                System.err.println("Bad --select (expected field=value): " + select);
                return 2;
            }
            clauses = Map.of(select.substring(0, eq).trim(), select.substring(eq + 1).trim());
        }
        List<String> inclusion = selectCards == null ? List.of() : selectCards;
        boolean hasWhere = selectWhere != null && !selectWhere.isBlank();
        boolean hasQuery = (clauses != null && !clauses.isEmpty()) || hasWhere;
        if (hasQuery || !inclusion.isEmpty()) {
            dev.noregressions.paperband.predicate.PredicateEvaluator predicate =
                    hasWhere ? new dev.noregressions.paperband.predicate.PredicateEvaluator() : null;
            List<Card> keptCards = new ArrayList<>();
            List<RenderContext> keptContexts = new ArrayList<>();
            for (int i = 0; i < cards.size(); i++) {
                // Union semantics (DESIGN-publications.md): explicit cards:
                // entries are hand-picked inclusions; the clauses+where form
                // one AND-ed query whose result is unioned with them.
                boolean keep = inclusion.contains(cards.get(i).id());
                if (!keep && hasQuery) {
                    keep = true;
                    if (clauses != null) {
                        for (Map.Entry<String, String> clause : clauses.entrySet()) {
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
                }
            }
            String description = describeSelect(clauses, inclusion, selectWhere);
            if (keptCards.isEmpty()) {
                System.err.println("select " + description + " matched no cards");
                return 2;
            }
            System.out.println("Selected " + keptCards.size() + " of " + cards.size()
                    + " cards " + description);
            cards = keptCards;
            contexts = keptContexts;
        }

        // Sanity: bookCtx is non-null because we returned early on empty cardFiles.
        ThemeBundle theme = ThemeResolver.resolve(resolveThemeName(bookCtx), themeDir);
        LayoutEngine layout = new LayoutEngine(bookCtx.book().bookRoot(), theme);
        if (editionModel != null) layout.setEdition(editionModel);
        String html = (layoutOverride != null)
                ? layout.renderBook(cards, contexts, bookCtx, layoutOverride)
                : layout.renderBook(cards, contexts, bookCtx);

        if (emitHtml != null) {
            // Stamp the build's base URI into the debug copy so relative
            // assets (cover images etc.) resolve when the file is opened in
            // a browser from wherever it was written — without this, a
            // browser resolves them against the emit directory and shows
            // broken images that say nothing about the real build.
            Files.writeString(emitHtml,
                    withBaseForDebug(html, input.toAbsolutePath().toUri().toString()),
                    StandardCharsets.UTF_8);
            System.out.println("Wrote intermediate HTML -> " + emitHtml);
        }

        HtmlToPdfRenderer renderer = resolveRenderer();
        if (renderer == null) return 2;

        URI baseUri = input.toAbsolutePath().toUri();
        String bookTitle = bookCtx.book().title();
        PdfMetadata metadata = bookTitle != null
                ? PdfMetadata.of(bookTitle)
                : PdfMetadata.empty();
        // Running footer (see HtmlInput.footerHtml / LayoutEngine.renderFooter
        // javadoc) — null when the book declares no footer:, in which case
        // HtmlInput just carries no footer either way.
        String footerHtml = layout.renderFooter(bookCtx);
        HtmlInput htmlInput = new HtmlInput(html, baseUri, bookCtx.pageSpec(), metadata, footerHtml);

        renderer.render(htmlInput, output);

        Watermark watermark = resolveWatermark(bookCtx);
        if (watermark != null) {
            WatermarkApplier.apply(output, watermark);
            System.out.println("Applied watermark: \"" + watermark.text() + "\"");
        }

        System.out.println("Built book " + input + " -> " + output
                + " (renderer=" + renderer.name()
                + ", target=" + target
                + ", size=" + pageSize.slug()
                + ", cards=" + cards.size()
                + ", blocks=" + totalBlocks + ")");

        return runPageChecks(cards, html, baseUri, bookCtx);
    }

    /**
     * Resolve the CLI flag against the yaml default: {@code --max-pages-per-card}
     * wins when explicitly passed (same "CLI overrides yaml" convention as
     * {@code --theme}/{@code --watermark}); otherwise falls back to {@code
     * vars.maxPagesPerCard} from {@code pagewright.yaml} (any level of the
     * cascade — book root or a folder/edition override, same as any other
     * {@code vars} entry). Returns the resolved limit plus a label for
     * reporting/error messages, or null if neither is set.
     */
    private record GlobalLimit(int value, String source) {}

    private static GlobalLimit resolveGlobalLimit(Integer cliValue, RenderContext bookCtx) {
        if (cliValue != null) return new GlobalLimit(cliValue, "--max-pages-per-card");
        Integer yamlValue = parseInt(bookCtx.vars().get("maxPagesPerCard"));
        if (yamlValue != null && yamlValue > 0) return new GlobalLimit(yamlValue, "pagewright.yaml");
        return null;
    }

    /**
     * Post-render page-count analysis. Measures the just-rendered HTML via
     * {@link PagesReport#analyseHtml} (a Playwright DOM-measurement pass —
     * see that class's javadoc for why this replaced reading the finished
     * PDF's named destinations for the build-time enforcement path) and:
     * <ul>
     *   <li>Optionally prints a report ({@code --report-pages})</li>
     *   <li>Honours per-card {@code max_pages} declared in frontmatter</li>
     *   <li>Falls back to a global ceiling — {@code --max-pages-per-card} or,
     *       if that's not passed, {@code vars.maxPagesPerCard} in
     *       {@code pagewright.yaml} — for cards that don't declare their own
     *       limit</li>
     * </ul>
     *
     * <p>Effective limit per card:
     * <ol>
     *   <li>{@code max_pages} from card frontmatter, if present</li>
     *   <li>{@code --max-pages-per-card} from the CLI, if set</li>
     *   <li>{@code vars.maxPagesPerCard} from {@code pagewright.yaml}, if set</li>
     *   <li>otherwise no enforcement for this card</li>
     * </ol>
     *
     * @return 0 if all cards within limits (or no checks requested);
     *         3 if any card exceeds its effective limit
     */
    private Integer runPageChecks(List<Card> cards, String html, URI baseUri, RenderContext bookCtx) {
        // Build the per-card limit map from frontmatter.
        Map<String, Integer> frontmatterLimits = new HashMap<>();
        for (Card c : cards) {
            Object v = c.frontmatter().values().get("max_pages");
            Integer n = parseInt(v);
            if (n != null && n > 0) frontmatterLimits.put(c.id(), n);
        }

        GlobalLimit globalLimit = resolveGlobalLimit(maxPagesPerCard, bookCtx);
        boolean haveAnyLimit = !frontmatterLimits.isEmpty() || globalLimit != null;
        if (!reportPages && !haveAnyLimit) return 0;

        PagesReport.Analysis analysis;
        try {
            analysis = PagesReport.analyseHtmlFull(html, baseUri, bookCtx.pageSpec());
        } catch (Exception e) {
            System.err.println("warn: failed to analyse pages: " + e.getMessage());
            return 0;
        }
        List<PagesReport.Row> rows = analysis.rows();
        if (rows.isEmpty()) {
            System.err.println("warn: no recognised anchors found — page-count checks skipped.");
            return 0;
        }

        if (reportPages) {
            System.out.println();
            System.out.printf("%-8s  %-50s  %5s  %5s  %5s%n", "KIND", "ID", "START", "PAGES", "LIMIT");
            System.out.println("--------  --------------------------------------------------  -----  -----  -----");
            for (PagesReport.Row r : rows) {
                String limitCol = "";
                if ("card".equals(r.kind())) {
                    String cardId = r.label();
                    Integer fm = frontmatterLimits.get(cardId);
                    if (fm != null) {
                        limitCol = String.valueOf(fm);
                    } else if (globalLimit != null) {
                        limitCol = globalLimit.value() + "*";
                    }
                }
                System.out.printf("%-8s  %-50s  %5d  %5d  %5s%n",
                        r.kind(), truncate(r.label(), 50), r.startPage(), r.span(), limitCol);
            }
            if (globalLimit != null) {
                System.out.println("(* = global limit from " + globalLimit.source() + ")");
            }
        }

        if (!haveAnyLimit) return 0;

        // Enforce: card-by-card. Reports which page numbers are actually the
        // overflow, computed straight from startPage/span/limit — no attempt
        // to attribute the overflow to a specific block at THIS stage. (An
        // earlier version tried to name the exact section crossing the page
        // break straight off the anchor-level gap math; it was inconsistent
        // — missed some offenders and misidentified others — so it's gone.
        // Once a card is confirmed over budget here, firstOverflowUnit below
        // does the section-naming instead, off the fixed cumulative/
        // own-height algorithm and its own break-hint-aware walk — see that
        // method's javadoc.)
        record Offender(String cardId, String anchor, int startPage, int pages, int limit, String source) {}
        List<Offender> offenders = new ArrayList<>();
        int checkedCount = 0;
        for (PagesReport.Row r : rows) {
            if (!"card".equals(r.kind())) continue;
            String cardId = r.label();
            Integer fm = frontmatterLimits.get(cardId);
            Integer limit;
            String source;
            if (fm != null) {
                limit = fm;
                source = "frontmatter";
            } else if (globalLimit != null) {
                limit = globalLimit.value();
                source = globalLimit.source();
            } else {
                continue;       // no limit applies to this card
            }
            checkedCount++;
            if (r.span() > limit) {
                offenders.add(new Offender(cardId, r.anchor(), r.startPage(), r.span(), limit, source));
            }
        }

        if (!offenders.isEmpty()) {
            System.err.println();
            System.err.println("Page-count check failed: " + offenders.size()
                    + " card(s) exceeded their page limit.");
            for (Offender o : offenders) {
                int lastAllowedPage = o.startPage() + o.limit() - 1;
                int lastActualPage = o.startPage() + o.pages() - 1;
                String overflowPages = (lastAllowedPage + 1 == lastActualPage)
                        ? "page " + lastActualPage
                        : "pages " + (lastAllowedPage + 1) + "-" + lastActualPage;
                System.err.printf("  %s: pages %d-%d (%d pages, limit %d, from %s) — %s overflow%n",
                        o.cardId(), o.startPage(), lastActualPage, o.pages(), o.limit(), o.source(),
                        overflowPages);

                PagesReport.firstOverflowUnit(analysis, o.anchor(), o.limit()).ifPresentOrElse(
                        loc -> {
                            if (loc.label() != null) {
                                System.err.printf("      first crossed by \"%s\" (starts page %d)%n",
                                        loc.label(), loc.page());
                            } else {
                                System.err.printf("      first crossed within an unlabelled section (starts page %d)%n",
                                        loc.page());
                            }
                        },
                        () -> System.err.println("      (no section-level detail available for this card)"));
            }
            return 3;
        }
        System.out.println("Page-count check passed: " + checkedCount
                + " card(s) checked, all within their limits.");
        return 0;
    }

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Include-provider config derived solely from operator-controlled CLI flags.
     * The trust decision to let includes escape the book root belongs to the
     * person running the build, not to the book being built — so this is never
     * populated from the book's own {@code pagewright.yaml}, and it names the
     * specific directories/files trusted rather than flipping a blanket switch.
     */
    static Map<String, Map<String, Object>> includeProviderConfig(
            List<Path> externalDirs, List<Path> externalFiles) {
        List<String> dirs = absoluteStrings(externalDirs);
        List<String> files = absoluteStrings(externalFiles);
        if (dirs.isEmpty() && files.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> fileCfg = new HashMap<>();
        if (!dirs.isEmpty())  fileCfg.put("external_roots", dirs);
        if (!files.isEmpty()) fileCfg.put("external_files", files);
        return Map.of("file", Map.copyOf(fileCfg));
    }

    private static List<String> absoluteStrings(List<Path> paths) {
        if (paths == null) return List.of();
        List<String> out = new ArrayList<>(paths.size());
        for (Path p : paths) {
            if (p != null) out.add(p.toAbsolutePath().normalize().toString());
        }
        return out;
    }

    /**
     * Read {@code cardFile} and run the pre-flexmark preprocessing pass
     * (fragment resolution + vars/conditionals — see
     * {@code PebbleIncludePreprocessor} in {@code pagewright-include}), then
     * parse the result into a {@link Card} via {@link CardLoader#parse(Path, String)}.
     *
     * <p>A {@code .yaml}/{@code .yml} card file is first transpiled to
     * markdown via {@link YamlCardTranspiler}, driven by the book's
     * {@code cardSchema:} (see {@code CardSchema}). The transpile happens
     * <em>before</em> the preprocessor so includes/vars/conditionals behave
     * identically for both card formats.
     *
     * <p>Bypasses {@link CardLoader#load(Path)} because {@code preprocessor}
     * varies per card (it binds that card's {@code vars}) and {@code load}
     * only knows about a single configured preprocessor instance — calling
     * {@code parse} directly here keeps a single {@link CardLoader} (and its
     * underlying flexmark engine) shared across the whole book.
     */
    static Card loadCard(
            CardLoader cardLoader,
            MarkdownPreprocessor preprocessor,
            Path cardFile,
            CardSchema cardSchema) {
        String source;
        try {
            source = Files.readString(cardFile, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new CardParseException("Failed to read " + cardFile, e);
        }
        if (YamlCardTranspiler.isYamlCard(cardFile)) {
            source = new YamlCardTranspiler().transpile(cardFile, source, cardSchema);
        }
        if (preprocessor != null) {
            source = preprocessor.process(source, cardFile);
        }
        return cardLoader.parse(cardFile, source);
    }

    /**
     * Resolve the active watermark by combining CLI flags and yaml.
     *
     * <ol>
     *   <li>If {@code --watermark} is set, the base spec comes from CLI text
     *       with default knobs.</li>
     *   <li>Otherwise the base spec comes from {@code vars.watermark} in
     *       {@code pagewright.yaml} (bare string or full map; see
     *       {@link Watermark#fromYaml}).</li>
     *   <li>CLI tuning flags ({@code --watermark-color}, etc.) override
     *       whichever fields they target on the resolved spec.</li>
     * </ol>
     *
     * @return resolved watermark, or null when neither CLI nor yaml asked for one
     */
    private Watermark resolveWatermark(RenderContext ctx) {
        Watermark base = null;
        if (watermarkText != null && !watermarkText.isBlank()) {
            base = Watermark.withDefaults(watermarkText);
        } else if (ctx != null) {
            base = Watermark.fromYaml(ctx.vars().get("watermark"));
        }
        if (base == null) return null;
        return base.withOverrides(
                watermarkColor, watermarkOpacity, watermarkAngle, watermarkFontSize, null);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // ---- helpers ----

    /**
     * Resolve the active theme name: {@code --theme} CLI flag wins, otherwise
     * fall back to the {@code theme:} declared in the book's {@code pagewright.yaml}.
     * Returns null when neither is set, which {@link ThemeResolver} treats as
     * "no theme" ({@link ThemeBundle#NONE}).
     */
    private String resolveThemeName(RenderContext ctx) {
        if (themeName != null && !themeName.isBlank()) return themeName;
        if (ctx != null && ctx.book() != null) return ctx.book().theme();
        return null;
    }

    private HtmlToPdfRenderer resolveRenderer() {
        RendererRegistry registry = RendererRegistry.discover();
        HtmlToPdfRenderer renderer = registry.get(rendererName).orElse(null);
        if (renderer == null) {
            System.err.println("Unknown renderer: " + rendererName);
            System.err.println("Available: " + registry.all().stream()
                    .map(HtmlToPdfRenderer::name).toList());
        }
        return renderer;
    }

}
