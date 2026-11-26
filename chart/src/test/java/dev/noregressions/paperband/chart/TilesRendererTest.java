package dev.noregressions.paperband.chart;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.HtmlIds;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** tiles fences become a grid of figures in HTML; a tile that can't be drawn stops the build. */
class TilesRendererTest {

    private static String tiles(String yaml) throws IOException {
        return new TilesRenderer().render("tiles", yaml);
    }

    private static String problem(String yaml) {
        return assertThrows(IOException.class, () -> tiles(yaml)).getMessage();
    }

    @Test
    void aTilesFenceBecomesAGridOfTiles() throws IOException {
        var file = new CombinedAst().add(Path.of("a.md"), "```tiles\ncolumns: 4\ntiles:\n  - { value: 12 minutes }\n  - { value: 23% }\n```\n");
        List<org.commonmark.node.Node> blocks = new ArrayList<>();
        for (var n = file.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        String out = MarkdownHtmlRenderer.renderNodes(blocks, new HtmlIds("a-md", new HashSet<>()));
        assertTrue(out.contains("<div class=\"block block-tiles\"><div class=\"pb-tiles\" style=\"--pb-tiles-columns:4\">"), out);
        assertEquals(2, out.split("<div class=\"pb-tile\">", -1).length - 1);
        assertTrue(out.contains("<p class=\"pb-tile-value\">12 minutes</p>") && out.contains("<p class=\"pb-tile-value\">23%</p>"));
    }

    @Test
    void aTileShowsItsLabelSourceKindRefAndTagWithMarkdownLinks() throws IOException {
        String out = tiles("""
                tiles:
                  - value: $9
                    label: Cost per known CVE, *rediscovered* by a cheap model.
                    source: Aikido
                    kind: vendor benchmark
                    ref: "[Finding 1](#finding-1)"
                    tag: 2027 watch
                """);
        assertTrue(out.contains("style=\"--pb-tiles-columns:3\""), "three across unless said");
        assertTrue(out.contains("<p class=\"pb-tile-label\">Cost per known CVE, <em>rediscovered</em> by a cheap model.</p>"), out);
        assertTrue(out.contains("<p class=\"pb-tile-marks\"><span class=\"pb-tile-kind\">vendor benchmark</span>"
                + "<span class=\"pb-tile-tag\">2027 watch</span></p>"), out);
        assertTrue(out.contains("<p class=\"pb-tile-source\">Aikido · <span class=\"pb-tile-ref\">"
                + "<a href=\"#finding-1\">Finding 1</a></span></p>"), out);
    }

    @Test
    void aTrendIsMarkedGoodOrBadAgainstTheGoodDirection() throws IOException {
        String out = tiles("""
                tiles:
                  - { value: 278 days, was: 215 days, trend: up, good: down }
                  - { value: 12%, was: 9%, trend: up, good: up, highlight: true }
                  - { value: 13%, was: 14% }
                """);
        assertTrue(out.contains("<div class=\"pb-tile pb-tile-bad\">"), out);
        assertTrue(out.contains("<div class=\"pb-tile pb-tile-highlight pb-tile-good\">"), out);
        assertTrue(out.contains("<span class=\"pb-tile-arrow\" title=\"up\">↑</span> from 215 days</p>"), out);
        assertTrue(out.contains("<p class=\"pb-tile-was\">was 14%</p>"), "no trend, no arrow");
    }

    @Test
    void aTilesOwnKeysAddToAndReplaceItsFacts() throws IOException {
        String out = tiles("""
                tiles:
                  - fact: {value: "278 days", label: "Median lag", source: "[Datadog](https://example.com)", kind: "vendor", was: "215 days", trend: "up", good: "down"}
                    label: Median dependency lag.
                    tag: watch
                """);
        assertTrue(out.contains("<p class=\"pb-tile-value\">278 days</p>"), "the value is the fact's");
        assertTrue(out.contains("<p class=\"pb-tile-label\">Median dependency lag.</p>"), "the tile's own label replaces the fact's");
        assertTrue(out.contains("<span class=\"pb-tile-kind\">vendor</span><span class=\"pb-tile-tag\">watch</span>"), out);
        assertTrue(out.contains("<p class=\"pb-tile-source\"><a href=\"https://example.com\">Datadog</a></p>"), out);
        assertTrue(out.contains("pb-tile-bad"), "the fact's trend and good direction are used");
    }

    @Test
    void aTilesLinkToAPageOrAnAnchorGoesWhereAMarkdownLinkWould(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        java.nio.file.Files.createDirectories(dir.resolve("docs"));
        java.nio.file.Files.writeString(dir.resolve("docs/a.md"), """
                # Numbers

                ```tiles
                tiles:
                  - { value: 23%, ref: "[Finding 4](#finding-4)", source: "[below](#later)" }
                ```

                ## Later
                """);
        java.nio.file.Files.writeString(dir.resolve("docs/b.md"), "---\nid: finding-4\n---\n# The window has closed\n");
        java.nio.file.Files.writeString(dir.resolve("manifest.yml"), "sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\n");
        var build = dev.noregressions.paperband.SiteBuilder.build(
                dev.noregressions.paperband.layout.Manifest.load(dir.resolve("manifest.yml")));
        String html = build.outputs().getFirst().html();
        assertTrue(html.contains("<a href=\"#docs-b-md\">Finding 4</a>"), "a page's front matter id: " + html);
        assertTrue(html.contains("<a href=\"#docs-a-md--later\">below</a>"), "a heading in the same file: " + html);
        assertTrue(build.report().warnings().isEmpty(), String.valueOf(build.report().warnings()));
    }

    @Test
    void textIsEscaped() throws IOException {
        String out = tiles("tiles:\n  - { value: \"<5%\", label: \"a <b>bold</b> claim\" }\n");
        assertTrue(out.contains("&lt;5%") && out.contains("&lt;b&gt;bold&lt;/b&gt;"), out);
    }

    @Test
    void tilesThatCantBeDrawnStopTheBuildWithTheReason() {
        assertTrue(problem("columns: 3").contains("need 'tiles'"));
        assertTrue(problem("tiles: [{ label: no figure }]").contains("tile 1 needs a 'value'"));
        assertTrue(problem("tiles: [{ value: 1, colour: red }]").contains("unknown key 'colour'"));
        assertTrue(problem("tiles: [{ value: 1, fact: { value: 2, colour: red } }]").contains("tile 1's fact: unknown key 'colour'"));
        assertTrue(problem("colour: red\ntiles: [{ value: 1 }]").contains("unknown key 'colour'"));
        assertTrue(problem("columns: 9\ntiles: [{ value: 1 }]").contains("whole number from 1 to 6"));
        assertTrue(problem("tiles: [{ value: 1, was: 2, trend: sideways }]").contains("'trend' is up, down or flat"));
        assertTrue(problem("tiles: [{ value: 1, trend: up }]").contains("needs a 'was'"));
        assertTrue(problem("tiles: [{ value: 1, was: 2, trend: up, good: yes }]").contains("'good' is up or down"));
        assertTrue(problem("tiles: [{ value: 1").contains("aren't valid YAML"));
    }
}
