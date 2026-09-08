package dev.noregressions.paperband.render.pptx;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Media;
import dev.noregressions.paperband.render.playwright.LoadedPage;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads a composed book document as slides: one entry per {@code article.card},
 * carrying the card's slot regions with their settled geometry and type.
 *
 * <h2>Why there is no stylesheet parser here</h2>
 * The obvious way to turn a themed HTML page into styled shapes is to parse the
 * theme's CSS. It doesn't survive contact with the bundled themes, which use
 * {@code var()} in 416 places, {@code max()} in 32 and {@code calc()} in 14,
 * across 40 {@code @media} blocks — and three of the custom properties they
 * read ({@code --pw-content-height}, {@code --pw-page-margin-*},
 * {@code --pw-font-scale}) aren't in any stylesheet at all: {@code LayoutEngine}
 * stamps them onto {@code <html>} at build time. Twenty-one flex and grid
 * containers then do the actual positioning, so resolved style tokens alone
 * still wouldn't place anything.
 *
 * <p>So the harvest asks the browser instead. {@code getComputedStyle} over a
 * fixed property allowlist ({@link #PROPS}) is the "constrained CSS reader":
 * Chromium has already done the cascade, the custom-property substitution, the
 * unit arithmetic, the media-query evaluation and the layout, and this reads
 * back only settled numbers. Paperband already pays for that Chromium — the
 * page-budget check measures the same way.
 *
 * <h2>Runs, not blocks</h2>
 * pptx styling is per-run, so the harvest descends to <em>text nodes</em> and
 * reads each one's own parent. Reading style at block level instead loses every
 * inline distinction — an inline {@code <code>} span silently renders in the
 * paragraph's body font.
 *
 * <h2>Two tiers</h2>
 * Prose becomes native text; anything with no text-placeholder equivalent
 * becomes a picture of itself, captured from the same page and placed at the
 * rect it occupied. {@link #FIGURE_SELECTOR} is the dividing line: a Mermaid or
 * PlantUML {@code <svg>}, a {@code <table>}, an {@code <img>}, a
 * {@code <figure>}, and — deliberately — a {@code <pre>}.
 *
 * <p>Code fences could in principle be text: they are text. But a highlighted
 * fence is a thicket of inline spans over whitespace that must not collapse and
 * lines that must not wrap, and pptx text boxes are poor at all three. A
 * pixel-exact picture is worth more on a slide than an editable approximation,
 * because a code block on a slide gets read, not edited. The trade is that it
 * can't be edited in PowerPoint — change the markdown and rebuild.
 */
final class SlideHarvest {

    /**
     * The properties read per element. Deliberately short: every entry has to
     * map onto something pptx can express, and one that doesn't is a silent
     * fidelity loss rather than an error.
     */
    static final String PROPS =
            "['fontFamily','fontSize','fontWeight','fontStyle','color',"
            + "'textAlign','lineHeight','letterSpacing','textTransform']";

    /**
     * Region selectors, in the order their content should fill a layout's
     * content placeholders. These are the classes a slot-based deck template
     * emits around {@code card.slots.take(...)} — the slot skeleton is the
     * contract between theme and exporter.
     */
    static final String BODY_SELECTOR  = ".slide-body";
    static final String ASIDE_SELECTOR = ".slide-aside";

    /**
     * Elements that become a picture rather than text. See the class javadoc
     * for why {@code pre} is on this list.
     *
     * <p>{@code svg} covers both diagram renderers: Mermaid and PlantUML both
     * land as inline SVG in the card body.
     */
    static final String FIGURE_SELECTOR = "svg, table, pre, img, figure";

    /** Elements that become native text runs. */
    static final String TEXT_SELECTOR = "p, li, h2, h3, h4, h5, h6, blockquote";

    /** The attribute the harvest stamps on a figure so Java can screenshot it. */
    static final String FIGURE_ATTR = "data-pb-figure";

    /** Authored speaker notes: {@code take('notes')}, hidden by the theme. */
    static final String NOTES_SELECTOR = ".notes:not(.notes--unplaced)";

    /**
     * Leftovers: {@code rest()}. Routed to speaker notes so nothing is lost,
     * but marked so the renderer can warn — a heading typo that quietly
     * relocated a visible region is worse than a build error, because the
     * slide still looks finished.
     */
    static final String UNPLACED_SELECTOR = ".notes--unplaced";

    private SlideHarvest() {}

    private static final String SCRIPT = """
        () => {
          const PROPS = %s;
          const style = el => { const cs = getComputedStyle(el); const o = {};
                                for (const p of PROPS) o[p] = cs[p]; return o; };
          const runsOf = el => {
            const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            const out = []; let n;
            while ((n = w.nextNode())) {
              if (!n.nodeValue || !n.nodeValue.trim()) continue;
              out.push({text: n.nodeValue.replace(/\\s+/g, ' '), style: style(n.parentElement)});
            }
            return out;
          };
          const rectIn = (card, el) => {
            const c = card.getBoundingClientRect(), r = el.getBoundingClientRect();
            return {x: r.left - c.left, y: r.top - c.top, w: r.width, h: r.height};
          };
          const FIG = '%s';
          const TEXT = '%s';
          const region = (card, sel, tag) => {
            const el = card.querySelector(sel);
            if (!el) return null;
            const paras = [];
            const figures = [];
            for (const node of el.querySelectorAll(TEXT + ', ' + FIG)) {
              if (node.matches(FIG)) {
                // A figure inside a figure travels with its parent: an <svg>
                // in a <figure>, or a <pre> in a <table>, must not be captured
                // twice and placed on top of itself.
                if (node.parentElement && node.parentElement.closest(FIG)) continue;
                const r = node.getBoundingClientRect();
                if (r.width < 1 || r.height < 1) continue;      // nothing to capture
                const id = tag + '-' + figures.length;
                node.setAttribute('%s', id);
                figures.push({id: id, kind: node.tagName.toLowerCase(),
                              rect: rectIn(card, node)});
                continue;
              }
              // Text inside a table or a code fence belongs to that picture,
              // not to the slide's prose.
              if (node.closest(FIG)) continue;
              if (node.tagName !== 'LI' && node.closest('li')) continue;
              const runs = runsOf(node);
              if (!runs.length) continue;
              paras.push({bullet: node.tagName === 'LI', runs: runs, style: style(node)});
            }
            return {rect: rectIn(card, el), paras: paras, figures: figures};
          };
          const textOf = (card, sel) => {
            const el = card.querySelector(sel);
            if (!el) return null;
            // The block's own heading is a slot marker, not notes content:
            // '## Notes' is how the author addressed the slot, and repeating
            // it as the first word of every notes pane is just noise.
            const clone = el.cloneNode(true);
            for (const h of clone.querySelectorAll('h2, h3, h4, h5, h6')) h.remove();
            const text = clone.textContent.replace(/\\s+/g, ' ').trim();
            return text.length ? text : null;
          };
          return Array.from(document.querySelectorAll('article.card')).map((card, ci) => {
            const h1  = card.querySelector('h1.card-title');
            const one = card.querySelector('.oneliner');
            return {
              id: card.id,
              background: getComputedStyle(card).backgroundColor,
              title:    h1  ? {text: h1.textContent.trim(),  rect: rectIn(card, h1),  style: style(h1)}  : null,
              subtitle: one ? {text: one.textContent.trim(), rect: rectIn(card, one), style: style(one)} : null,
              body:  region(card, '%s', 'c' + ci + 'b'),
              aside: region(card, '%s', 'c' + ci + 'a'),
              notes:    textOf(card, '%s'),
              unplaced: textOf(card, '%s')
            };
          });
        }
        """.formatted(PROPS, FIGURE_SELECTOR, TEXT_SELECTOR, FIGURE_ATTR,
                BODY_SELECTOR, ASIDE_SELECTOR, NOTES_SELECTOR, UNPLACED_SELECTOR);

    /**
     * How much bigger than its CSS size a figure is captured. Placed back at
     * the CSS rect, the extra pixels become resolution rather than size, which
     * is what keeps a diagram crisp on a projector and in print.
     */
    private static final int FIGURE_SCALE = 2;

    /**
     * A harvested document: the cards, and the captured figures keyed by the
     * id the harvest stamped on each one.
     *
     * <p>Two fields rather than bytes inside the card maps because
     * {@code page.evaluate} returns JSON — an image can't ride back through it,
     * so the figures are captured in a second pass over the same live page.
     */
    record Harvest(List<Map<String, Object>> cards, Map<String, byte[]> figures) {}

    /**
     * Load the document, read the cards, and capture their figures.
     *
     * <p>The viewport is pinned to the page's own width for the same reason the
     * page-budget measurer pins it: Chromium's print layout resolves wrapping
     * and percentage widths against the target page width, so measuring at the
     * default viewport would report geometry the printed page contradicts.
     *
     * @param html            the composed document
     * @param baseUri         base URI for relative assets
     * @param viewportWidthPx the page's resolved width in CSS px
     * @param onWarn          receives survivable warnings; may be null
     */
    @SuppressWarnings("unchecked")
    static Harvest harvest(String html, URI baseUri, int viewportWidthPx, Consumer<String> onWarn) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            try {
                BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                        .setBaseURL(baseUri == null ? null : baseUri.toString())
                        .setViewportSize(viewportWidthPx, 20_000)
                        // Figures are captured from this page, so the scale
                        // factor is what gives them their resolution.
                        .setDeviceScaleFactor(FIGURE_SCALE));
                Page page = ctx.newPage();
                // Print media, so the harvest sees the same theme layer and the
                // same screen-only affordances (copy buttons) the PDF pass does.
                page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
                try (LoadedPage loaded = LoadedPage.open(page, html, baseUri, onWarn)) {
                    List<Map<String, Object>> cards =
                            (List<Map<String, Object>>) page.evaluate(SCRIPT);
                    return new Harvest(cards, capture(page, cards, onWarn));
                }
            } finally {
                browser.close();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Slide harvest failed: " + describe(e), e);
        }
    }

    /**
     * Screenshot every tagged figure. A capture that fails is warned about and
     * dropped rather than failing the build: one unrenderable diagram should
     * cost its own slide a picture, not the whole deck.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> capture(Page page, List<Map<String, Object>> cards,
                                               Consumer<String> onWarn) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map<String, Object> card : cards) {
            for (String key : new String[] {"body", "aside"}) {
                Object region = card.get(key);
                if (!(region instanceof Map<?, ?> r)) continue;
                Object figures = r.get("figures");
                if (!(figures instanceof List<?> list)) continue;
                for (Object f : list) {
                    if (!(f instanceof Map<?, ?> fig)) continue;
                    String id = String.valueOf(fig.get("id"));
                    try {
                        ElementHandle el = page.querySelector("[" + FIGURE_ATTR + "='" + id + "']");
                        if (el == null) {
                            warn(onWarn, "Figure " + id + " vanished before it could be captured");
                            continue;
                        }
                        out.put(id, el.screenshot());
                    } catch (RuntimeException e) {
                        warn(onWarn, "Could not capture " + fig.get("kind") + " figure " + id
                                + " on card '" + card.get("id") + "': " + describe(e)
                                + " — the slide will be missing it");
                    }
                }
            }
        }
        return out;
    }

    private static void warn(Consumer<String> onWarn, String message) {
        if (onWarn != null) onWarn.accept(message);
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    /** CSS length in px, or 0 when absent or not a px value. */
    static double px(String s) {
        if (s == null) return 0;
        String v = s.trim();
        if (v.endsWith("px")) v = v.substring(0, v.length() - 2);
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;   // 'normal' (letter-spacing, line-height) and friends
        }
    }

    static int weight(String s) {
        if (s == null) return 400;
        if ("bold".equals(s)) return 700;
        if ("normal".equals(s)) return 400;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 400;
        }
    }

    /**
     * CSS generic font families mean nothing to PowerPoint, and
     * {@code getComputedStyle} hands back the <em>declared</em> list rather
     * than the face Chromium actually used — so {@code ui-monospace} arrives
     * verbatim. Map the generics to real faces and otherwise take the first
     * declared family.
     *
     * <p>The exact face is recoverable over CDP
     * ({@code CSS.getPlatformFontsForNode} reports {@code Menlo} where this
     * table only guesses), which is the upgrade path if this proves too blunt.
     */
    static String family(String css) {
        if (css == null || css.isBlank()) return "Helvetica Neue";
        for (String part : css.split(",")) {
            String f = part.replace("\"", "").replace("'", "").trim();
            if (f.isEmpty()) continue;
            String generic = GENERIC.get(f.toLowerCase(Locale.ROOT));
            return generic != null ? generic : f;
        }
        return "Helvetica Neue";
    }

    private static final Map<String, String> GENERIC = Map.ofEntries(
            Map.entry("ui-monospace", "Menlo"),
            Map.entry("monospace", "Menlo"),
            Map.entry("ui-sans-serif", "Helvetica Neue"),
            Map.entry("system-ui", "Helvetica Neue"),
            Map.entry("-apple-system", "Helvetica Neue"),
            Map.entry("sans-serif", "Helvetica Neue"),
            Map.entry("ui-serif", "Georgia"),
            Map.entry("serif", "Georgia"),
            Map.entry("cursive", "Comic Sans MS"),
            Map.entry("fantasy", "Impact"));
}
