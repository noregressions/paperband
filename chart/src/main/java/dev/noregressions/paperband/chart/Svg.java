package dev.noregressions.paperband.chart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The marks a chart is made of, written as SVG. Colours and text sizes are classes, styled by
 * {@link ChartRenderer#STYLE} from the page's theme, so nothing here names a colour or a font.
 */
final class Svg {

    private final StringBuilder out = new StringBuilder();

    @Override
    public String toString() {
        return out.toString();
    }

    Svg add(Svg other) {
        out.append(other.out);
        return this;
    }

    Svg raw(String svg) {
        out.append(svg);
        return this;
    }

    /** Text at a baseline; {@code classes} are text classes ({@code t-soft t-small}), {@code anchor} start, middle or end. */
    Svg text(double x, double y, String s, String classes, String anchor) {
        out.append("<text x=\"").append(n(x)).append("\" y=\"").append(n(y)).append('"');
        if (!classes.isEmpty()) {
            out.append(" class=\"").append(classes).append('"');
        }
        if (!"start".equals(anchor)) {
            out.append(" text-anchor=\"").append(anchor).append('"');
        }
        out.append('>').append(esc(s)).append("</text>");
        return this;
    }

    Svg text(double x, double y, String s) {
        return text(x, y, s, "", "start");
    }

    /** A line of text in ink with a quieter, smaller line beneath it (either may be null). */
    Svg pair(double x, double y, String top, String bottom, String anchor) {
        if (top != null) {
            text(x, y, top, "", anchor);
        }
        if (bottom != null) {
            text(x, y + 16, bottom, "t-soft t-small", anchor);
        }
        return this;
    }

    Svg line(double x1, double y1, double x2, double y2, String stroke) {
        out.append("<line x1=\"").append(n(x1)).append("\" y1=\"").append(n(y1)).append("\" x2=\"").append(n(x2))
                .append("\" y2=\"").append(n(y2)).append("\" class=\"").append(stroke).append("\"/>");
        return this;
    }

    Svg rect(double x, double y, double w, double h, String fill) {
        out.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y)).append("\" width=\"").append(n(w))
                .append("\" height=\"").append(n(h)).append("\" class=\"").append(fill).append("\"/>");
        return this;
    }

    /** A bar growing from the left: square at its origin, rounded at the value end. */
    Svg bar(double x, double y, double w, double h, String fill) {
        double r = Math.min(4, Math.min(w, h / 2)), end = x + w;
        return path("M" + n(x) + "," + n(y) + " H" + n(end - r) + " Q" + n(end) + "," + n(y) + " " + n(end) + ","
                + n(y + r) + " V" + n(y + h - r) + " Q" + n(end) + "," + n(y + h) + " " + n(end - r) + "," + n(y + h)
                + " H" + n(x) + " Z", fill);
    }

    /** A column standing on the baseline: square at the foot, rounded at the top. */
    Svg column(double x, double base, double w, double h, String fill) {
        double r = Math.min(4, Math.min(h, w / 2)), top = base - h;
        return path("M" + n(x) + "," + n(base) + " V" + n(top + r) + " Q" + n(x) + "," + n(top) + " " + n(x + r) + ","
                + n(top) + " H" + n(x + w - r) + " Q" + n(x + w) + "," + n(top) + " " + n(x + w) + "," + n(top + r)
                + " V" + n(base) + " Z", fill);
    }

    Svg path(String d, String fill) {
        out.append("<path d=\"").append(d).append("\" class=\"").append(fill).append("\"/>");
        return this;
    }

    Svg dot(double x, double y, String fill) {
        out.append("<circle cx=\"").append(n(x)).append("\" cy=\"").append(n(y)).append("\" r=\"6\" class=\"")
                .append(fill).append("\"/>");
        return this;
    }

    /** A legend entry: a small square and its name. Returns the x after it. */
    double swatch(double x, double y, String fill, String label) {
        rect(x, y - 10, 11, 11, fill);
        text(x + 17, y, label, "t-soft t-small", "start");
        return x + 17 + width(label, 12) + 22;
    }

    /** A coordinate: one decimal place, none where it isn't needed. */
    static String n(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /** A value as people write it: 5,087 and 99.88. */
    static String number(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v)
                : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * About how wide a text is, in the same units as its size. There is no font to measure with
     * (the page's theme chooses it), so this assumes an ordinary sans-serif and errs a little wide.
     */
    static double width(String s, double size) {
        if (s == null) {
            return 0;
        }
        double em = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            em += "iljtfrI.,:;'|!()[] ".indexOf(c) >= 0 ? 0.29 : "mwMW@%".indexOf(c) >= 0 ? 0.8
                    : Character.isUpperCase(c) ? 0.6 : 0.49;
        }
        return em * size;
    }

    /** Breaks a text into lines no wider than {@code max}. */
    static List<String> wrap(String s, double size, double max) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.strip().split("\\s+")) {
            if (line.length() > 0 && width(line + " " + word, size) > max) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        lines.add(line.toString());
        return lines;
    }
}
