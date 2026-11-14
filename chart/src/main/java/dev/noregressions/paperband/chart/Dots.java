package dev.noregressions.paperband.chart;

import java.util.ArrayList;
import java.util.List;

/**
 * A dot plot: one row per item, its name on the left and a dot (or, for a {@code range}, a
 * capsule) on a shared scale, linear or logarithmic. Rows can sit in titled {@code groups}; a
 * group with {@code highlight: true} takes the accent. The scale's {@code ticks} name its points.
 */
final class Dots {

    private Dots() {
    }

    static double draw(Spec spec, Svg g) {
        boolean log = "log".equals(spec.str("scale", "linear"));
        List<Spec> groups = spec.has("groups") ? spec.list("groups") : List.of(spec);
        String format = spec.str("format", "{}");

        // What there is to lay out: the widest name, and the extent of the values.
        double names = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        List<List<Spec>> rowsOf = new ArrayList<>();
        for (Spec group : groups) {
            List<Spec> rows = group.list("rows");
            rowsOf.add(rows);
            names = Math.max(names, Svg.width(group == spec ? null : group.str("title"), 12));
            for (Spec r : rows) {
                names = Math.max(names, Math.max(Svg.width(r.required("label"), 13), Svg.width(r.str("detail"), 12)));
                for (double v : r.has("range") ? r.nums("range") : r.nums("value")) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        if (hi < lo) {
            throw new Spec.Problem("a dot chart needs 'rows', each with a 'value' or a 'range'");
        }
        double min = spec.num("min", log ? lo / 2 : Math.min(lo, 0)), max = spec.num("max", log ? hi * 1.5 : hi);
        if (log && min <= 0) {
            throw new Spec.Problem("a log scale needs values above zero");
        }
        double x0 = Math.min(names + 24, ChartRenderer.WIDTH * 0.45), x1 = ChartRenderer.WIDTH - 40;
        double a = log ? Math.log10(min) : min, span = (log ? Math.log10(max) : max) - a;

        Svg marks = new Svg();
        double y = 0;
        for (int i = 0; i < groups.size(); i++) {
            Spec group = groups.get(i);
            boolean own = group != spec;
            String fill = !own || group.flag("highlight") ? "f-accent" : "f-point";
            if (i > 0) {
                marks.line(0, y, x1, y, "s-rule");
                y += 4;
            }
            String title = own ? group.str("title") : null;
            if (title != null) {
                marks.text(0, y + 18, title, "t-soft t-small", "start");
            }
            y += title != null ? 46 : 22;
            for (Spec r : rowsOf.get(i)) {
                List<Double> values = r.has("range") ? r.nums("range") : r.nums("value");
                double first = x0 + ((log ? Math.log10(values.get(0)) : values.get(0)) - a) / span * (x1 - x0);
                double last = x0 + ((log ? Math.log10(values.get(values.size() - 1)) : values.get(values.size() - 1)) - a)
                        / span * (x1 - x0);
                String detail = r.str("detail");
                marks.pair(0, detail == null ? y + 4.5 : y - 3, r.str("label"), detail, "start");
                marks.line(x0, y, x1, y, "s-grid");
                if (values.size() > 1) {
                    marks.line(first, y, last, y, "s-range " + fill);
                } else {
                    marks.dot(first, y, fill);
                }
                String text = r.str("text");
                if (text == null) {
                    text = values.stream().map(v -> format.replace("{}", Svg.number(v))).reduce((p, q) -> p + " to " + q)
                            .orElse("");
                }
                boolean right = last + 12 + Svg.width(text, 13) <= x1 + 36;
                marks.text(right ? last + 12 : first - 12, y + 4.5, text, "t-halo", right ? "start" : "end");
                r.done();
                y += 44;
            }
            y -= 14;
            if (own) {
                group.done();
            }
        }
        double axis = y - 4;
        for (Spec tick : spec.list("ticks")) {
            Double at = tick.num("at");
            if (at == null) {
                throw new Spec.Problem("a tick needs 'at'");
            }
            double x = x0 + ((log ? Math.log10(at) : at) - a) / span * (x1 - x0);
            g.line(x, 0, x, axis, "s-grid");
            g.text(x, axis + 18, tick.str("label", Svg.number(at)), "t-soft t-small", "middle");
            tick.done();
        }
        g.line(x0, axis, x1, axis, "s-rule");
        g.add(marks);
        return axis + 26;
    }
}
