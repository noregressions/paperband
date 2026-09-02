package dev.noregressions.paperband.render;

import dev.noregressions.paperband.model.Watermark;

import java.util.List;
import java.util.Locale;

/**
 * Paints a {@link Watermark} as an HTML overlay, for the outputs that aren't a
 * finished PDF: the static site's pages and the {@code emitHtml} copy.
 *
 * <p>The PDF gets its stamp from PDFBox after rendering, which the site has no
 * equivalent of — so a book whose yaml says {@code DRAFT} used to produce a
 * stamped PDF and a clean-looking site, which is the wrong way round: the site
 * is the copy that gets linked and forwarded.
 *
 * <p>The overlay is a single fixed-position, {@code pointer-events: none}
 * element injected before {@code </body>}, carrying its own scoped CSS. Doing
 * it by injection rather than through the page templates is deliberate: a book
 * with a hand-written theme or a fully replaced {@code site-card} template gets
 * the mark too. Nothing here touches layout — the overlay is out of flow, ignores
 * the pointer, and is hidden from assistive technology.
 *
 * <p>Sizing mirrors {@code WatermarkApplier}'s fit maths in CSS units, so the
 * same declaration lands at roughly the same size on screen and on paper. It is
 * an estimate: CSS can't measure a string, so the width of a line is taken as
 * {@value #AVG_ADVANCE_EM} em per character, which is about right for the
 * upper-case Helvetica a watermark is usually set in.
 */
public final class WatermarkHtml {

    private WatermarkHtml() {}

    /** The overlay's class name; also the CSS hook a theme can restyle. */
    public static final String CLASS = "pb-watermark";

    /** Average glyph advance, in em, used to guess how wide a line will set. */
    static final double AVG_ADVANCE_EM = 0.70;

    /** Line height for a multi-line stamp, in em. Matches the PDF applier. */
    static final double LINE_HEIGHT_EM = 1.2;

    /** Fraction of the page the fitted stamp is allowed to span. */
    static final double FIT_FRACTION = 0.92;

    /** Columns and rows drawn when {@link Watermark#tile()} is on. */
    static final int TILE_COLS = 3;
    static final int TILE_ROWS = 4;

    /**
     * Build the overlay markup — a {@code <style>} block plus the element —
     * for one page.
     *
     * @param w         the watermark; must not be null
     * @param imageUrl  the URL to use for an image watermark, already resolved
     *                  relative to the page that will carry it (a {@code data:}
     *                  URI for a standalone file). Ignored for text watermarks;
     *                  may be null when the image couldn't be resolved, in which
     *                  case nothing is drawn.
     * @param screenOnly hide the overlay when the page is printed. True for the
     *                  {@code emitHtml} copy, whose print path is the renderer
     *                  plus the PDFBox stamp — without this a re-render would
     *                  carry two watermarks.
     * @return the HTML to inject, or an empty string when there is nothing to draw
     */
    public static String overlay(Watermark w, String imageUrl, boolean screenOnly) {
        if (w == null) return "";
        if (w.hasImage() && (imageUrl == null || imageUrl.isBlank())) return "";

        List<String> lines = w.lines();
        String css = css(w, screenOnly);
        StringBuilder sb = new StringBuilder(css.length() + 512);
        sb.append("\n<style>").append(css).append("</style>\n");
        sb.append("<div class=\"").append(CLASS).append(w.tile() ? " " + CLASS + "-tiled" : "")
                .append("\" aria-hidden=\"true\" role=\"presentation\">");
        if (w.tile()) {
            sb.append("<div class=\"").append(CLASS).append("-grid\">");
            for (int i = 0; i < TILE_COLS * TILE_ROWS; i++) mark(sb, w, lines, imageUrl);
            sb.append("</div>");
        } else {
            mark(sb, w, lines, imageUrl);
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private static void mark(StringBuilder sb, Watermark w, List<String> lines, String imageUrl) {
        sb.append("<div class=\"").append(CLASS).append("-mark\">");
        if (w.hasImage()) {
            sb.append("<img src=\"").append(escape(imageUrl)).append("\" alt=\"\">");
        } else {
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append("<br>");
                sb.append(escape(lines.get(i)));
            }
        }
        sb.append("</div>");
    }

    /**
     * The scoped stylesheet for one page's overlay.
     *
     * <p>Emitted per page rather than added to the theme's css chain so that a
     * book can turn the watermark on for one edition without a theme edit, and
     * so that an {@code emitHtml} file stays standalone.
     *
     * @param w          the watermark
     * @param screenOnly hide it in print
     * @return CSS text, no surrounding tag
     */
    static String css(Watermark w, boolean screenOnly) {
        StringBuilder sb = new StringBuilder(600);
        sb.append('.').append(CLASS).append("{position:fixed;top:0;left:0;right:0;bottom:0;")
                .append("display:flex;align-items:center;justify-content:center;overflow:hidden;")
                .append("pointer-events:none;-webkit-user-select:none;user-select:none;")
                // Behind the content still needs to be above the page background,
                // which a negative index on a painted <body> would lose.
                .append("z-index:").append(w.behind() ? "0" : "2147483000").append(";}");

        // A body with its own background would hide a behind-watermark; give the
        // content a stacking context above it rather than fighting the theme.
        if (w.behind()) {
            sb.append("body>*:not(.").append(CLASS).append("){position:relative;z-index:1;}");
        }

        sb.append('.').append(CLASS).append("-mark{")
                .append("color:").append(cssColor(w.color())).append(';')
                .append("opacity:").append(trim(w.opacity())).append(';')
                .append("font-family:Helvetica,Arial,sans-serif;")
                .append("font-weight:").append(w.bold() ? "700" : "400").append(';')
                .append("line-height:").append(trim((float) LINE_HEIGHT_EM)).append(';')
                .append("text-align:center;white-space:nowrap;")
                .append("-webkit-print-color-adjust:exact;print-color-adjust:exact;");
        if (!w.tile()) {
            sb.append("transform:rotate(").append(trim(w.angle())).append("deg);");
        }
        if (w.hasImage()) {
            sb.append("width:").append(trim(w.scale() * 100f)).append("%;");
        } else {
            sb.append("font-size:").append(fontSizeExpression(w)).append(';');
        }
        sb.append('}');

        if (w.hasImage()) {
            sb.append('.').append(CLASS).append("-mark img{display:block;width:100%;height:auto;")
                    .append("max-height:").append(w.tile() ? "20vh" : "80vh").append(";object-fit:contain;}");
        }

        if (w.tile()) {
            // The grid oversizes the viewport so rotating it doesn't leave the
            // corners bare.
            sb.append('.').append(CLASS).append("-grid{position:absolute;")
                    .append("top:-50%;left:-50%;width:200%;height:200%;display:grid;")
                    .append("grid-template-columns:repeat(").append(TILE_COLS).append(",1fr);")
                    .append("grid-template-rows:repeat(").append(TILE_ROWS).append(",1fr);")
                    .append("place-items:center;")
                    .append("transform:rotate(").append(trim(w.angle())).append("deg);}");
        }

        if (screenOnly) {
            sb.append("@media print{.").append(CLASS).append("{display:none!important;}}");
        }
        return sb.toString();
    }

    /**
     * The {@code font-size} value, as a CSS {@code min()} of the declared size
     * and the ceilings that keep the rotated text inside the page.
     *
     * <p>Both ceilings matter: a long line rotated to -30° is limited by the
     * page's width, a nearly vertical one by its height, and CSS min() picks
     * whichever bites.
     */
    private static String fontSizeExpression(Watermark w) {
        String declared = w.fontSize() + "pt";
        if (!w.fit()) return declared;

        List<String> lines = w.lines();
        int longest = 1;
        for (String line : lines) longest = Math.max(longest, line.length());
        double cols = w.tile() ? TILE_COLS : 1;
        double rows = w.tile() ? TILE_ROWS : 1;

        double rad = Math.toRadians(w.angle());
        double cos = Math.abs(Math.cos(rad));
        double sin = Math.abs(Math.sin(rad));
        double wEm = longest * AVG_ADVANCE_EM;
        double hEm = lines.size() * LINE_HEIGHT_EM;

        // Rotated bounding box of a w x h block, in em, then inverted: how many
        // viewport units one em may be.
        double bboxW = wEm * cos + hEm * sin;
        double bboxH = wEm * sin + hEm * cos;
        double vw = FIT_FRACTION * 100 / (bboxW * cols);
        double vh = FIT_FRACTION * 100 / (bboxH * rows);
        return "min(" + declared + "," + trim((float) vw) + "vw," + trim((float) vh) + "vh)";
    }

    /** Normalise a hex colour for CSS; anything unparseable falls back to mid-grey. */
    static String cssColor(String hex) {
        if (hex == null) return "#888888";
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (!h.matches("(?i)[0-9a-f]{3}|[0-9a-f]{6}")) return "#888888";
        return "#" + h.toLowerCase(Locale.ROOT);
    }

    /** Numbers without a trailing {@code .0}, so the CSS reads like a person wrote it. */
    static String trim(float v) {
        if (v == Math.rint(v) && !Float.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * Inject an overlay into a finished HTML document, immediately before
     * {@code </body>} so it is the last thing painted.
     *
     * @param html    the rendered page
     * @param overlay the markup from {@link #overlay}; an empty string is a no-op
     * @return the page with the overlay in it
     */
    public static String inject(String html, String overlay) {
        if (overlay == null || overlay.isEmpty() || html == null) return html;
        int at = html.lastIndexOf("</body>");
        // A template that emits no </body> is still a page a browser will render;
        // appending is the only honest place left.
        if (at < 0) return html + overlay;
        return html.substring(0, at) + overlay + html.substring(at);
    }

    /** Escape for HTML text and double-quoted attribute values. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
