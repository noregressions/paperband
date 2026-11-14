package dev.noregressions.paperband.chart;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical columns standing on a baseline, each with its value above and its name beneath.
 * {@code lines} are horizontal reference lines, {@code markers} vertical ones between two
 * columns. {@code panels} puts several sets of columns side by side, each on its own scale.
 */
final class Columns {

    private record Row(String label, double value, String text, String note, boolean highlight) {
    }

    private Columns() {
    }

    static double draw(Spec spec, Svg g) {
        double full = spec.num("height", 190);
        List<Spec> panels = spec.has("panels") ? spec.list("panels") : List.of(spec);
        if (spec.has("panels") && spec.has("rows")) {
            throw new Spec.Problem("a column chart has 'rows' or 'panels', not both");
        }
        boolean titles = false, markers = false, notes = false;
        for (Spec p : panels) {
            titles |= p != spec && p.has("title");
            markers |= p.has("markers");
            for (Spec r : p.list("rows")) {
                notes |= r.has("note");
            }
        }
        double gap = 40, width = (ChartRenderer.WIDTH - gap * (panels.size() - 1)) / panels.size();
        double top = (titles ? 26 : 0) + 20 + (markers ? 20 : 0), base = top + full;
        for (int i = 0; i < panels.size(); i++) {
            Spec p = panels.get(i);
            panel(p, p != spec, g, i * (width + gap), width, top, base, notes);
            if (p != spec) {
                p.done();
            }
        }
        return base + (notes ? 42 : 26);
    }

    private static void panel(Spec p, boolean own, Svg g, double left, double width, double top, double base,
                              boolean notes) {
        String format = p.str("format", "{}"), tone = p.str("tone", "accent");
        List<String> highlight = p.strs("highlight");
        List<Row> rows = new ArrayList<>();
        double max = 0;
        for (Spec r : p.list("rows")) {
            String label = r.required("label");
            Double value = r.num("value");
            if (value == null) {
                throw new Spec.Problem("column '" + label + "' needs a 'value'");
            }
            rows.add(new Row(label, value, r.str("text"), r.str("note"),
                    ChartRenderer.named(highlight, label, r.flag("highlight"))));
            r.done();
            max = Math.max(max, value);
        }
        if (rows.isEmpty()) {
            throw new Spec.Problem("a column chart needs 'rows'");
        }
        List<Spec> lines = p.list("lines");
        for (Spec l : lines) {
            max = Math.max(max, l.num("value", 0));
        }
        max = p.num("max", max);
        double full = base - top, step = width / rows.size();
        boolean anyHighlight = rows.stream().anyMatch(Row::highlight);

        if (own && p.has("title")) {
            g.text(left, top - 30 - (p.has("markers") ? 20 : 0), p.str("title"));
        }
        for (Spec l : lines) {
            double y = base - l.num("value", 0) / max * full;
            g.line(left, y, left + width, y, "s-rule");
            if (l.has("label")) {
                g.text(left + width, y - 6, l.str("label"), "t-soft t-small", "end");
            }
            l.done();
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double cx = left + step * (i + 0.5), h = Math.max(r.value / max * full, 1.5);
            boolean accent = anyHighlight ? r.highlight : "accent".equals(tone);
            g.column(cx - 12, base, 24, h, accent ? "f-accent" : "f-neutral");
            g.text(cx, base - h - 7, ChartRenderer.label(r.text, format, r.value, max), "", "middle");
            g.text(cx, base + 18, r.label, notes ? "" : "t-soft t-small", "middle");
            if (r.note != null) {
                g.text(cx, base + 34, r.note, "t-soft t-small", "middle");
            }
        }
        for (Spec m : p.list("markers")) {
            String after = m.required("after");
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).label.equals(after)) {
                    at = i;
                }
            }
            if (at < 0) {
                throw new Spec.Problem("marker after '" + after + "': no column has that label");
            }
            double x = left + step * (at + 1);
            g.line(x, top - 36, x, base, "s-point");
            String label = m.str("label");
            if (label != null) {
                boolean fits = x + 8 + Svg.width(label, 12) <= left + width;
                g.text(fits ? x + 8 : x - 8, top - 24, label, "t-soft t-small", fits ? "start" : "end");
            }
            m.done();
        }
        g.line(left, base, left + width, base, "s-rule");
    }
}
