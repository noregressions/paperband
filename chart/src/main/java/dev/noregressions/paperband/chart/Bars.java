package dev.noregressions.paperband.chart;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal bars, one row per item: the name on the left, the bar, then its value. A row with
 * several {@code values} draws one bar per series, with a legend. Rows may carry a {@code note}
 * beneath the bar, an {@code aside} after the value and their own {@code max}.
 */
final class Bars {

    private record Row(String label, List<Double> values, List<String> texts, String note, String aside, double max,
                       boolean highlight) {
    }

    private Bars() {
    }

    static double draw(Spec spec, Svg g) {
        String format = spec.str("format", "{}");
        List<String> series = spec.strs("series"), highlight = spec.strs("highlight");
        Double chartMax = spec.num("max");
        List<Row> rows = new ArrayList<>();
        double top = 0;
        int n = 1;
        for (Spec r : spec.list("rows")) {
            List<Double> values = r.has("values") ? r.nums("values") : r.nums("value");
            if (values.isEmpty()) {
                throw new Spec.Problem("a bar chart row needs 'value' or 'values'");
            }
            List<String> texts = r.has("texts") ? r.strs("texts") : r.strs("text");
            String label = r.required("label");
            rows.add(new Row(label, values, texts, r.str("note"), r.str("aside"), r.num("max", 0),
                    ChartRenderer.named(highlight, label, r.flag("highlight"))));
            r.done();
            n = Math.max(n, values.size());
            for (double v : values) {
                top = Math.max(top, v);
            }
        }
        if (rows.isEmpty()) {
            throw new Spec.Problem("a bar chart needs 'rows'");
        }
        double max = chartMax != null ? chartMax : top;
        boolean anyHighlight = rows.stream().anyMatch(Row::highlight);

        // Columns of the layout: names, bars, values, asides.
        double names = 0, texts = 0, asides = 0;
        List<List<String>> shown = new ArrayList<>();
        for (Row r : rows) {
            names = Math.max(names, Svg.width(r.label, 13));
            asides = Math.max(asides, Svg.width(r.aside, 12));
            List<String> t = new ArrayList<>();
            for (int k = 0; k < r.values.size(); k++) {
                t.add(ChartRenderer.label(k < r.texts.size() ? r.texts.get(k) : null, format, r.values.get(k),
                        r.max > 0 ? r.max : max));
                texts = Math.max(texts, Svg.width(t.get(k), 13));
            }
            shown.add(t);
        }
        double x0 = Math.min(names * 1.08 + 14, ChartRenderer.WIDTH * 0.45);   // room for a wider font than assumed
        double span = ChartRenderer.WIDTH - x0 - 8 - texts - (asides > 0 ? asides + 14 : 0) - 2;

        double y = 0;
        if (n > 1 && !series.isEmpty()) {
            double x = x0;
            for (int k = 0; k < series.size(); k++) {
                x = g.swatch(x, 10, fill(series, highlight, k), series.get(k));
            }
            y = 26;
        }
        double first = y;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double height = r.values.size() * 26 - 4;
            g.text(x0 - 10, y + height / 2 + 4.5, r.label, "", "end");
            for (int k = 0; k < r.values.size(); k++) {
                double w = Math.max(r.values.get(k) / (r.max > 0 ? r.max : max) * span, 1.5);
                String fill = n > 1 ? fill(series, highlight, k) : !anyHighlight || r.highlight ? "f-accent" : "f-neutral";
                g.bar(x0, y + k * 26, w, 22, fill);
                String t = shown.get(i).get(k);
                g.text(x0 + w + 8, y + k * 26 + 16, t);
                if (r.aside != null && k == 0) {
                    g.text(x0 + w + 8 + Svg.width(t, 13) + 12, y + 16, r.aside, "t-soft t-small", "start");
                }
            }
            if (r.note != null) {
                g.text(x0 + 6, y + height + 16, r.note, "t-soft t-small", "start");
            }
            y += height + (r.note != null ? 22 : 0) + 18;
        }
        y -= 18;
        g.line(x0, first - 6, x0, y + 6, "s-rule");
        return y + 8;
    }

    /** In a chart with several series, the named one (or the first) takes the accent. */
    private static String fill(List<String> series, List<String> highlight, int k) {
        boolean named = series.stream().anyMatch(highlight::contains);
        return (named ? k < series.size() && highlight.contains(series.get(k)) : k == 0) ? "f-accent" : "f-neutral";
    }
}
