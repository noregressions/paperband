package dev.noregressions.paperband.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Watermark spec: the text (or image) to stamp across a page, plus the visual
 * knobs that place it.
 *
 * <p>Lives in the model rather than beside the PDF stamper because two very
 * different renderers consume it. The PDF path draws it with PDFBox as a
 * post-pass on the finished file; the site and the {@code emitHtml} copy paint
 * the same declaration as a CSS overlay. One spec, so a book that says
 * {@code DRAFT} says it in both places and can't drift.
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
 * top of whichever base spec was resolved — see {@link Overrides}.
 *
 * @param text     watermark text. Exactly one of {@code text} / {@code image}
 *                 is set. May carry newlines: each line is stamped in turn,
 *                 centred, rather than running off the page edge.
 * @param image    book-root-relative path to a watermark image (png/jpg/gif),
 *                 as an alternative to {@code text}
 * @param font     path to a TrueType font to embed, book-root-relative. Needed
 *                 only for text the built-in Helvetica can't encode — anything
 *                 outside WinAnsi (CJK, Cyrillic, Greek). Null uses Helvetica.
 * @param color    fill colour as a 6-digit hex string (with or without leading {@code #})
 * @param opacity  fill alpha in [0, 1]; 0 is fully transparent, 1 fully opaque
 * @param angle    rotation in degrees; negative rotates clockwise from the horizontal,
 *                 the conventional "diagonal stamp" reading uses {@code -30f}
 * @param fontSize font size in points (PDF user units). With {@link #fit} on
 *                 this is a ceiling rather than an exact size.
 * @param bold     true to use Helvetica-Bold; false for Helvetica. Ignored when
 *                 {@link #font} names a font file.
 * @param scale    for an image watermark, its width as a fraction of the page
 *                 width, in (0, 1]. Ignored for text.
 * @param fit      shrink the stamp until it fits inside the page. On by default:
 *                 the alternative is silent overflow off the paper edge.
 * @param behind   draw underneath the page content instead of over it
 * @param tile     repeat the stamp across the whole page rather than centring one
 * @param pages    which pages get stamped
 */
public record Watermark(
        String text,
        String image,
        String font,
        String color,
        float opacity,
        float angle,
        int fontSize,
        boolean bold,
        float scale,
        boolean fit,
        boolean behind,
        boolean tile,
        Pages pages) {

    public static final String DEFAULT_COLOR = "#888888";
    public static final float DEFAULT_OPACITY = 0.12f;
    public static final float DEFAULT_ANGLE = -30f;
    public static final int DEFAULT_FONT_SIZE = 96;
    public static final boolean DEFAULT_BOLD = true;
    public static final float DEFAULT_SCALE = 0.5f;
    public static final boolean DEFAULT_FIT = true;
    public static final boolean DEFAULT_BEHIND = false;
    public static final boolean DEFAULT_TILE = false;

    /** Smallest font size worth stamping; also the floor {@link #fit} shrinks to. */
    public static final int MIN_FONT_SIZE = 8;

    /**
     * Which pages of the finished document a watermark lands on.
     *
     * <p>{@link #EXCEPT_COVER} exists because a book's cover is usually a
     * designed page — a full-bleed image, a title block — and a {@code DRAFT}
     * stamp across it is the one place the mark reads as damage rather than
     * as status.
     */
    public enum Pages {
        /** Every page. The default. */
        ALL,
        /** Page one only — a title-page stamp. */
        FIRST,
        /** Every page but the first. */
        EXCEPT_COVER;

        /**
         * Parse a yaml/parameter spelling: {@code all}, {@code first},
         * {@code except-cover} (or {@code except_cover}). Case-insensitive.
         *
         * @param s the declared value
         * @return the parsed selection
         * @throws IllegalArgumentException if it names none of them
         */
        public static Pages parse(String s) {
            String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (v) {
                case "all" -> ALL;
                case "first" -> FIRST;
                case "except-cover" -> EXCEPT_COVER;
                default -> throw new IllegalArgumentException(
                        "watermark pages must be one of all, first, except-cover; got '" + s + "'");
            };
        }

        /** @param index zero-based page index @return true when that page is stamped */
        public boolean includes(int index) {
            return switch (this) {
                case ALL -> true;
                case FIRST -> index == 0;
                case EXCEPT_COVER -> index > 0;
            };
        }
    }

    public Watermark {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isBlank();
        if (!hasText && !hasImage) {
            throw new IllegalArgumentException(
                    "watermark needs 'text' or 'image'");
        }
        if (hasText && hasImage) {
            throw new IllegalArgumentException(
                    "watermark declares both 'text' and 'image' — pick one "
                            + "(a logo with wording in it is an image)");
        }
        if (opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException(
                    "watermark opacity must be in [0, 1], got " + opacity);
        }
        if (hasText && fontSize < MIN_FONT_SIZE) {
            throw new IllegalArgumentException(
                    "watermark font size must be at least " + MIN_FONT_SIZE + "pt, got " + fontSize);
        }
        if (hasImage && (scale <= 0f || scale > 1f)) {
            throw new IllegalArgumentException(
                    "watermark scale must be in (0, 1], got " + scale);
        }
        if (color == null || color.isBlank()) color = DEFAULT_COLOR;
        if (pages == null) pages = Pages.ALL;
        if (!hasText) text = null;
        if (!hasImage) image = null;
    }

    /** Construct with sensible defaults for everything but {@code text}. */
    public static Watermark withDefaults(String text) {
        return new Watermark(text, null, null, DEFAULT_COLOR, DEFAULT_OPACITY,
                DEFAULT_ANGLE, DEFAULT_FONT_SIZE, DEFAULT_BOLD, DEFAULT_SCALE,
                DEFAULT_FIT, DEFAULT_BEHIND, DEFAULT_TILE, Pages.ALL);
    }

    /** Construct an image watermark with sensible defaults for everything else. */
    public static Watermark imageWithDefaults(String image) {
        return new Watermark(null, image, null, DEFAULT_COLOR, DEFAULT_OPACITY,
                DEFAULT_ANGLE, DEFAULT_FONT_SIZE, DEFAULT_BOLD, DEFAULT_SCALE,
                DEFAULT_FIT, DEFAULT_BEHIND, DEFAULT_TILE, Pages.ALL);
    }

    /** The keys a {@code watermark:} map may carry, in the order the docs list them. */
    public static final Set<String> KEYS = new LinkedHashSet<>(List.of(
            "text", "image", "font", "color", "opacity", "angle",
            "font_size", "fontSize", "bold", "scale", "fit", "behind", "tile", "pages"));

    /**
     * Parse a watermark from a yaml node read out of {@code paperband.yaml}.
     * Accepts either a bare string ({@code watermark: "DRAFT"}, all defaults
     * applied), or a map with {@code text} (or {@code image}) plus any of the
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
     *   fit: true
     *   behind: false
     *   tile: false
     *   pages: all          # all | first | except-cover
     *   font: fonts/NotoSansSC.ttf
     * </pre>
     *
     * <p>An unknown key is an error rather than a shrug: a misspelled
     * {@code opacty:} that silently stamped at the default would be found by
     * whoever printed the proof, which is late.
     *
     * @param node the {@code vars.watermark} node
     * @return parsed watermark, or null if {@code node} is null / blank /
     *         a map with neither a {@code text} nor an {@code image} field
     * @throws IllegalArgumentException if the map carries an unknown key or an
     *         unparseable value
     */
    public static Watermark fromYaml(Object node) {
        if (node == null) return null;
        if (node instanceof String s) {
            return s.isBlank() ? null : withDefaults(s);
        }
        if (node instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (!KEYS.contains(String.valueOf(k))) {
                    throw new IllegalArgumentException("watermark has unknown key '" + k
                            + "' — expected one of " + KEYS);
                }
            }
            String text = stringOr(m.get("text"), null);
            String image = stringOr(m.get("image"), null);
            if ((text == null || text.isBlank()) && (image == null || image.isBlank())) return null;
            Object sizeNode = m.containsKey("font_size") ? m.get("font_size") : m.get("fontSize");
            return new Watermark(
                    text,
                    image,
                    stringOr(m.get("font"), null),
                    stringOr(m.get("color"), DEFAULT_COLOR),
                    floatOr(m.get("opacity"), DEFAULT_OPACITY, "opacity"),
                    floatOr(m.get("angle"), DEFAULT_ANGLE, "angle"),
                    intOr(sizeNode, DEFAULT_FONT_SIZE, "font_size"),
                    boolOr(m.get("bold"), DEFAULT_BOLD, "bold"),
                    floatOr(m.get("scale"), DEFAULT_SCALE, "scale"),
                    boolOr(m.get("fit"), DEFAULT_FIT, "fit"),
                    boolOr(m.get("behind"), DEFAULT_BEHIND, "behind"),
                    boolOr(m.get("tile"), DEFAULT_TILE, "tile"),
                    m.get("pages") == null ? Pages.ALL : Pages.parse(String.valueOf(m.get("pages"))));
        }
        return null;
    }

    /**
     * The per-knob overrides a build layers over whichever base spec won —
     * {@code <watermarkColor>} and friends. Every field is nullable; a null
     * leaves the base value alone.
     *
     * <p>A record rather than a dozen positional parameters on
     * {@link #withOverrides}: at this width the call site stops being readable
     * and one transposed argument is a silent wrong stamp.
     *
     * @param color    see {@link Watermark#color()}
     * @param opacity  see {@link Watermark#opacity()}
     * @param angle    see {@link Watermark#angle()}
     * @param fontSize see {@link Watermark#fontSize()}
     * @param bold     see {@link Watermark#bold()}
     * @param scale    see {@link Watermark#scale()}
     * @param fit      see {@link Watermark#fit()}
     * @param behind   see {@link Watermark#behind()}
     * @param tile     see {@link Watermark#tile()}
     * @param pages    see {@link Watermark#pages()}
     * @param font     see {@link Watermark#font()}
     */
    public record Overrides(
            String color,
            Float opacity,
            Float angle,
            Integer fontSize,
            Boolean bold,
            Float scale,
            Boolean fit,
            Boolean behind,
            Boolean tile,
            Pages pages,
            String font) {

        /** Overrides that change nothing. */
        public static final Overrides NONE =
                new Overrides(null, null, null, null, null, null, null, null, null, null, null);

        /**
         * This bundle with {@code later}'s named knobs on top.
         *
         * <p>Two channels can carry knobs — a POM's {@code <watermark>} block
         * and the flat {@code -Dpaperband.watermark*} parameters — and both may
         * be retuning a stamp a book's yaml declared. Merging them into one
         * bundle keeps that a single, ordered application rather than a
         * sequence the callers each have to get right.
         *
         * @param later the bundle that wins where it names a knob
         * @return the merged bundle
         */
        public Overrides then(Overrides later) {
            if (later == null) return this;
            return new Overrides(
                    later.color() != null ? later.color() : color,
                    later.opacity() != null ? later.opacity() : opacity,
                    later.angle() != null ? later.angle() : angle,
                    later.fontSize() != null ? later.fontSize() : fontSize,
                    later.bold() != null ? later.bold() : bold,
                    later.scale() != null ? later.scale() : scale,
                    later.fit() != null ? later.fit() : fit,
                    later.behind() != null ? later.behind() : behind,
                    later.tile() != null ? later.tile() : tile,
                    later.pages() != null ? later.pages() : pages,
                    later.font() != null ? later.font() : font);
        }
    }

    /** @param o the knobs to layer on @return a copy with the named knobs replaced */
    public Watermark withOverrides(Overrides o) {
        if (o == null) return this;
        return new Watermark(
                this.text,
                this.image,
                o.font() != null ? o.font() : this.font,
                o.color() != null ? o.color() : this.color,
                o.opacity() != null ? o.opacity() : this.opacity,
                o.angle() != null ? o.angle() : this.angle,
                o.fontSize() != null ? o.fontSize() : this.fontSize,
                o.bold() != null ? o.bold() : this.bold,
                o.scale() != null ? o.scale() : this.scale,
                o.fit() != null ? o.fit() : this.fit,
                o.behind() != null ? o.behind() : this.behind,
                o.tile() != null ? o.tile() : this.tile,
                o.pages() != null ? o.pages() : this.pages);
    }

    /** @return true when this stamp is text rather than an image */
    public boolean hasText() {
        return text != null;
    }

    /** @return true when this stamp is an image rather than text */
    public boolean hasImage() {
        return image != null;
    }

    /**
     * The text split into stamped lines.
     *
     * <p>Both spellings of a line break work: a real newline (yaml block
     * scalars, or a double-quoted {@code "\n"}) and the literal two characters
     * {@code \n}, which is what a single-quoted yaml scalar or a
     * {@code -Dpaperband.watermark=...} command line actually delivers.
     *
     * @return one entry per line, never empty for a text watermark
     */
    public List<String> lines() {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : text.replace("\\n", "\n").split("\n", -1)) {
            out.add(line.strip());
        }
        // A trailing blank from "DRAFT\n" would stamp an empty line's worth of
        // leading; leading blanks are the author's own spacing and stay.
        while (out.size() > 1 && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** @return a short one-line description for the build log */
    public String describe() {
        String what = hasImage() ? "image " + image : "\"" + String.join(" / ", lines()) + "\"";
        StringBuilder sb = new StringBuilder(what);
        if (pages != Pages.ALL) sb.append(", pages=").append(pages.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (tile) sb.append(", tiled");
        if (behind) sb.append(", behind");
        return sb.toString();
    }

    private static String stringOr(Object v, String fallback) {
        return (v instanceof String s && !s.isBlank()) ? s : fallback;
    }

    private static float floatOr(Object v, float fallback, String key) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.floatValue();
        try {
            return Float.parseFloat(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "watermark " + key + " must be a number, got '" + v + "'");
        }
    }

    private static int intOr(Object v, int fallback, String key) {
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "watermark " + key + " must be a whole number, got '" + v + "'");
        }
    }

    private static boolean boolOr(Object v, boolean fallback, String key) {
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String t = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (t.equals("true") || t.equals("yes") || t.equals("1")) return true;
        if (t.equals("false") || t.equals("no") || t.equals("0")) return false;
        throw new IllegalArgumentException(
                "watermark " + key + " must be true or false, got '" + v + "'");
    }
}
