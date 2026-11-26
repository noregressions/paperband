package dev.noregressions.paperband.chart;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.blocks.BlockRenderer;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * {@code ```chart} blocks as inline SVG. The block is YAML: a {@code type} (bar, column, line,
 * dot, timeline, units or funnel), an optional {@code title}, {@code subtitle} and {@code note}, and
 * the type's own data. The drawing is done here, in Java, so it needs no browser; the SVG
 * inherits the page's font and reads its colours from {@code --pb-chart-*} variables, so a theme
 * styles every chart at once. A chart that can't be drawn stops the build with the reason.
 */
public final class ChartRenderer implements BlockRenderer {

    /** The drawing's width in its own units; the SVG scales to the width of the text. */
    static final double WIDTH = 680;

    /** The classes the marks use. A theme sets the variables; the fallbacks are a plain blue and grey. */
    static final String STYLE = "<style>"
            + ".pb-chart{display:block;width:100%;height:auto;font-family:inherit}"
            + ".pb-chart text{font-size:13px;fill:var(--pb-chart-ink,currentColor)}"
            + ".pb-chart .t-title{font-size:17px}.pb-chart .t-big{font-size:14px}.pb-chart .t-small{font-size:12px}"
            + ".pb-chart .t-soft{fill:var(--pb-chart-soft,#5d5d5d)}"
            + ".pb-chart .t-halo{paint-order:stroke;stroke:var(--pb-chart-bg,#fff);stroke-width:5px;stroke-linejoin:round}"
            + ".pb-chart .f-accent{fill:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-chart .f-neutral{fill:var(--pb-chart-neutral,#c3ccd6)}"
            + ".pb-chart .f-point{fill:var(--pb-chart-point,#8a9099)}"
            + ".pb-chart .f-tint{fill:var(--pb-chart-tint,#eef1f4)}"
            + ".pb-chart .f-band{fill:var(--pb-chart-accent,#2b6cb0);opacity:.25}"
            + ".pb-chart line{stroke-width:1px}"
            + ".pb-chart .s-rule{stroke:var(--pb-chart-rule,#ccc)}"
            + ".pb-chart .s-grid{stroke:var(--pb-chart-grid,#e6e9ec)}"
            + ".pb-chart .s-point{stroke:var(--pb-chart-point,#8a9099)}"
            + ".pb-chart .s-track{stroke:var(--pb-chart-neutral,#c3ccd6);stroke-width:2px}"
            + ".pb-chart .s-ink{stroke:var(--pb-chart-ink,currentColor);stroke-width:2px}"
            + ".pb-chart .s-range{stroke-width:12px;stroke-linecap:round}"
            + ".pb-chart .s-line{fill:none;stroke-width:2.5px;stroke-linejoin:round;stroke-linecap:round}"
            + ".pb-chart .s-line.f-accent{fill:none;stroke:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-chart .s-line.f-point{fill:none;stroke:var(--pb-chart-point,#8a9099)}"
            + ".pb-chart .s-range.f-accent{stroke:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-chart .s-range.f-point{stroke:var(--pb-chart-point,#8a9099)}"
            + ".pb-chart circle{stroke:var(--pb-chart-bg,#fff);stroke-width:2px}"
            + "</style>";

    @Override
    public Set<String> languages() {
        return Set.of("chart");
    }

    @Override
    public String render(String language, String source) throws IOException {
        try {
            Object yaml;
            try {
                yaml = FrontMatter.parseYaml(source);
            } catch (RuntimeException e) {
                throw new Spec.Problem("the chart isn't valid YAML: " + e.getMessage());
            }
            return draw(new Spec(yaml, "the chart"));
        } catch (Spec.Problem e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String draw(Spec spec) {
        String type = spec.required("type");
        String title = spec.str("title"), subtitle = spec.str("subtitle"), note = spec.str("note");
        String name = spec.str("name", title == null ? type + " chart" : title);
        Svg body = new Svg();
        double height = switch (type) {
            case "bar" -> Bars.draw(spec, body);
            case "column" -> Columns.draw(spec, body);
            case "line" -> Lines.draw(spec, body);
            case "dot" -> Dots.draw(spec, body);
            case "timeline" -> Timeline.draw(spec, body);
            case "units" -> Units.draw(spec, body);
            case "funnel" -> Funnel.draw(spec, body);
            default -> throw new Spec.Problem("unknown chart type '" + type
                    + "': use bar, column, line, dot, timeline, units or funnel");
        };
        spec.done();

        Svg head = new Svg();
        double y = 0;
        if (title != null) {
            for (String line : Svg.wrap(title, 17, WIDTH)) {
                head.text(0, y += 18 + (y > 0 ? 4 : 0), line, "t-title", "start");
            }
        }
        if (subtitle != null) {
            for (String line : Svg.wrap(subtitle, 12, WIDTH)) {
                head.text(0, y += y > 0 ? 18 : 14, line, "t-soft t-small", "start");
            }
        }
        double top = y > 0 ? y + 22 : 2;
        Svg foot = new Svg();
        double bottom = top + height;
        if (note != null) {
            bottom += 4;
            for (String line : Svg.wrap(note, 12, WIDTH)) {
                foot.text(0, bottom += 16, line, "t-soft t-small", "start");
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"pb-chart pb-chart-" + type + "\" viewBox=\"0 0 "
                + Svg.n(WIDTH) + " " + Svg.n(bottom + 6) + "\" role=\"img\" aria-label=\"" + Svg.esc(name) + "\">"
                + STYLE + head + "<g transform=\"translate(0," + Svg.n(top) + ")\">" + body + "</g>" + foot + "</svg>";
    }

    /** The value text for a mark: the row's own, or the chart's format with {@code {}} and {@code {max}} filled in. */
    static String label(String own, String format, double value, double max) {
        return own != null ? own : format.replace("{}", Svg.number(value)).replace("{max}", Svg.number(max));
    }

    /** Whether a row is one the chart singles out: by its own {@code highlight: true} or by name in the chart's list. */
    static boolean named(List<String> highlight, String label, boolean own) {
        return own || highlight.contains(label);
    }
}
