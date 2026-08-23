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

    /**
     * Parse a CSS-style margin shorthand — the form a build tool's single
     * scalar parameter can carry, e.g. the Maven plugin's
     * {@code <margins>} or {@code -Dpaperband.margins=0}.
     *
     * <p>One to four whitespace- or comma-separated lengths, read exactly as
     * CSS reads them:
     *
     * <pre>
     * 0                &rarr; every edge zero — a full-bleed build
     * 18mm             &rarr; 18mm on every edge
     * 20mm 15mm        &rarr; vertical, horizontal
     * 20mm 15mm 25mm   &rarr; top, horizontal, bottom
     * 20 15 25 15      &rarr; top, right, bottom, left
     * </pre>
     *
     * <p>Each length may carry a unit — {@code mm} (the default when omitted),
     * {@code cm}, {@code in}/{@code inch}, or {@code pt}/{@code point}. Mixed
     * units are fine; everything is normalised to millimetres, since
     * {@link Margins} expresses all four edges in one unit.
     *
     * @param value the shorthand; null or blank returns null, meaning "no
     *              override, keep whatever the page preset supplies"
     * @return the parsed margins, or null when {@code value} declares nothing
     * @throws IllegalArgumentException if the shorthand isn't one to four
     *         non-negative lengths in a recognised unit
     */
    public static Margins parse(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.trim().split("[\\s,]+");
        if (parts.length > 4) {
            throw new IllegalArgumentException(
                    "margins: expected 1 to 4 lengths (CSS shorthand order), got " + parts.length
                            + ": '" + value + "'");
        }
        double[] mm = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            mm[i] = parseLengthMm(parts[i], value);
        }
        return switch (parts.length) {
            case 1 -> uniform(mm[0], Unit.MM);
            case 2 -> new Margins(mm[0], mm[1], mm[0], mm[1], Unit.MM);
            case 3 -> new Margins(mm[0], mm[1], mm[2], mm[1], Unit.MM);
            default -> new Margins(mm[0], mm[1], mm[2], mm[3], Unit.MM);
        };
    }

    /** One length of a shorthand, in millimetres. */
    private static double parseLengthMm(String token, String whole) {
        String t = token.trim().toLowerCase(java.util.Locale.ROOT);
        Unit unit = Unit.MM;
        double factor = 1.0;
        if (t.endsWith("mm")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("cm")) {
            // Not a Unit of its own; 1cm is 10mm and everything normalises to mm.
            t = t.substring(0, t.length() - 2);
            factor = 10.0;
        } else if (t.endsWith("inch")) {
            t = t.substring(0, t.length() - 4);
            unit = Unit.INCH;
        } else if (t.endsWith("in")) {
            t = t.substring(0, t.length() - 2);
            unit = Unit.INCH;
        } else if (t.endsWith("point")) {
            t = t.substring(0, t.length() - 5);
            unit = Unit.POINT;
        } else if (t.endsWith("pt")) {
            t = t.substring(0, t.length() - 2);
            unit = Unit.POINT;
        }
        double raw;
        try {
            raw = Double.parseDouble(t.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "margins: '" + token + "' is not a length (in '" + whole
                            + "'). Use e.g. 0, 18mm, 0.75in, 54pt.", e);
        }
        if (raw < 0) {
            throw new IllegalArgumentException(
                    "margins: negative length '" + token + "' in '" + whole + "'");
        }
        return unit.toMillimetres(raw) * factor;
    }
}
