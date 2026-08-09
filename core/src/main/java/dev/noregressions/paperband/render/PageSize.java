package dev.noregressions.paperband.render;

import java.util.Objects;

/**
 * Physical page dimensions for PDF output. Width and height are interpreted in
 * the supplied {@link Unit}; orientation is applied separately by the renderer
 * via {@link Orientation}.
 *
 * <p>The constants {@link #A4}, {@link #A5}, {@link #LETTER} and {@link #LEGAL}
 * cover the common cases; use {@link #of(double, double, Unit)} for anything else.
 *
 * @param width  page width, must be positive
 * @param height page height, must be positive
 * @param unit   unit in which {@code width} and {@code height} are expressed
 */
public record PageSize(double width, double height, Unit unit) {

    public PageSize {
        Objects.requireNonNull(unit, "unit");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("page dimensions must be positive");
        }
    }

    public static final PageSize A4     = new PageSize(210, 297, Unit.MM);
    public static final PageSize A5     = new PageSize(148, 210, Unit.MM);
    public static final PageSize LETTER = new PageSize(8.5, 11, Unit.INCH);
    public static final PageSize LEGAL  = new PageSize(8.5, 14, Unit.INCH);

    public static PageSize of(double width, double height, Unit unit) {
        return new PageSize(width, height, unit);
    }
}
