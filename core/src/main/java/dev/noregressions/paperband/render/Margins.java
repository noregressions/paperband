package dev.noregressions.paperband.render;

import java.util.Objects;

/**
 * Page margins for PDF output. All four edges are specified independently in
 * the same {@link Unit}. Some renderers ignore these values and rely on
 * {@code @page} CSS rules instead; see {@link PageSpec} and the per-renderer
 * {@link HtmlToPdfRenderer#description() description} for caveats.
 *
 * @param top    top margin, must be non-negative
 * @param right  right margin, must be non-negative
 * @param bottom bottom margin, must be non-negative
 * @param left   left margin, must be non-negative
 * @param unit   unit in which all four margins are expressed
 */
public record Margins(double top, double right, double bottom, double left, Unit unit) {

    public Margins {
        Objects.requireNonNull(unit, "unit");
        if (top < 0 || right < 0 || bottom < 0 || left < 0) {
            throw new IllegalArgumentException("margins must be non-negative");
        }
    }

    /** 20mm on every side. */
    public static Margins standard() {
        return new Margins(20, 20, 20, 20, Unit.MM);
    }

    public static Margins of(double top, double right, double bottom, double left, Unit unit) {
        return new Margins(top, right, bottom, left, unit);
    }

    public static Margins uniform(double all, Unit unit) {
        return new Margins(all, all, all, all, unit);
    }
}
