package dev.noregressions.paperband.fit;

import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.layout.Layout;
import dev.noregressions.paperband.layout.Layouts;
import dev.noregressions.paperband.layout.Page;
import dev.noregressions.paperband.layout.PageSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.noregressions.paperband.fit.Problem.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The checks on a measurement, without a browser: measurements are written out by hand. */
class FitCheckTest {

    @TempDir
    Path dir;

    private static final double[] MARGIN = {10, 10, 10, 10};

    private Page page(String layoutYaml) throws IOException {
        Files.writeString(dir.resolve("l.yml"), layoutYaml);
        Layout layout = Layouts.load(dir, PageSpec.A4).get("l");
        List<Page.PlacedBox> boxes = layout.boxes().stream().map(b -> new Page.PlacedBox(b, List.of())).toList();
        return new Page("p", layout, Path.of("docs/a.md"), PageSpec.A4, boxes);
    }

    private static Measurement sheet(double overflowY, Measurement.Box... boxes) {
        return new Measurement(List.of(new Measurement.Sheet("p", 210, 297, MARGIN, 0, overflowY, List.of(boxes))),
                List.of(), List.of());
    }

    private static Measurement.Box box(String path, double x, double y, double w, double h, double ox, double oy) {
        return new Measurement.Box(path, x, y, w, h, ox, oy);
    }

    private static List<Problem.Kind> kinds(List<Problem> problems) {
        return problems.stream().map(Problem::kind).toList();
    }

    @Test
    void boxesThatFitHaveNoProblems() throws IOException {
        Page p = page("stack: {}\nboxes: [{ id: a, select: rest }]\n");
        assertEquals(List.of(), FitCheck.check(List.of(p), sheet(0, box("a", 10, 10, 190, 100, 0.2, 0.29))),
                "under the tolerance is rounding");
    }

    @Test
    void overflowOffPageAndMargins() throws IOException {
        Page p = page("boxes: [{ id: a, select: h1, at: { x: 10mm, y: 10mm, w: 190mm, h: 20mm } },"
                + " { id: b, select: rest, size: { min: 10mm } }, { id: c, text: x, at: { x: 0mm, y: 280mm, w: 50mm, h: 17mm } }]\n");
        List<Problem> problems = FitCheck.check(List.of(p), sheet(30,
                box("a", 10, 10, 190, 20, 4, 6),
                box("b", 10, 35, 190, 255, 0, 0),
                box("c", 0, 280, 50, 17, 0, 0)));
        assertEquals(List.of(TALLER, WIDER, IN_MARGIN), kinds(problems), problems.toString());
        assertEquals("page p (docs/a.md), box a: content is 6.0mm taller than the box", problems.get(0).message());
        assertEquals("page p (docs/a.md), box a: content is 4.0mm wider than the box", problems.get(1).message());
        assertEquals("page p (docs/a.md), box b: the box reaches 3.0mm into the page margin", problems.get(2).message());
        // c sits in the margin on purpose (it's placed with at), and the page overflow is explained by its boxes.

        List<Problem> off = FitCheck.check(List.of(p), sheet(0, box("b", 10, 35, 190, 270, 0, 0)));
        assertEquals("page p (docs/a.md), box b: the box reaches 8.0mm past the edge of the page", off.getFirst().message());
    }

    @Test
    void thePageIsReportedWhenNoBoxExplainsIt() throws IOException {
        Page fixed = page("stack: {}\nboxes: [{ id: a, select: rest }]\n");
        assertEquals(List.of("page p (docs/a.md): content is 12.0mm taller than the page"),
                FitCheck.check(List.of(fixed), sheet(12)).stream().map(Problem::message).toList());
        Page flowing = page("boxes: [{ id: a, select: rest }]\n");
        assertEquals(List.of(), FitCheck.check(List.of(flowing), sheet(12, box("a", 10, 10, 190, 400, 0, 0))),
                "a page without a fixed shape flows onto more sheets");
    }

    @Test
    void imagesAndFonts() throws IOException {
        Page p = page("boxes: [{ id: a, select: rest }]\n");
        Measurement m = new Measurement(List.of(), List.of(new Measurement.BrokenImage("p", "a", "img/x.png")),
                List.of("Inter 700 italic"));
        assertEquals(List.of("page p (docs/a.md), box a: image \"img/x.png\" didn't load", "font Inter 700 italic didn't load"),
                FitCheck.check(List.of(p), m).stream().map(Problem::message).toList());
    }

    @Test
    void theFitPageOutlinesEachProblem() {
        String html = "<html><head></head><body><section class=\"page\" id=\"p\"><div class=\"box box-a\" data-box=\"a\">"
                + "<div class=\"box box-b\" data-box=\"a/b\">x</div></div></section></body></html>";
        List<Problem> problems = List.of(
                new Problem("p", Path.of("docs/a.md"), "a/b", TALLER, 5, null),
                new Problem("p", Path.of("docs/a.md"), "a/b", WIDER, 2, null),
                new Problem("p", Path.of("docs/a.md"), null, PAGE_TALLER, 1, null));
        String page = FitPage.render(html, "print", problems);
        assertTrue(page.contains("<h1>Output print: 3 problem(s); nothing was written</h1>"), page);
        assertTrue(page.contains("data-box=\"a/b\" id=\"fit-0\" class=\"box box-b fit-problem\"")
                || page.contains("class=\"box box-b fit-problem\""), page);
        assertTrue(page.contains("data-fit=\"a/b: content is 5.0mm taller than the box\na/b: content is 2.0mm wider than the box\""), page);
        assertTrue(page.contains("<a href=\"#fit-0\">page p (docs/a.md), box a/b: content is 5.0mm taller than the box</a>"), page);
        assertTrue(page.contains("<a href=\"#p\">page p (docs/a.md): content is 1.0mm taller than the page</a>"), page);
        assertFalse(page.contains("box box-a fit-problem"), "only the box with the problem");
    }

    @Test
    void fitReportsGoBesideTheirOutput() {
        assertEquals(Path.of("target/handbook.fit.html"), Runner.fitReport(Path.of("target/handbook.pdf")));
        assertEquals(Path.of("out/notes.fit.html"), Runner.fitReport(Path.of("out/notes")));
    }
}
