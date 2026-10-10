package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.SiteBuilder;
import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.fit.FitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fit remedies, measured in Chromium. */
class RemediesTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private BuildConfig config(String markdown, String... layouts) throws IOException {
        write("docs/a.md", markdown);
        for (int i = 0; i < layouts.length; i += 2) {
            write("layouts/" + layouts[i] + ".yml", layouts[i + 1]);
        }
        write("manifest.yml", """
                sources: [docs/a.md]
                sequence: [{ file: docs/a.md, layout: l }]
                outputs: [{ name: print, format: paged-html, file: out/a.html, page: { size: A5, margin: 10mm } }]
                """);
        BuildConfig.Builder b = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (ChromiumBrowserTest.BROWSER != null) {
            b.browser(ChromiumBrowserTest.BROWSER);
        }
        return b.build();
    }

    private SiteBuilder.Output build(String markdown, String... layouts) throws IOException {
        BuildConfig c = config(markdown, layouts);
        return SiteBuilder.build(c.manifest(), c.clock()).outputs().getFirst();
    }

    private static String text(int sentences) {
        return "A sentence that takes up some room in the box. ".repeat(sentences);
    }

    /** A box at a fixed place and size, with these remedies. */
    private static String boxLayout(String height, String fit) {
        return "boxes:\n  - { id: body, select: rest, at: { x: 10mm, y: 10mm, w: 128mm, h: " + height + " }, fit: "
                + fit + " }\n";
    }

    @Test
    void shrinkFindsTheLargestTextThatFits() throws IOException {
        SiteBuilder.Output out = build(text(12) + "\n\nShort. {! optional}\n", "l",
                boxLayout("36mm", "[{ shrink: { min: 60% } }, { drop: optional }]"));
        assertEquals(List.of(), out.problems());
        assertEquals(1, out.remedies().size(), out.remedies().toString());
        String r = out.remedies().getFirst();
        assertTrue(r.startsWith("page docs-a-md (docs/a.md), box body: text at "), r);
        double percent = Double.parseDouble(r.replaceAll(".*text at ([\\d.]+)%.*", "$1"));
        assertTrue(percent >= 60 && percent < 100, r);
        assertTrue(r.matches(".*text at [\\d.]+%"), "shrinking was enough, so nothing else was done: " + r);
        assertTrue(out.html().contains("Short."));
        assertTrue(out.html().contains("<style id=\"paperband-fit\">"), "the remedy is in the output");
        assertTrue(out.html().contains("section[id=\"docs-a-md\"] [data-box=\"body\"] > * { zoom: 0."), out.html());
    }

    @Test
    void remediesAddUpInOrder() throws IOException {
        SiteBuilder.Output out = build(text(14) + "\n\n" + text(4) + "\n", "l",
                boxLayout("34mm", "[{ shrink: { min: 95% } }, { tighten: true }, { truncate: true }]"));
        assertEquals(List.of(), out.problems());
        String r = out.remedies().getFirst();
        assertTrue(r.contains("text at 95%, tightened, cut after "), r);
    }

    @Test
    void shorterVariantsAndOptionalBlocks() throws IOException {
        String md = text(2) + "\n\n:::: {! variants}\n::: {! variant=full}\n" + text(8) + "\n:::\n"
                + "::: {! variant=short}\nShort version.\n:::\n::::\n";
        SiteBuilder.Output variant = build(md, "l", boxLayout("40mm", "[{ variant: next }]"));
        assertEquals(List.of(), variant.problems(), variant.remedies().toString());
        assertTrue(variant.html().contains("Short version.") && !variant.html().contains(text(8).strip()));
        assertEquals("page docs-a-md (docs/a.md), box body: variant short", variant.remedies().getFirst());

        SiteBuilder.Output dropped = build(text(10) + "\n\n" + "Least important. {! optional priority=3}\n\n"
                + "More important. {! optional priority=1}\n\nNot important at all. {! optional}\n", "l",
                boxLayout("62mm", "[{ drop: optional }]"));
        assertEquals(List.of(), dropped.problems(), dropped.remedies().toString());
        String html = dropped.html();
        assertFalse(html.contains("Not important at all."), "no priority goes first");
        assertTrue(html.contains("More important."), "priority 1 is kept longest: " + dropped.remedies());
    }

    @Test
    void pageRemediesDropBoxesSwitchGridsAndLayouts() throws IOException {
        String grid = """
                grid:
                  areas: ["title", "body", "notes"]
                boxes:
                  - { id: title, area: title, select: h1 }
                  - { id: body, area: body, select: "section.h1 > p" }
                  - id: notes
                    area: notes
                    select: blockquote
                    priority: optional
                    size: { min: 20mm }
                    drop: { below-min: true }
                fit: [ FIT ]
                """;
        String md = "# Title\n\n" + text(28) + "\n\n> " + text(30) + "\n";
        SiteBuilder.Output dropped = build(md, "l", grid.replace("FIT", "{ drop: boxes }"));
        assertEquals(List.of(), dropped.problems());
        assertEquals("page docs-a-md (docs/a.md): dropped box notes", dropped.remedies().getFirst());
        assertFalse(dropped.html().contains("data-box=\"notes\""));

        // The layout's own grid gives the title a tall row; its compact grid doesn't.
        String grids = """
                grid: { rows: [100mm, auto, auto], areas: ["title", "body", "notes"] }
                grids:
                  compact: { areas: ["title", "body", "notes"] }
                boxes:
                  - { id: title, area: title, select: h1 }
                  - { id: body, area: body, select: "section.h1 > p" }
                  - { id: notes, area: notes, select: blockquote }
                fit: [{ grid: compact }]
                """;
        SiteBuilder.Output compact = build(md.replace(text(30), text(6)), "l", grids);
        assertEquals(List.of(), compact.problems(), compact.remedies().toString());
        assertEquals("page docs-a-md (docs/a.md): grid compact", compact.remedies().getFirst());
        assertTrue(compact.html().contains("class=\"page layout-l grid grid-compact\""));
        assertTrue(compact.html().contains(".layout-l.grid.grid-compact { "), "the alternative grid's CSS");

        SiteBuilder.Output fallback = build(md, "l", grid.replace("FIT", "{ layout: plain }"),
                "plain", "boxes: [{ id: all, select: rest }]\n");
        assertEquals(List.of(), fallback.problems());
        assertEquals("page docs-a-md (docs/a.md): layout plain", fallback.remedies().getFirst());
        assertTrue(fallback.html().contains("class=\"page layout-plain\""), "a flowing layout takes as many sheets as it needs");
    }

    @Test
    void whenNothingWorksTheBuildSaysWhatWasTried() throws IOException {
        BuildConfig c = config(text(60) + "\n", "l", boxLayout("20mm", "[{ shrink: { min: 90% } }, { tighten: true }]"));
        FitException e = assertThrows(FitException.class, () -> Runner.run(c));
        assertTrue(e.getMessage().contains("box body: content is "), e.getMessage());
        assertTrue(e.getMessage().contains("\n    tried: box body: shrink (min 90%); box body: tighten (line-height 1.2, gap 1mm)"),
                e.getMessage());
    }
}
