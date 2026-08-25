package dev.noregressions.paperband.maven.pdf;

import java.util.Map;

/**
 * Watermark spec for PDF post-processing. Carries the text and the visual
 * knobs ({@link WatermarkApplier} renders one of these onto every page of
 * a finished PDF using PDFBox).
 *
 * <p>Two factory entry points cover the common cases:
 * <ul>
 *   <li>{@link #withDefaults(String)} — bare text plus all defaults; the
 *       shape used by {@code <watermark>DRAFT</watermark>}.</li>
 *   <li>{@link #fromYaml(Object)} — accepts either a bare string or a yaml
 *       map; the shape used when {@code vars.watermark} is read from
 *       {@code paperband.yaml}.</li>
 * </ul>
 *
 * <p>All knobs have sensible defaults so the POM / yaml only need to provide
 * what they want to change. The plugin's per-knob override parameters
 * ({@code <watermarkColor>}, {@code <watermarkOpacity>}, etc.) layer on
 * top of whichever base spec was resolved.
 *
 * @param text     watermark text; required, non-blank
 * @param color    fill colour as a 6-digit hex string (with or without leading {@code #})
 * @param opacity  fill alpha in [0, 1]; 0 is fully transparent, 1 fully opaque
 * @param angle    rotation in degrees; negative rotates clockwise from the horizontal,
 *                 the conventional "diagonal stamp" reading uses {@code -30f}
 * @param fontSize font size in points (PDF user units)
 * @param bold     true to use Helvetica-Bold; false for Helvetica
 */
public record Watermark(
        String text,
        String color,
        float opacity,
        float angle,
        int fontSize,
        boolean bold) {

    public static final String DEFAULT_COLOR = "#888888";
    public static final float DEFAULT_OPACITY = 0.12f;
    public static final float DEFAULT_ANGLE = -30f;
    public static final int DEFAULT_FONT_SIZE = 96;
    public static final boolean DEFAULT_BOLD = true;

    public Watermark {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("watermark text must not be blank");
        }
        if (opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException(
                    "watermark opacity must be in [0, 1], got " + opacity);
        }
        if (fontSize < 8) {
            throw new IllegalArgumentException(
                    "watermark font size must be at least 8pt, got " + fontSize);
        }
        if (color == null || color.isBlank()) color = DEFAULT_COLOR;
    }

    /** Construct with sensible defaults for everything but {@code text}. */
    public static Watermark withDefaults(String text) {
        return new Watermark(text, DEFAULT_COLOR, DEFAULT_OPACITY,
                DEFAULT_ANGLE, DEFAULT_FONT_SIZE, DEFAULT_BOLD);
    }

    /**
     * Parse a watermark from a yaml node read out of {@code paperband.yaml}.
     * Accepts either a bare string ({@code watermark: "DRAFT"}, all defaults
     * applied), or a map with at least a {@code text} key plus any of the
     * optional knobs:
     *
     * <pre>
     * watermark:
     *   text: "SAMPLE"
     *   color: "#888888"
     *   opacity: 0.12
     *   angle: -30
     *   font_size: 96
     *   bold: true
     * </pre>
     *
     * @return parsed watermark, or null if {@code node} is null / blank /
     *         a map without a non-blank {@code text} field
     */
    public static Watermark fromYaml(Object node) {
        if (node == null) return null;
        if (node instanceof String s) {
            return s.isBlank() ? null : withDefaults(s);
        }
        if (node instanceof Map<?, ?> m) {
            Object t = m.get("text");
            if (!(t instanceof String s) || s.isBlank()) return null;
            return new Watermark(
                    s,
                    stringOr(m.get("color"), DEFAULT_COLOR),
                    floatOr(m.get("opacity"), DEFAULT_OPACITY),
                    floatOr(m.get("angle"), DEFAULT_ANGLE),
                    intOr(m.get("font_size"), DEFAULT_FONT_SIZE),
                    boolOr(m.get("bold"), DEFAULT_BOLD));
        }
        return null;
    }

    /** Returned a copy with selected knobs overridden; null overrides keep the existing value. */
    public Watermark withOverrides(
            String color, Float opacity, Float angle, Integer fontSize, Boolean bold) {
        return new Watermark(
                this.text,
                color != null ? color : this.color,
                opacity != null ? opacity : this.opacity,
                angle != null ? angle : this.angle,
                fontSize != null ? fontSize : this.fontSize,
                bold != null ? bold : this.bold);
    }

    private static String stringOr(Object v, String fallback) {
        return (v instanceof String s && !s.isBlank()) ? s : fallback;
    }
    private static float floatOr(Object v, float fallback) {
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) {
            try { return Float.parseFloat(s.trim()); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }
    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }
    private static boolean boolOr(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("yes") || t.equals("1")) return true;
            if (t.equals("false") || t.equals("no") || t.equals("0")) return false;
        }
        return fallback;
    }
}
