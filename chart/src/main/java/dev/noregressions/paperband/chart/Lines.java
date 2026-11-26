package dev.noregressions.paperband.chart;

import java.util.ArrayList;
import java.util.List;

/**
 * One or more lines over the same steps, for how a figure moves: each row is a step along the
 * bottom with a {@code value}, or {@code values} for several {@code series}. One line takes the
 * accent ({@code highlight} names it; else the first), the others are quiet. {@code lines} are
 * horizontal reference lines, {@code markers} vertical ones between two steps. {@code labels}
 * says which points show their value: {@code all}, {@code ends} or {@code none}.
 */
final class Lines {

    private record Row(String label, List<Double> values, String text) {
    }

    private Lines() {
    }

    static double draw(Spec spec, Svg g) {
        double full = spec.num("height", 170);
        String format = spec.str("format", "{}");
        List<String> series = spec.strs("series"), highlight = spec.strs("highlight");
        int lines = Math.max(series.size(), 1);
        for (String h : highlight) {
            if (!series.contains(h)) {
                throw new Spec.Problem("highlight '" + h + "': no series has that name");
            }
        }

        List<Row> rows = new ArrayList<>();
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (Spec r : spec.list("rows")) {
            String label = r.required("label");
            if (r.has("value") == r.has("values")) {
                throw new Spec.Problem("step '" + label + "' needs a 'value', or 'values' for several series");
            }
            List<Double> values = r.has("value") ? r.nums("value") : r.nums("values");
            if (values.size() != lines) {
                throw new Spec.Problem("step '" + label + "' has " + values.size() + " value(s) for " + lines
                        + " series");
            }
            rows.add(new Row(label, values, r.str("text")));
            r.done();
            for (double v : values) {
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        if (rows.size() < 2) {
            throw new Spec.Problem("a line chart needs at least two 'rows'");
        }
        List<Spec> refs = spec.list("lines");
        for (Spec l : refs) {
            high = Math.max(high, l.num("value", 0));
        }
        double min = spec.num("min", Math.min(low, 0)), max = spec.num("max", high);
        if (max <= min) {
            max = min + 1;
        }
        String labels = spec.str("labels", lines == 1 && rows.size() <= 12 ? "all" : "ends");
        if (!List.of("all", "ends", "none").contains(labels)) {
            throw new Spec.Problem("'labels' is all, ends or none, not " + labels);
        }

        boolean legend = lines > 1, markers = spec.has("markers");
        double width = ChartRenderer.WIDTH, step = width / rows.size();
        double top = (legend ? 24 : 0) + (markers ? 20 : 0) + 22, base = top + full;
        if (legend) {
            double x = 0;
            for (int s = 0; s < lines; s++) {
                x = g.swatch(x, 12, accent(series, highlight, s) ? "f-accent" : "f-point", series.get(s));
            }
        }
        g.line(0, base, width, base, "s-rule");
        for (Spec l : refs) {
            double y = y(l.num("value", 0), min, max, base, full);
            g.line(0, y, width, y, "s-rule");
            if (l.has("label")) {
                g.text(width, y - 6, l.str("label"), "t-soft t-small", "end");
            }
            l.done();
        }
        for (Spec m : spec.list("markers")) {
            String after = m.required("after");
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).label.equals(after)) {
                    at = i;
                }
            }
            if (at < 0) {
                throw new Spec.Problem("marker after '" + after + "': no step has that label");
            }
            double x = step * (at + 1);
            g.line(x, top - 36, x, base, "s-point");
            String label = m.str("label");
            if (label != null) {
                boolean fits = x + 8 + Svg.width(label, 12) <= width;
                g.text(fits ? x + 8 : x - 8, top - 24, label, "t-soft t-small", fits ? "start" : "end");
            }
            m.done();
        }
        for (int i = 0; i < rows.size(); i++) {
            g.text(step * (i + 0.5), base + 18, rows.get(i).label, "t-soft t-small", "middle");
        }
        // The quiet lines first, so the accented one is drawn over them.
        for (int pass = 0; pass < 2; pass++) {
            for (int s = 0; s < lines; s++) {
                boolean accent = accent(series, highlight, s);
                if (accent != (pass == 1)) {
                    continue;
                }
                StringBuilder d = new StringBuilder();
                for (int i = 0; i < rows.size(); i++) {
                    d.append(i == 0 ? "M" : " L").append(Svg.n(step * (i + 0.5))).append(',')
                            .append(Svg.n(y(rows.get(i).values.get(s), min, max, base, full)));
                }
                g.path(d.toString(), "s-line " + (accent ? "f-accent" : "f-point"));
                for (int i = 0; i < rows.size(); i++) {
                    Row r = rows.get(i);
                    double x = step * (i + 0.5), py = y(r.values.get(s), min, max, base, full);
                    g.dot(x, py, accent ? "f-accent" : "f-point");
                    boolean end = i == 0 || i == rows.size() - 1;
                    if (labels.equals("all") || (labels.equals("ends") && end)) {
                        g.text(x, py - 12, ChartRenderer.label(lines == 1 ? r.text : null, format, r.values.get(s), max),
                                "t-halo", "middle");
                    }
                }
            }
        }
        return base + 26;
    }

    private static boolean accent(List<String> series, List<String> highlight, int s) {
        return highlight.isEmpty() ? s == 0 : highlight.contains(series.get(s));
    }

    private static double y(double value, double min, double max, double base, double full) {
        return base - (value - min) / (max - min) * full;
    }
}
