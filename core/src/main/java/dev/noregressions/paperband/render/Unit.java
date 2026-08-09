package dev.noregressions.paperband.render;

/**
 * Length unit used to express page dimensions and margins. Conversions to
 * millimetres and PDF points are provided so renderers can normalise input
 * regardless of the unit chosen by the caller.
 */
public enum Unit {
    MM, INCH, POINT;

    /** Convert {@code value} expressed in this unit to millimetres. */
    public double toMillimetres(double value) {
        return switch (this) {
            case MM    -> value;
            case INCH  -> value * 25.4;
            case POINT -> value * 25.4 / 72.0;
        };
    }

    /** Convert {@code value} expressed in this unit to PDF points (1/72 inch). */
    public double toPoints(double value) {
        return switch (this) {
            case MM    -> value * 72.0 / 25.4;
            case INCH  -> value * 72.0;
            case POINT -> value;
        };
    }
}
