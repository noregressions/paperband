package dev.noregressions.paperband.chart;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Events on a time axis running {@code from} one moment {@code to} another. Each event has a
 * {@code label} and an optional quieter {@code text}; the labels are placed above and below the
 * axis, in as many tiers as it takes to keep them apart. {@code bands} shade a period,
 * {@code marks} put a heavier tick at a moment (today, a release). {@code panels} stacks several
 * axes, each with its own period, to show the same story at different scales.
 */
final class Timeline {

    private static final double TIER = 42;

    /** A label already placed: its extent, its side and tier, and where its leader runs (NaN for none). */
    private record Box(double left, double right, boolean above, int tier, double leader) {
    }

    private record Placed(double x, String label, String text, boolean highlight, boolean above, int tier, boolean end) {
    }

    private Timeline() {
    }

    static double draw(Spec spec, Svg g) {
        List<Spec> panels = spec.has("panels") ? spec.list("panels") : List.of(spec);
        boolean named = panels.stream().anyMatch(p -> p != spec && p.has("label"));
        double xa = named ? 60 : 20, xb = ChartRenderer.WIDTH - 20, y = 0;
        for (Spec p : panels) {
            y = panel(p, p != spec, g, xa, xb, y) + 22;
            if (p != spec) {
                p.done();
            }
        }
        return y - 22;
    }

    private static double panel(Spec p, boolean own, Svg g, double xa, double xb, double top) {
        double from = p.requiredTime("from"), to = p.requiredTime("to");
        if (to <= from) {
            throw new Spec.Problem("a timeline's 'to' must be after its 'from'");
        }
        List<Box> boxes = new ArrayList<>();
        List<double[]> marks = new ArrayList<>();
        List<String> markLabels = new ArrayList<>();
        for (Spec m : p.list("marks")) {
            double x = at(m, "at", from, to, xa, xb);
            String label = m.str("label");
            marks.add(new double[] {x});
            markLabels.add(label);
            if (label != null) {                                 // beside the tick, on whichever side has room
                double w = 6 + Svg.width(label, 12);
                boolean end = x + w > ChartRenderer.WIDTH;
                marks.get(marks.size() - 1)[0] = end ? -x : x;
                boxes.add(new Box(end ? x - w : x, end ? x : x + w, false, 0, Double.NaN));
            }
            m.done();
        }
        List<Placed> placed = new ArrayList<>();
        int tiersAbove = 0, tiersBelow = marks.isEmpty() ? 0 : 1;
        for (Spec e : p.list("events")) {
            double x = at(e, "at", from, to, xa, xb);
            String label = e.str("label"), text = e.str("text"), side = e.str("side");
            double w = Math.max(Svg.width(label, 13), Svg.width(text, 12)) + 6;
            Placed spot = null;
            search:
            for (int tier = 0; tier < 8; tier++) {
                for (boolean above : new boolean[] {true, false}) {
                    if (side != null && above != "above".equals(side)) {
                        continue;
                    }
                    for (boolean end : new boolean[] {false, true}) {
                        double left = end ? x - w : x, right = end ? x : x + w;
                        if (left >= 0 && right <= ChartRenderer.WIDTH && free(boxes, left, right, above, tier, x)) {
                            spot = new Placed(x, label, text, e.flag("highlight"), above, tier, end);
                            boxes.add(new Box(left, right, above, tier, x));
                            break search;
                        }
                    }
                }
            }
            if (spot == null) {
                throw new Spec.Problem("no room for the timeline label '" + label + "': shorten it or spread the events");
            }
            placed.add(spot);
            e.done();
            if (spot.above) {
                tiersAbove = Math.max(tiersAbove, spot.tier + 1);
            } else {
                tiersBelow = Math.max(tiersBelow, spot.tier + 1);
            }
        }

        double y = top + (tiersAbove > 0 ? tiersAbove * TIER + 8 : 12);
        if (own && p.has("label")) {
            g.text(0, y + 4, p.str("label"), "t-soft t-small", "start");
        }
        g.line(xa, y, xb, y, "s-track");
        for (Spec band : p.list("bands")) {
            double b0 = at(band, "from", from, to, xa, xb), b1 = at(band, "to", from, to, xa, xb);
            g.rect(b0, y - 5, b1 - b0, 10, "f-band");
            band.done();
        }
        double below = y + (tiersBelow > 0 ? 6 + tiersBelow * TIER : 10);
        boolean ticks = false;
        for (Tick tick : ticks(p, from, to)) {
            double x = xa + (tick.time - from) / (to - from) * (xb - xa);
            g.line(x, y - 5, x, y + 5, "s-point");
            g.text(x, below + 16, tick.label, "t-soft t-small", "middle");
            ticks = true;
        }
        for (int i = 0; i < marks.size(); i++) {
            boolean end = marks.get(i)[0] < 0;
            double x = Math.abs(marks.get(i)[0]);
            g.line(x, y - 11, x, y + 11, "s-ink");
            if (markLabels.get(i) != null) {
                g.text(end ? x - 6 : x + 6, y + 26, markLabels.get(i), "t-soft t-small", end ? "end" : "start");
            }
        }
        for (Placed e : placed) {
            double block = e.above ? y - 14 - e.tier * TIER : y + 14 + e.tier * TIER;     // the edge nearest the axis
            g.line(e.x, y, e.x, e.above ? block - 30 : block + 30, "s-rule");
            double tx = e.end ? e.x - 6 : e.x + 6;
            String anchor = e.end ? "end" : "start";
            if (e.above) {
                if (e.text != null) {
                    g.text(tx, block - 2, e.text, "t-soft t-small", anchor);
                }
                if (e.label != null) {
                    g.text(tx, block - (e.text != null ? 18 : 2), e.label, "", anchor);
                }
            } else {
                if (e.label != null) {
                    g.text(tx, block + 12, e.label, "", anchor);
                }
                if (e.text != null) {
                    g.text(tx, block + (e.label != null ? 28 : 12), e.text, "t-soft t-small", anchor);
                }
            }
        }
        for (Placed e : placed) {
            g.dot(e.x, y, e.highlight ? "f-accent" : "f-point");
        }
        return below + (ticks ? 22 : 0);
    }

    /** Whether a label can go at this side and tier without touching another label or crossing a leader. */
    private static boolean free(List<Box> boxes, double left, double right, boolean above, int tier, double leader) {
        for (Box b : boxes) {
            if (b.above != above) {
                continue;
            }
            if (b.tier == tier && left < b.right + 12 && right > b.left - 12) {
                return false;
            }
            if (b.tier < tier && leader >= b.left - 4 && leader <= b.right + 4) {
                return false;
            }
            if (b.tier > tier && b.leader >= left - 4 && b.leader <= right + 4) {
                return false;
            }
        }
        return true;
    }

    private static double at(Spec s, String key, double from, double to, double xa, double xb) {
        double t = s.requiredTime(key);
        if (t < from || t > to) {
            throw new Spec.Problem("'" + key + ": " + s.str(key) + "' is outside the timeline's from and to");
        }
        return xa + (t - from) / (to - from) * (xb - xa);
    }

    private record Tick(double time, String label) {
    }

    /** The axis ticks: a list of {at, label}, or the word years or months. */
    private static List<Tick> ticks(Spec p, double from, double to) {
        List<Tick> out = new ArrayList<>();
        if (!p.has("ticks")) {
            return out;
        }
        if (p.get("ticks") instanceof String unit) {
            if (!unit.equals("years") && !unit.equals("months")) {
                throw new Spec.Problem("'ticks' is years, months or a list of {at, label}");
            }
            LocalDate d = LocalDate.ofEpochDay((long) Math.floor(from / 86400)).withDayOfMonth(1);
            for (d = unit.equals("years") ? d.withDayOfYear(1) : d; d.toEpochSecond(java.time.LocalTime.MIDNIGHT, ZoneOffset.UTC) <= to;
                 d = unit.equals("years") ? d.plusYears(1) : d.plusMonths(1)) {
                double t = d.toEpochSecond(java.time.LocalTime.MIDNIGHT, ZoneOffset.UTC);
                if (t >= from) {
                    out.add(new Tick(t, unit.equals("years") ? String.valueOf(d.getYear())
                            : d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)));
                }
            }
            return out;
        }
        for (Spec tick : p.list("ticks")) {
            double t = tick.requiredTime("at");
            out.add(new Tick(t, tick.str("label", "")));
            tick.done();
        }
        return out;
    }
}
