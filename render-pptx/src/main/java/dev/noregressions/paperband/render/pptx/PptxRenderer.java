package dev.noregressions.paperband.render.pptx;

import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.Orientation;
import dev.noregressions.paperband.render.PageSize;
import dev.noregressions.paperband.render.PdfRenderException;
import dev.noregressions.paperband.render.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes a book as a PowerPoint deck of native, editable shapes.
 *
 * <p>One {@code article.card} becomes one slide. The card's slot regions — the
 * {@code .slide-body} / {@code .slide-aside} wrappers a slot-based deck
 * template emits around {@code card.slots.take(...)} — fill <em>typed
 * placeholders</em> on a shared {@code slideLayout} chosen from the card's slot
 * signature, so the result behaves like a deck someone authored rather than one
 * that was imported.
 *
 * <p>Requires a deck theme: the slot regions are the contract, and a book built
 * with a prose theme has no {@code .slide-body} to read. See
 * {@code themes/deck}.
 *
 * <p>Text becomes native shapes; a diagram, table or code fence becomes a
 * picture of itself at the rect it occupied. See {@link SlideHarvest} for where
 * that line is drawn.
 *
 * <p>This class is the thin outer shell — geometry, the browser call, and the
 * build log. {@link SlideHarvest} reads the document; {@link DeckWriter} builds
 * the file; {@link LayoutMap} decides which layout each card lands on.
 *
 * <h2>What this deliberately does not do</h2>
 * There is no CSS parser — {@link SlideHarvest} explains why the browser is the
 * CSS engine instead. There is also no whole-slide fallback: a card that
 * defeats both tiers loses the part that defeated them, with a warning, rather
 * than silently becoming a flat image of itself.
 */
public final class PptxRenderer implements HtmlToPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(PptxRenderer.class);

    @Override
    public String name() {
        return "pptx";
    }

    @Override
    public String description() {
        return "PowerPoint .pptx of native editable shapes (Apache POI XSLF). "
                + "Requires headless Chromium (Playwright) to resolve the theme's "
                + "layout and styling, and a slot-based deck theme to supply the "
                + "slide skeleton. Diagrams, tables and code fences are exported "
                + "as pictures rather than text.";
    }

    /**
     * False: the output is a .pptx, so the build must skip the PDF post-passes
     * that would otherwise reopen it with PDFBox.
     */
    @Override
    public boolean producesPdf() {
        return false;
    }

    @Override
    public void render(HtmlInput input, Path output) throws PdfRenderException {
        try {
            int widthPx = (int) Math.round(pxOf(input, true));
            int heightPx = (int) Math.round(pxOf(input, false));

            SlideHarvest.Harvest harvest =
                    SlideHarvest.harvest(input.html(), input.baseUri(), widthPx, log::warn);
            List<Map<String, Object>> cards = harvest.cards();

            if (cards.isEmpty()) {
                throw new PdfRenderException(
                        "No slides found: the pptx renderer reads one slide per "
                        + "'article.card', and this book produced none. A cover-only "
                        + "or divider-only build has nothing to export, and a book on "
                        + "a prose theme has no slot regions to read — see themes/deck.");
            }

            DeckWriter.Result result = DeckWriter.write(
                    cards, harvest.figures(), widthPx, heightPx, output, log::warn);

            log.info("Wrote {} slide(s) at {}x{}pt to {}", result.slides(),
                    Math.round(widthPx * DeckWriter.PT),
                    Math.round(heightPx * DeckWriter.PT), output);
            result.layoutUse().forEach((k, v) -> log.info("  layout {} x{}", k, v));
            if (result.pictures() > 0) {
                log.info("  {} figure(s) placed as pictures (diagrams, tables, code fences)",
                        result.pictures());
            }
            if (!result.unplaced().isEmpty()) {
                log.warn("{} card(s) had unplaced blocks ({}). They are in the speaker"
                        + " notes, not on the slides.",
                        result.unplaced().size(), String.join(", ", result.unplaced()));
            }
        } catch (PdfRenderException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // LayoutMap's "no layout for this signature" — an authoring error,
            // so it should read as one rather than as a renderer crash.
            throw new PdfRenderException(e.getMessage(), e);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            throw new PdfRenderException("pptx render failed: " + msg, e);
        }
    }

    /** The page's resolved width or height in CSS px, honouring orientation. */
    static double pxOf(HtmlInput input, boolean width) {
        PageSize size = input.pageSpec().size();
        boolean landscape = input.pageSpec().orientation() == Orientation.LANDSCAPE;
        double value = width == !landscape ? size.width() : size.height();
        return toPx(value, size.unit());
    }

    private static double toPx(double value, Unit unit) {
        return unit.toMillimetres(value) / 25.4 * 96.0;
    }
}
