package dev.noregressions.paperband.chart;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.blocks.BlockRenderer;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * {@code ```tiles} blocks: a grid of figures, each one a tile, for a dashboard or an "at a
 * glance" page. The block is YAML:
 * <pre>
 * columns: 3                     # tiles across; 3 unless said
 * tiles:
 *   - value: 278 days            # the figure, as it should be read
 *     label: Median dependency lag behind the latest major version.
 *     source: "[Datadog](https://example.com)"
 *     kind: vendor               # where the figure comes from: a small outlined marker
 *     tag: 2027 watch            # a short marker of your own, filled
 *     ref: "[Finding 8](#finding-8)"   # where it is explained, shown after the source
 *     was: 215 days              # what it is compared with
 *     trend: up                  # up, down or flat: the arrow before 'was'
 *     good: down                 # which direction is the good one, so the trend can be marked
 *     highlight: true            # the one tile the grid is about
 * </pre>
 * {@code label}, {@code source} and {@code ref} are Markdown, so they can hold links and emphasis. A tile's
 * {@code fact} is a mapping of the same keys that the tile's own keys add to or replace, which is
 * what {@code {{ fact("name").tile }}} writes in a templated block ({@code ```tiles {! template}}).
 * <p>
 * The tiles are HTML, not a drawing: they take the page's font, wrap as the page narrows, and
 * read their colours from the same {@code --pb-chart-*} variables as the charts. A group of tiles
 * isn't broken across printed pages. A key a tile doesn't have stops the build.
 */
public final class TilesRenderer implements BlockRenderer {

    static final String STYLE = "<style>"
            + ".pb-tiles{display:grid;grid-template-columns:repeat(var(--pb-tiles-columns,3),minmax(0,1fr));gap:.8em;"
            + "margin:.9em 0 1.4em;break-inside:avoid}"
            + ".pb-tile{display:flex;flex-direction:column;padding:.65em .85em .6em;border-radius:3px;"
            + "border-top:3pt solid var(--pb-chart-accent,#2b6cb0);"
            + "background:var(--pb-tiles-bg,var(--pb-chart-tint,#eef1f4));font-size:.86em;line-height:1.32;break-inside:avoid}"
            + ".pb-tile>p{margin:0}"
            + ".pb-tile>.pb-tile-value{font-size:2.1em;font-weight:300;line-height:1.05;letter-spacing:-.01em;"
            + "color:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-tile>.pb-tile-was{margin-top:.25em;font-size:.86em;color:var(--pb-chart-soft,#5d5d5d)}"
            + ".pb-tile-arrow{font-weight:700}"
            + ".pb-tile>.pb-tile-label{margin-top:.35em}"
            + ".pb-tile>.pb-tile-marks{margin-top:.45em;display:flex;flex-wrap:wrap;gap:.35em}"
            + ".pb-tile-kind,.pb-tile-tag{font-size:.78em;line-height:1.5;padding:0 .6em;border-radius:1em;white-space:nowrap;"
            + "border:.75pt solid var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-tile-kind{color:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-tile-tag{background:var(--pb-chart-accent,#2b6cb0);color:#fff}"
            // The source sits at the foot of the tile, so the feet of a row line up whatever the labels' lengths.
            + ".pb-tile>.pb-tile-source{margin-top:auto;padding-top:.5em;font-size:.82em;color:var(--pb-chart-soft,#5d5d5d)}"
            + ".pb-tile>.pb-tile-source::before{content:\"\";display:block;margin-bottom:.35em;"
            + "border-top:.75pt solid var(--pb-chart-rule,#ccc)}"
            + ".pb-tile-ref{white-space:nowrap}"
            + ".pb-tile-good .pb-tile-arrow{color:var(--pb-tiles-good,#2f7d4f)}"
            + ".pb-tile-bad .pb-tile-arrow{color:var(--pb-tiles-bad,#b3402a)}"
            + ".pb-tile-highlight{background:var(--pb-chart-accent,#2b6cb0);color:#fff}"
            + ".pb-tile-highlight>p,.pb-tile-highlight>.pb-tile-value,.pb-tile-highlight a,.pb-tile-highlight a:visited,"
            + ".pb-tile-highlight .pb-tile-arrow,.pb-tile-highlight .pb-tile-kind{color:#fff}"
            + ".pb-tile-highlight>.pb-tile-source,.pb-tile-highlight>.pb-tile-was{color:#fff}"
            + ".pb-tile-highlight .pb-tile-kind{border-color:#fff}"
            + ".pb-tile-highlight .pb-tile-tag{background:#fff;border-color:#fff;color:var(--pb-chart-accent,#2b6cb0)}"
            + ".pb-tile-highlight>.pb-tile-source::before{border-top-color:rgba(255,255,255,.45)}"
            + "@media screen and (max-width:640px){.pb-tiles{grid-template-columns:repeat(2,minmax(0,1fr))}}"
            + "</style>";

    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().escapeHtml(true).build();

    @Override
    public Set<String> languages() {
        return Set.of("tiles");
    }

    @Override
    public String render(String language, String source) throws IOException {
        try {
            Object yaml;
            try {
                yaml = FrontMatter.parseYaml(source);
            } catch (RuntimeException e) {
                throw new Spec.Problem("the tiles aren't valid YAML: " + e.getMessage());
            }
            return draw(new Spec(yaml, "the tiles"));
        } catch (Spec.Problem e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String draw(Spec spec) {
        double columns = spec.num("columns", 3);
        if (columns < 1 || columns > 6 || columns != Math.rint(columns)) {
            throw new Spec.Problem("'columns' is a whole number from 1 to 6, not " + Svg.number(columns));
        }
        List<Spec> tiles = spec.list("tiles");
        if (tiles.isEmpty()) {
            throw new Spec.Problem("the tiles need 'tiles': a list with a 'value' each");
        }
        spec.done();
        StringBuilder out = new StringBuilder("<div class=\"pb-tiles\" style=\"--pb-tiles-columns:")
                .append((int) columns).append("\">").append(STYLE);
        int n = 1;
        for (Spec tile : tiles) {
            tile(tile, tile.has("fact") ? new Spec(tile.get("fact"), "tile " + n + "'s fact") : null, n++, out);
        }
        return out.append("</div>").toString();
    }

    private static void tile(Spec own, Spec fact, int n, StringBuilder out) {
        if (fact == null) {
            own.get("fact"); // an empty 'fact:' is read, so it isn't an unknown key
        }
        String value = pick(own, fact, "value"), label = pick(own, fact, "label"), source = pick(own, fact, "source");
        String kind = pick(own, fact, "kind"), tag = pick(own, fact, "tag"), was = pick(own, fact, "was");
        String trend = pick(own, fact, "trend"), good = pick(own, fact, "good"), ref = pick(own, fact, "ref");
        boolean highlight = own.flag("highlight");
        own.done();
        if (fact != null) {
            fact.done();
        }
        String where = "tile " + n + (value == null ? "" : " (" + value + ")");
        if (value == null) {
            throw new Spec.Problem(where + " needs a 'value'");
        }
        if (trend != null && !List.of("up", "down", "flat").contains(trend)) {
            throw new Spec.Problem(where + ": 'trend' is up, down or flat, not " + trend);
        }
        if (good != null && !List.of("up", "down").contains(good)) {
            throw new Spec.Problem(where + ": 'good' is up or down, not " + good);
        }
        if (trend != null && was == null) {
            throw new Spec.Problem(where + ": a 'trend' needs a 'was' to be measured against");
        }
        String verdict = trend == null || good == null || trend.equals("flat") ? ""
                : trend.equals(good) ? " pb-tile-good" : " pb-tile-bad";
        out.append("<div class=\"pb-tile").append(highlight ? " pb-tile-highlight" : "").append(verdict).append("\">")
                .append("<p class=\"pb-tile-value\">").append(Svg.esc(value)).append("</p>");
        if (was != null) {
            String arrow = trend == null ? null : trend.equals("up") ? "↑" : trend.equals("down") ? "↓" : "→";
            out.append("<p class=\"pb-tile-was\">");
            if (arrow != null) {
                out.append("<span class=\"pb-tile-arrow\" title=\"").append(trend).append("\">").append(arrow)
                        .append("</span> from ");
            } else {
                out.append("was ");
            }
            out.append(Svg.esc(was)).append("</p>");
        }
        if (label != null) {
            out.append("<p class=\"pb-tile-label\">").append(inline(label)).append("</p>");
        }
        if (kind != null || tag != null) {
            out.append("<p class=\"pb-tile-marks\">");
            if (kind != null) {
                out.append("<span class=\"pb-tile-kind\">").append(Svg.esc(kind)).append("</span>");
            }
            if (tag != null) {
                out.append("<span class=\"pb-tile-tag\">").append(Svg.esc(tag)).append("</span>");
            }
            out.append("</p>");
        }
        if (source != null || ref != null) {
            out.append("<p class=\"pb-tile-source\">");
            if (source != null) {
                out.append(inline(source));
            }
            if (ref != null) {
                out.append(source != null ? " \u00b7 " : "").append("<span class=\"pb-tile-ref\">").append(inline(ref))
                        .append("</span>");
            }
            out.append("</p>");
        }
        out.append("</div>");
    }

    /** The tile's own key, or else its fact's. Both are read, so neither is an unknown key. */
    private static String pick(Spec own, Spec fact, String key) {
        String mine = own.str(key), theirs = fact == null ? null : fact.str(key);
        return mine != null ? mine : theirs;
    }

    /** A line of Markdown as HTML without the paragraph around it. */
    static String inline(String markdown) {
        return HTML.render(MARKDOWN.parse(markdown)).strip().replace("<p>", "").replace("</p>", "");
    }
}
