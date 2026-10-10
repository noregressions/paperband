package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.SiteBuilder;
import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.fit.FitException;
import dev.noregressions.paperband.fit.Measurement;
import dev.noregressions.paperband.fit.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Measuring paged outputs in Chromium, and failing builds that don't fit. */
class FitTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private BuildConfig config(String layout, String markdown) throws IOException {
        write("docs/a.md", markdown);
        write("layouts/l.yml", layout);
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

    private SiteBuilder.Output build(String layout, String markdown) throws IOException {
        BuildConfig c = config(layout, markdown);
        return SiteBuilder.build(c.manifest(), c.clock()).outputs().getFirst();
    }

    private static final String LONG = "# Title\n\n" + "A sentence that takes up some room on the page. ".repeat(60) + "\n";

    @Test
    void measuresEveryBoxInMillimetres() throws IOException {
        SiteBuilder.Output out = build("""
                boxes:
                  - { id: title, select: h1, at: { x: 15mm, y: 20mm, w: 100mm, h: 30mm } }
                  - { id: body, select: rest, at: { x: 15mm, y: 60mm, w: 118mm, h: 120mm } }
                """, "# Title\n\nShort.\n");
        assertEquals(List.of(), out.problems());
        Measurement.Sheet sheet = out.measurement().pages().getFirst();
        assertEquals(148, sheet.width(), 0.1);
        assertEquals(210, sheet.height(), 0.1);
        assertArrayEquals(new double[]{10, 10, 10, 10}, sheet.margin(), 0.1);
        Measurement.Box title = sheet.boxes().getFirst();
        assertEquals("title", title.path());
        assertEquals(15, title.x(), 0.1);
        assertEquals(20, title.y(), 0.1);
        assertEquals(100, title.w(), 0.1);
        assertEquals(30, title.h(), 0.1);
    }

    @Test
    void findsContentTallerOrWiderThanItsBox() throws IOException {
        SiteBuilder.Output out = build("""
                boxes:
                  - { id: title, select: h1, at: { x: 10mm, y: 10mm, w: 128mm, h: 15mm } }
                  - { id: body, select: "section.h1 > p", at: { x: 10mm, y: 30mm, w: 128mm, h: 40mm } }
                  - { id: code, select: pre, at: { x: 10mm, y: 150mm, w: 40mm, h: 40mm } }
                """, LONG + "\n```\n" + "x".repeat(200) + "\n```\n");
        Problem title = out.problems().getFirst();
        assertEquals("title", title.box(), "an h1 and its margins don't fit in 15mm either");
        Problem taller = out.problems().stream().filter(p -> "body".equals(p.box())).findFirst().orElseThrow();
        assertEquals(Problem.Kind.TALLER, taller.kind());
        assertEquals("body", taller.box());
        assertTrue(taller.amount() > 20, taller.message());
        assertTrue(taller.message().startsWith("page docs-a-md (docs/a.md), box body: content is "), taller.message());
        Problem wider = out.problems().stream().filter(p -> p.kind() == Problem.Kind.WIDER).findFirst().orElseThrow();
        assertEquals("code", wider.box());
        assertTrue(wider.amount() > 100, wider.message());
    }

    @Test
    void findsPagesThatOverflowAndBoxesInTheMargin() throws IOException {
        SiteBuilder.Output out = build("""
                stack: {}
                boxes:
                  - { id: title, select: h1 }
                  - { id: body, select: rest }
                """, LONG + LONG.replace("# Title", ""));
        assertEquals(1, out.problems().size(), out.problems().toString());
        Problem body = out.problems().getFirst();
        assertEquals("body", body.box());
        assertEquals(Problem.Kind.OFF_PAGE, body.kind(), "it runs past the bottom of the sheet");

        SiteBuilder.Output margin = build("stack: {}\nboxes:\n  - { id: title, select: h1 }\n"
                + "  - { id: body, select: rest, size: { min: 175mm } }\n", "# Title\n\nShort.\n");
        assertEquals(List.of(Problem.Kind.IN_MARGIN), margin.problems().stream().map(Problem::kind).toList(),
                margin.problems().toString());

        SiteBuilder.Output flowing = build("boxes: [{ id: body, select: rest }]\n", LONG + LONG.replace("# Title", ""));
        assertEquals(List.of(), flowing.problems(), "a page without a fixed shape flows onto more sheets");
    }

    @Test
    void findsImagesThatDidNotLoad() throws IOException {
        SiteBuilder.Output out = build("boxes: [{ id: body, select: rest }]\n", "# A\n\n![missing](nowhere.png)\n");
        assertEquals(List.of(new Problem("docs-a-md", Path.of("docs/a.md"), "body", Problem.Kind.IMAGE, 0, "nowhere.png")),
                out.problems());
    }

    @Test
    void aBuildThatDoesNotFitWritesTheFitReportInstead() throws IOException {
        BuildConfig c = config("""
                boxes:
                  - { id: body, select: rest, at: { x: 10mm, y: 10mm, w: 128mm, h: 20mm } }
                """, LONG);
        FitException e = assertThrows(FitException.class, () -> Runner.run(c));
        Path report = dir.resolve("out/a.fit.html");
        assertTrue(e.getMessage().startsWith("output print doesn't fit (1 problem(s); see " + report + "):\n"
                + "  page docs-a-md (docs/a.md), box body: content is "), e.getMessage());
        assertFalse(Files.exists(dir.resolve("out/a.html")), "nothing is written");
        String html = Files.readString(report);
        assertTrue(html.contains("<aside class=\"fit-summary\">"), html);
        assertTrue(html.contains("class=\"box box-body fit-problem\""), "the box is outlined");
        assertTrue(html.contains("data-fit=\"body: content is "), "and labelled");
        assertTrue(Files.isRegularFile(dir.resolve("out/paperband-fonts/inter-latin-400-normal.woff2")));

        // Once it fits, the output is written and the old report goes.
        write("layouts/l.yml", "boxes: [{ id: body, select: rest }]\n");
        Runner.run(c);
        assertTrue(Files.exists(dir.resolve("out/a.html")));
        assertFalse(Files.exists(report));
    }
}
