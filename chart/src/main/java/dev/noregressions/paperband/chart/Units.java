package dev.noregressions.paperband.chart;

import java.util.List;

/**
 * Rows of ten squares, each row a share out of 100: 57.3 fills five squares and a third of the
 * sixth. Rows sit in {@code groups}, each with a title on the left. {@code legend} names the
 * filled and the empty squares.
 */
final class Units {

    private Units() {
    }

    static double draw(Spec spec, Svg g) {
        String format = spec.str("format", "{}%");
        List<String> legend = spec.strs("legend");
        List<Spec> groups = spec.has("groups") ? spec.list("groups") : List.of(spec);
        double titles = 0, names = 0;
        for (Spec group : groups) {
            titles = Math.max(titles, Svg.width(group == spec ? null : group.str("title"), 14));
            for (Spec r : group.list("rows")) {
                names = Math.max(names, Svg.width(r.required("label"), 12));
            }
        }
        double size = 26, gap = 4, x0 = (titles > 0 ? titles + 24 : 0) + names * 1.08 + 14;
        double y = 0;
        if (!legend.isEmpty()) {
            double x = g.swatch(x0, 10, "f-accent", legend.get(0));
            if (legend.size() > 1) {
                g.swatch(x, 10, "f-tint", legend.get(1));
            }
            y = 26;
        }
        for (Spec group : groups) {
            if (group != spec && group.has("title")) {
                g.text(0, y + 18, group.str("title"), "t-big", "start");
            }
            List<Spec> rows = group.list("rows");
            if (rows.isEmpty()) {
                throw new Spec.Problem("a units chart needs 'rows'");
            }
            for (Spec r : rows) {
                Double value = r.num("value");
                if (value == null) {
                    throw new Spec.Problem("row '" + r.str("label") + "' needs a 'value'");
                }
                g.text(x0 - 12, y + 18, r.str("label"), "t-soft t-small", "end");
                for (int i = 0; i < 10; i++) {
                    double x = x0 + i * (size + gap), filled = Math.min(Math.max(value / 10 - i, 0), 1);
                    g.raw("<rect x=\"" + Svg.n(x) + "\" y=\"" + Svg.n(y) + "\" width=\"26\" height=\"26\" rx=\"4\" class=\"f-tint\"/>");
                    if (filled >= 1) {
                        g.raw("<rect x=\"" + Svg.n(x) + "\" y=\"" + Svg.n(y) + "\" width=\"26\" height=\"26\" rx=\"4\" class=\"f-accent\"/>");
                    } else if (filled > 0) {                     // part of a square: rounded on the left only
                        double w = Math.max(size * filled, 4), b = y + size;
                        g.path("M" + Svg.n(x + 4) + "," + Svg.n(y) + " H" + Svg.n(x + w) + " V" + Svg.n(b) + " H"
                                + Svg.n(x + 4) + " Q" + Svg.n(x) + "," + Svg.n(b) + " " + Svg.n(x) + "," + Svg.n(b - 4)
                                + " V" + Svg.n(y + 4) + " Q" + Svg.n(x) + "," + Svg.n(y) + " " + Svg.n(x + 4) + ","
                                + Svg.n(y) + " Z", "f-accent");
                    }
                }
                g.text(x0 + 10 * (size + gap) + 8, y + 18, ChartRenderer.label(r.str("text"), format, value, 100));
                r.done();
                y += 36;
            }
            y += 18;
            if (group != spec) {
                group.done();
            }
        }
        return y - 26;
    }
}
