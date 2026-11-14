package dev.noregressions.paperband.chart;

import java.util.List;

/**
 * A queue narrowing through {@code stages}: each a block whose height is its {@code share} of
 * the first, joined by tapers that the {@code filters} name. The last stage can {@code split}
 * into two outcomes, drawn above and below it.
 */
final class Funnel {

    private static final double MID = 122, FULL = 200, W = 44;

    private Funnel() {
    }

    static double draw(Spec spec, Svg g) {
        List<Spec> stages = spec.list("stages"), split = spec.list("split");
        List<String> filters = spec.strs("filters");
        String format = spec.str("format", "{}%");
        if (stages.size() < 2) {
            throw new Spec.Problem("a funnel needs at least two 'stages'");
        }
        if (!split.isEmpty() && split.size() != 2) {
            throw new Spec.Problem("a funnel's 'split' has two outcomes");
        }
        int columns = stages.size() + (split.isEmpty() ? 0 : 1);
        double first = 60, step = (ChartRenderer.WIDTH - 120 - first) / (columns - 1);
        double[] h = new double[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            h[i] = height(stages.get(i));
        }
        Svg blocks = new Svg();
        for (int i = 0; i < stages.size(); i++) {
            Spec s = stages.get(i);
            double x = first + i * step;
            if (i > 0) {
                taper(g, x - step + W, h[i - 1], MID, x, h[i], MID);
                if (i - 1 < filters.size()) {
                    blocks.text((x - step + W + x) / 2, MID + 4, filters.get(i - 1), "t-soft t-small t-halo", "middle");
                }
            }
            blocks.rect(x, MID - h[i] / 2, W, h[i], "f-neutral");
            blocks.text(x + W / 2, MID + FULL / 2 + 26, share(s, format), "t-big", "middle");
            blocks.pair(x + W / 2, MID + FULL / 2 + 44, s.str("label"), s.str("detail"), "middle");
            s.done();
        }
        if (!split.isEmpty()) {
            double x = first + stages.size() * step, from = x - step + W, last = h[stages.size() - 1];
            for (int k = 0; k < 2; k++) {
                Spec s = split.get(k);
                double hk = height(s), centre = MID + (k == 0 ? -46 : 46);
                double source = k == 0 ? MID - last / 2 + hk / 2 : MID + last / 2 - hk / 2;
                taper(g, from, hk, source, x, hk, centre);
                blocks.rect(x, centre - hk / 2, W, hk, s.flag("highlight") ? "f-accent" : "f-neutral");
                double y = k == 0 ? centre - 50 : centre + 24;
                blocks.text(x + W / 2, y, share(s, format), "t-big", "middle");
                blocks.pair(x + W / 2, y + 18, s.str("label"), s.str("detail"), "middle");
                s.done();
            }
        }
        g.add(blocks);
        return MID + FULL / 2 + 66;
    }

    private static double height(Spec s) {
        Double share = s.num("share");
        if (share == null) {
            throw new Spec.Problem("a funnel stage needs a 'share'");
        }
        return Math.max(share / 100 * FULL, 3);
    }

    private static String share(Spec s, String format) {
        return ChartRenderer.label(s.str("text"), format, s.num("share", 0), 100);
    }

    private static void taper(Svg g, double xa, double ha, double ya, double xb, double hb, double yb) {
        g.path("M" + Svg.n(xa) + "," + Svg.n(ya - ha / 2) + " L" + Svg.n(xb) + "," + Svg.n(yb - hb / 2) + " V"
                + Svg.n(yb + hb / 2) + " L" + Svg.n(xa) + "," + Svg.n(ya + ha / 2) + " Z", "f-tint");
    }
}
