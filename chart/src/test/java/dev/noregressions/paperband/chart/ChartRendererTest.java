package dev.noregressions.paperband.chart;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** chart fences become inline SVG, one test per chart type; a chart that can't be drawn stops the build. */
class ChartRendererTest {

    private static String html(String markdown) {
        org.commonmark.node.Node f;
        try {
            f = new CombinedAst().add(Path.of("a.md"), markdown);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        List<org.commonmark.node.Node> blocks = new ArrayList<>();
        for (var n = f.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        return MarkdownHtmlRenderer.renderNodes(blocks, new dev.noregressions.paperband.HtmlIds("a-md", new java.util.HashSet<>()));
    }

    private static String chart(String yaml) throws IOException {
        return new ChartRenderer().render("chart", yaml);
    }

    private static String problem(String yaml) {
        return assertThrows(IOException.class, () -> chart(yaml)).getMessage();
    }

    @Test
    void aChartFenceBecomesInlineSvgAndOtherFencesStayCode() {
        String out = html("```chart\ntype: bar\ntitle: Downloads\nrows:\n  - { label: a, value: 3 }\n```\n\n```yaml\ntype: bar\n```\n");
        assertTrue(out.contains("<div class=\"block block-chart\"><svg"), out.substring(0, Math.min(300, out.length())));
        assertTrue(out.contains("aria-label=\"Downloads\""), "the title names the drawing");
        assertTrue(out.contains("<pre><code class=\"language-yaml\">type: bar\n</code></pre>"), out);
    }

    @Test
    void barsShowEachValueAndSingleOutTheHighlightedRow() throws IOException {
        String out = chart("""
                type: bar
                format: "{}%"
                highlight: old
                rows:
                  - { label: old, value: 99.88, note: fixed long ago }
                  - { label: new, value: 5087, aside: the rest }
                """);
        assertTrue(out.contains(">99.88%<") && out.contains(">5,087%<"), "values are written as people write them");
        assertTrue(out.contains(">fixed long ago<") && out.contains(">the rest<"), "notes and asides are drawn");
        assertEquals(1, count(out, "class=\"f-accent\""), "only the highlighted row takes the accent");
        assertEquals(1, count(out, "class=\"f-neutral\""));
    }

    @Test
    void barsWithSeveralSeriesGetALegendAndTheirOwnMaximum() throws IOException {
        String out = chart("""
                type: bar
                series: [Ours, Theirs]
                format: "{} of {max}"
                rows:
                  - { label: Benchmark, values: [0, 20], max: 41 }
                """);
        assertTrue(out.contains(">Ours<") && out.contains(">Theirs<"), "the legend names each series");
        assertTrue(out.contains(">0 of 41<") && out.contains(">20 of 41<"), out);
    }

    @Test
    void columnsTakeReferenceLinesMarkersAndPanels() throws IOException {
        String out = chart("""
                type: column
                lines: [{ value: 26, label: all of them }]
                markers: [{ after: a, label: policy change }]
                rows:
                  - { label: a, value: 18, note: cheap }
                  - { label: b, value: 23 }
                """);
        assertTrue(out.contains(">all of them<") && out.contains(">policy change<") && out.contains(">cheap<"), out);
        String panels = chart("""
                type: column
                panels:
                  - { title: Left, rows: [{ label: x, value: 1 }] }
                  - { title: Right, tone: neutral, rows: [{ label: x, value: 900, text: 0.9k }] }
                """);
        assertTrue(panels.contains(">Left<") && panels.contains(">Right<") && panels.contains(">0.9k<"), panels);
        assertEquals(1, count(panels, "class=\"f-neutral\""), "a panel's tone colours its columns");
    }

    @Test
    void dotsSitOnALogScaleWithRangesAsCapsules() throws IOException {
        String out = chart("""
                type: dot
                scale: log
                min: 0.1
                max: 10000
                ticks: [{ at: 24, label: 1 day }]
                groups:
                  - { title: Fast, highlight: true, rows: [{ label: exploit, value: 0.2, text: 12 minutes }] }
                  - { title: Slow, rows: [{ label: fix, detail: two sources, range: [6048, 6672] }] }
                """);
        assertTrue(out.contains(">12 minutes<") && out.contains(">1 day<") && out.contains(">two sources<"), out);
        assertTrue(out.contains(">6,048 to 6,672<"), "a range without its own text shows both ends");
        assertTrue(out.contains("s-range f-point"), "a range is one capsule");
        assertTrue(problem("type: dot\nscale: log\nrows: [{ label: a, value: 0 }]").contains("above zero"));
    }

    @Test
    void timelineLabelsAreKeptApart() throws IOException {
        String out = chart("""
                type: timeline
                from: 2024-09-01
                to: 2028-02-01
                ticks: years
                marks: [{ at: 2026-11-08, label: Today }]
                events:
                  - { at: 2024-10-17, label: Oct 2024, text: a deadline with a long description }
                  - { at: 2024-12-10, label: Dec 2024, text: an act in force with a long description }
                  - { at: 2027-12-11, label: Dec 2027, text: full obligations, highlight: true }
                """);
        assertTrue(out.contains(">2025<") && out.contains(">2028<") && out.contains(">Today<"), out);
        assertTrue(out.contains(">Dec 2027</text>") && out.contains("text-anchor=\"end\""), "a label near the right edge turns left");
        assertEquals(1, count(out, "class=\"f-accent\""));
        assertTrue(problem("type: timeline\nfrom: 2026-01-01\nto: 2026-02-01\nevents: [{ at: 2027-01-01, label: late }]")
                .contains("outside"));
    }

    @Test
    void timelinePanelsTakeTimesOfDay() throws IOException {
        String out = chart("""
                type: timeline
                panels:
                  - { label: Days, from: 2026-08-11, to: 2026-10-22, bands: [{ from: 2026-10-20, to: 2026-10-22 }],
                      events: [{ at: 2026-10-17, label: first attempt }] }
                  - { label: Hours, from: 2026-10-20, to: "2026-10-21 16:00", events: [{ at: "2026-10-21 07:41", label: early images }] }
                """);
        assertTrue(out.contains(">Days<") && out.contains(">Hours<") && out.contains(">early images<"), out);
        assertTrue(out.contains("class=\"f-band\""));
    }

    @Test
    void unitsFillPartOfASquare() throws IOException {
        String out = chart("""
                type: units
                legend: [found, missed]
                groups:
                  - { title: Java, rows: [{ label: tool, value: 38.2 }] }
                """);
        assertTrue(out.contains(">38.2%<") && out.contains(">found<") && out.contains(">Java<"), out);
        assertEquals(3 + 1 + 1, count(out, "class=\"f-accent\""), "three whole squares, part of a fourth and the legend");
        assertEquals(1, count(out, "<path"), "and part of a fourth");
    }

    @Test
    void funnelsNarrowAndSplit() throws IOException {
        String out = chart("""
                type: funnel
                filters: [exposure]
                stages:
                  - { share: 100, label: Raw queue }
                  - { share: 30, label: Exposed, detail: internet-facing }
                split:
                  - { share: 25, label: Maintained }
                  - { share: 5, label: End-of-life, highlight: true }
                """);
        assertTrue(out.contains(">100%<") && out.contains(">exposure<") && out.contains(">internet-facing<"), out);
        assertTrue(out.contains(">End-of-life<") && out.contains(">5%<"), out);
    }

    @Test
    void titleSubtitleAndNoteWrapToTheWidth() throws IOException {
        String longTitle = "A headline long enough that it cannot possibly sit on a single line of a chart that is only so wide, whatever the font";
        String out = chart("type: bar\ntitle: " + longTitle + "\nsubtitle: what is measured\nnote: a caveat\nrows: [{ label: a, value: 1 }]");
        assertEquals(2, count(out, "class=\"t-title\""), "the headline takes two lines");
        assertTrue(out.contains(">what is measured<") && out.contains(">a caveat<"));
    }

    @Test
    void aChartThatCantBeDrawnStopsTheBuildWithTheReason() {
        assertTrue(problem("type: pie").contains("unknown chart type 'pie'"));
        assertTrue(problem("rows: []").contains("needs 'type'"));
        assertTrue(problem("type: bar\ncolour: red\nrows: [{ label: a, value: 1 }]").contains("unknown key 'colour'"));
        assertTrue(problem("type: bar\nrows: [{ label: a, value: 1, colour: red }]").contains("unknown key 'colour'"));
        assertTrue(problem("type: bar\nrows: [{ label: a, value: lots }]").contains("must hold numbers"));
        assertTrue(problem("type: bar").contains("needs 'rows'"));
        assertTrue(problem("type: column\nmarkers: [{ after: z }]\nrows: [{ label: a, value: 1 }]").contains("no column has that label"));
        assertTrue(problem("type: bar\nrows: [{ label: a, value: 1").contains("isn't valid YAML"));
        Exception e = assertThrows(RuntimeException.class, () -> html("```chart\ntype: pie\n```\n"));
        assertTrue(String.valueOf(e.getMessage()).contains("chart block"), e.getMessage());
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
            n++;
        }
        return n;
    }
}
