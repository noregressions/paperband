package dev.noregressions.paperband.render.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Media;
import com.microsoft.playwright.options.WaitUntilState;

import dev.noregressions.paperband.render.PdfRenderException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measures where elements land in a document <em>before</em> committing to a
 * full PDF render — no {@code Page.pdf()} call, just page layout plus a DOM
 * read. This is the primitive that replaces {@code PagesReport}'s previous
 * PDFBox/named-destination approach (which could only inspect a PDF that had
 * already been generated, and only if the renderer emitted named
 * destinations at all — a Chromium-specific behaviour). It's also meant to
 * be reused by any future trial-render pipeline that needs to know whether
 * content fits before deciding on a final layout, rather than being
 * single-purpose to page counting.
 *
 * <p>Deliberately renderer-geometry-agnostic: this class only measures pixel
 * positions. Converting a pixel offset into "which page is this on" requires
 * dividing by the resolved page content-box height, which depends on
 * {@code PageSpec} (size, margins, orientation) — that conversion is the
 * caller's job (see {@code PagesReport} for the book-page version).
 */
public final class PlaywrightPageMeasurer {

    private PlaywrightPageMeasurer() {}

    /**
     * @param id       the element's {@code id} attribute
     * @param topPx    {@code getBoundingClientRect().top} in CSS pixels, measured
     *                 on an unscrolled page — i.e. the element's offset from the
     *                 very top of the document
     * @param heightPx {@code getBoundingClientRect().height} in CSS pixels — the
     *                 element's own rendered height, independent of anything
     *                 that comes after it in the document. Needed because
     *                 {@code topPx} alone can't be divided against a neighbour's
     *                 {@code topPx} to get a page span: this measurement pass
     *                 never simulates {@code break-before}/{@code break-after}
     *                 (those are pagination-only effects, invisible in a plain
     *                 loaded page with no {@code Page.pdf()} call), so an
     *                 element that forces the next one onto a fresh page
     *                 leaves no gap in continuous flow — only this element's
     *                 own height reveals how many pages it actually needs.
     */
    public record ElementPosition(String id, double topPx, double heightPx) {}

    /**
     * One "layout unit" inside a card body: either a bare top-level {@code
     * .block} section (an H2–H6-triggered region, flowing naturally with no
     * break preference of its own) or a {@code pw-avoid-split}/{@code
     * pw-page-start} wrapper {@code div} — the hint markers {@code
     * SlotTracker.takeWithLayout}/{@code requireWithLayout} emit around one or
     * more blocks (see margin-notes' {@code _card-body.html} for the only
     * current example). Only <em>top-level</em> units are captured: a
     * {@code .block} nested inside another counted unit (whether a parent
     * block or a hint wrapper) is skipped, since it travels with its parent
     * for layout purposes and would double-count height otherwise.
     *
     * @param cardId     the enclosing card's anchor id ({@code card-{cardId}}),
     *                   or {@code null} if this unit isn't inside a recognised
     *                   card wrapper (shouldn't happen for any bundled template)
     * @param label      the first heading text found anywhere inside the unit,
     *                   for a human-readable "which section" identifier; falls
     *                   back to the unit's own non-structural CSS class name,
     *                   or {@code null} if neither is available (e.g. a
     *                   heading-less intro block — the caller degrades to a
     *                   plain page-number-only message in that case)
     * @param topPx      continuous (unpaginated) offset from document top —
     *                   only trustworthy for establishing document order and
     *                   as an offset relative to the unit's own card, never for
     *                   deriving an absolute page number by itself (same
     *                   caveat as {@link ElementPosition#topPx}, and doubly so
     *                   here since a unit's break behaviour depends on where
     *                   its *card* starts, not the raw document top)
     * @param heightPx   the unit's own rendered height
     * @param pageStart  {@code true} if this unit (or its wrapper) carries
     *                   {@code pw-page-start} — an unconditional forced break
     *                   before it, same as {@code break-before: page}
     * @param avoidSplit {@code true} if this unit (or its wrapper) carries
     *                   {@code pw-avoid-split} — {@code break-inside: avoid}:
     *                   no forced break unless the unit doesn't fit in the
     *                   remaining space on the current page, in which case the
     *                   whole thing moves to the next page rather than
     *                   splitting mid-way (a conditional break the caller has
     *                   to evaluate against its own running page-fill state,
     *                   since whether it fits depends on everything measured
     *                   before it, which this record alone doesn't carry)
     */
    public record LayoutUnit(String cardId, String label, double topPx, double heightPx,
                              boolean pageStart, boolean avoidSplit) {}

    /**
     * @param positions     every {@code id}-bearing element's measured position
     * @param units         every top-level layout unit inside a card body (see
     *                      {@link LayoutUnit}) — used to locate the first
     *                      overflowing section within a card that's already
     *                      known (via {@code positions}) to exceed its page
     *                      budget
     * @param totalHeightPx {@code document.documentElement.scrollHeight} — the
     *                      full document height, letting a caller compute an
     *                      approximate total page count the same way it
     *                      computes any other anchor's page number
     */
    public record Measurement(List<ElementPosition> positions, List<LayoutUnit> units, double totalHeightPx) {}

    /** Tall enough that scrollHeight is never bounded by the viewport itself for any realistic book. */
    private static final int VIEWPORT_HEIGHT_PX = 20_000;

    /**
     * Load {@code html} in headless Chromium with print media emulated and
     * return the vertical offset of every element carrying an {@code id}
     * attribute, in document order isn't guaranteed — callers should sort by
     * {@code topPx} themselves.
     *
     * <p><b>Viewport width matters a lot here.</b> Chromium's real print-to-PDF
     * layout uses the target page width as the effective layout width (text
     * wraps, percentage widths resolve, etc. against that, not against
     * whatever viewport the browser happens to default to). Without pinning
     * the viewport to the same width, this measurement pass would lay out
     * content against Playwright's default ~1280px viewport — a much wider
     * canvas than a typical printed page — under-counting how tall the
     * content actually gets once real pagination narrows it, and silently
     * passing overflow checks that should have failed. {@code viewportWidthPx}
     * should be the resolved page's width (accounting for orientation) in
     * CSS pixels; see {@code PagesReport} for the conversion from {@code
     * PageSpec}.
     *
     * @param html    the fully composed HTML document (same shape {@code
     *                HtmlToPdfRenderer#render} would receive)
     * @param baseUri base URI for resolving relative asset references; when
     *                it's a {@code file:} directory, the same temp-file/navigate
     *                dance {@link PlaywrightRenderer} uses is applied, so
     *                relative local assets (cover images etc.) load correctly
     * @param viewportWidthPx the page's resolved width in CSS px — pin the
     *                        browser viewport to this before measuring
     * @throws PdfRenderException if the browser can't be launched or the page
     *                             fails to load
     */
    public static Measurement measure(String html, URI baseUri, int viewportWidthPx) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            try {
                BrowserContext ctx = browser.newContext(
                        new Browser.NewContextOptions()
                                .setBaseURL(baseUri.toString())
                                .setViewportSize(viewportWidthPx, VIEWPORT_HEIGHT_PX));
                Page page = ctx.newPage();
                page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));

                Path tempHtml = null;
                try {
                    if ("file".equals(baseUri.getScheme())) {
                        Path baseDir = Path.of(baseUri);
                        if (Files.isDirectory(baseDir)) {
                            tempHtml = Files.createTempFile(baseDir, ".pagewright-measure-", ".html");
                            Files.writeString(tempHtml, html);
                            page.navigate(tempHtml.toUri().toString(),
                                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                        }
                    }
                    if (tempHtml == null) {
                        page.setContent(PlaywrightRenderer.injectBase(html, baseUri.toString()),
                                new Page.SetContentOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                    }

                    Object raw = page.evaluate(
                            "() => {"
                            + "  const isUnit = el => el.classList.contains('block')"
                            + "      || el.classList.contains('pw-avoid-split')"
                            + "      || el.classList.contains('pw-page-start');"
                            + "  const ancestorUnit = el => {"
                            + "    let p = el.parentElement;"
                            + "    while (p) { if (isUnit(p)) return p; p = p.parentElement; }"
                            + "    return null;"
                            + "  };"
                            + "  const ids = Array.from(document.querySelectorAll('[id]')).map(el => {"
                            + "    const r = el.getBoundingClientRect();"
                            + "    return {id: el.id, top: r.top, height: r.height};"
                            + "  });"
                            + "  const candidates = Array.from("
                            + "      document.querySelectorAll('.block, .pw-avoid-split, .pw-page-start'));"
                            + "  const units = candidates"
                            + "      .filter(el => ancestorUnit(el) === null)"
                            + "      .map(el => {"
                            + "        const r = el.getBoundingClientRect();"
                            + "        const card = el.closest('[id^=\"card-\"]');"
                            + "        const heading = el.querySelector('h2, h3, h4, h5, h6');"
                            + "        const cls = Array.from(el.classList)"
                            + "            .filter(c => c !== 'block' && c !== 'pw-avoid-split' && c !== 'pw-page-start')"
                            + "            .join(' ');"
                            + "        return {"
                            + "          cardId: card ? card.id : null,"
                            + "          label: heading ? heading.textContent.trim() : (cls || null),"
                            + "          top: r.top,"
                            + "          height: r.height,"
                            + "          pageStart: el.classList.contains('pw-page-start'),"
                            + "          avoidSplit: el.classList.contains('pw-avoid-split')"
                            + "        };"
                            + "      });"
                            + "  return {ids: ids, units: units, totalHeight: document.documentElement.scrollHeight};"
                            + "}");
                    return toMeasurement(raw);
                } finally {
                    if (tempHtml != null) Files.deleteIfExists(tempHtml);
                }
            } finally {
                browser.close();
            }
        } catch (PdfRenderException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            throw new PdfRenderException("Playwright measurement failed: " + msg, e);
        }
    }

    private static Measurement toMeasurement(Object raw) {
        List<ElementPosition> positions = new ArrayList<>();
        List<LayoutUnit> units = new ArrayList<>();
        double totalHeight = 0;
        if (raw instanceof Map<?, ?> m) {
            Object idsNode = m.get("ids");
            Object unitsNode = m.get("units");
            Object totalNode = m.get("totalHeight");
            if (totalNode instanceof Number n) totalHeight = n.doubleValue();
            if (idsNode instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> em)) continue;
                    Object id = em.get("id");
                    Object top = em.get("top");
                    Object height = em.get("height");
                    if (id != null && top instanceof Number tn && height instanceof Number hn && !id.toString().isBlank()) {
                        positions.add(new ElementPosition(id.toString(), tn.doubleValue(), hn.doubleValue()));
                    }
                }
            }
            if (unitsNode instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> em)) continue;
                    Object top = em.get("top");
                    Object height = em.get("height");
                    if (!(top instanceof Number tn) || !(height instanceof Number hn)) continue;
                    Object cardId = em.get("cardId");
                    Object label = em.get("label");
                    Object pageStart = em.get("pageStart");
                    Object avoidSplit = em.get("avoidSplit");
                    units.add(new LayoutUnit(
                            cardId == null ? null : cardId.toString(),
                            label == null ? null : label.toString(),
                            tn.doubleValue(), hn.doubleValue(),
                            Boolean.TRUE.equals(pageStart),
                            Boolean.TRUE.equals(avoidSplit)));
                }
            }
        }
        return new Measurement(positions, units, totalHeight);
    }
}
