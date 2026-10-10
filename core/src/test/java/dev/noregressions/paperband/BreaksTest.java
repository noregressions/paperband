package dev.noregressions.paperband;

import dev.noregressions.paperband.layout.Layout;
import dev.noregressions.paperband.layout.Layouts;
import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BreaksTest {

    @TempDir
    Path dir;

    private static String parseError(String markdown) {
        return assertThrows(IOException.class, () -> new CombinedAst().add(Path.of("a.md"), markdown)).getMessage();
    }

    @Test
    void markersAreCheckedWhenParsed() throws IOException {
        MarkdownFile f = new CombinedAst().add(Path.of("a.md"), "## Step {! break=force}\n\n::: {! keep}\nA\n:::\n");
        assertEquals(Breaks.Break.FORCE, Breaks.of(f.getAttributes(f.getFirstChild())));
        assertTrue(Breaks.isKeep(f.getAttributes(f.getFirstChild().getNext())));

        assertEquals("a.md line 1: break \"now\" isn't one of [prefer, avoid, force]", parseError("Text. {! break=now}\n"));
        assertEquals("a.md line 1: {! break=force} goes on a block (a heading, paragraph, list, code block or ::: group),"
                + " not inside text", parseError("Click [here]{! break=force} now.\n"));
        assertEquals("a.md line 4: {! break=prefer} can't take effect inside the {! keep} group on line 1, which never"
                + " breaks", parseError("::: {! keep}\nA\n\nB {! break=prefer}\n:::\n"));
        new CombinedAst().add(Path.of("b.md"), "::: {! keep}\nA\n\nB {! break=avoid}\n:::\n"); // avoid is fine there
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private String layoutError(String yaml) throws IOException {
        write("layouts/bad.yml", yaml);
        return assertThrows(IOException.class, () -> Layouts.load(dir.resolve("layouts"), null)).getMessage();
    }

    @Test
    void splitRulesAreParsed() throws IOException {
        write("layouts/steps.yml", """
                boxes:
                  - { id: title, select: h1 }
                  - id: steps
                    select: rest
                    split:
                      between: section.h2
                      keep-together: [pre, ".keep-me"]
                      repeat: "h2:first-of-type"
                      label: continued
                      min-units: 2
                      prefer-fill: 60%
                      markers: add
                      continue: more
                      into: body
                """);
        write("layouts/more.yml", "boxes:\n  - { id: body, select: rest, split: true }\n");
        Layouts layouts = Layouts.load(dir.resolve("layouts"), null);
        Layout steps = layouts.get("steps");
        Layout.Split s = steps.splitting().rules().split();
        assertEquals("steps", steps.splitting().id());
        assertEquals("section.h2", s.between());
        assertEquals(List.of("pre", ".keep-me"), s.keepTogether());
        assertEquals("h2:first-of-type", s.repeat());
        assertEquals("continued", s.label());
        assertEquals(2, s.minUnits());
        assertEquals(0.6, s.preferFill(), 1e-9);
        assertEquals(Layout.BreakMarkers.ADD, s.markers());
        assertEquals("more", s.continueIn(steps));
        assertEquals("body", s.intoBox(steps.splitting()));

        Layout more = layouts.get("more");
        Layout.Split d = more.splitting().rules().split();
        assertNull(d.between());
        assertEquals(1, d.minUnits());
        assertEquals(0.5, d.preferFill(), 1e-9);
        assertEquals(Layout.BreakMarkers.NARROW, d.markers());
        assertEquals("more", d.continueIn(more), "continues in its own layout");
        assertEquals("body", d.intoBox(more.splitting()));
        assertNull(layouts.get("steps").boxes().getFirst().rules().split());
    }

    @Test
    void splitRulesAreChecked() throws IOException {
        assertEquals("bad.yml: boxes[0]: split: a text box can't split; only select, rest and each boxes can",
                layoutError("boxes: [{ id: a, text: Hi, split: true }]\n"));
        assertEquals("bad.yml: boxes[0].boxes[0]: split: only a page's own boxes can split, not nested boxes",
                layoutError("boxes: [{ id: a, select: div, boxes: [{ id: b, select: rest, split: true }] }]\n"));
        assertEquals("bad.yml: boxes[1]: split: box \"a\" already splits; only one box in a layout can split",
                layoutError("boxes: [{ id: a, select: p, split: true }, { id: b, select: rest, split: true }]\n"));
        assertEquals("bad.yml: boxes[0].split.min-units: expected a whole number from 1, got \"0\"",
                layoutError("boxes: [{ id: a, select: rest, split: { min-units: 0 } }]\n"));
        assertEquals("bad.yml: boxes[0].split.prefer-fill: expected a percentage from 0% to 100%, e.g. 60%, got \"120%\"",
                layoutError("boxes: [{ id: a, select: rest, split: { prefer-fill: 120% } }]\n"));
        assertEquals("bad.yml: boxes[0].split.markers: \"some\" isn't one of [narrow, add, ignore]",
                layoutError("boxes: [{ id: a, select: rest, split: { markers: some } }]\n"));
        assertTrue(layoutError("boxes: [{ id: a, select: rest, split: { between: \"p[\" } }]\n")
                .startsWith("bad.yml: boxes[0].split.between: invalid selector \"p[\""));
        assertTrue(layoutError("boxes: [{ id: a, select: rest, split: { inbetween: p } }]\n")
                .contains("inbetween"));
        assertEquals("bad: box \"a\" split: continue: unknown layout \"nope\"; available: [bad, default]",
                layoutError("boxes: [{ id: a, select: rest, split: { continue: nope } }]\n"));
        assertEquals("bad: box \"a\" split: into: this layout has no box \"b\" to take the rest; its boxes are [a]",
                layoutError("boxes: [{ id: a, select: rest, split: { into: b } }]\n"));
        assertEquals("bad: box \"a\" split: into: box \"content\" in layout \"default\" can't take the rest of an each"
                        + " box; it must be an each box too",
                layoutError("boxes: [{ id: a, each: .step, boxes: [{ id: t, select: rest }], split: { continue: default,"
                        + " into: content } }]\n"));
    }

    private static final String STEPS = """
            # Install

            Intro.

            ## Download

            Get it.

            ## Verify {! break=force}

            Check it.

            ```bash
            sha256sum app.tgz
            ```
            """;

    /** Builds docs/a.md with layout {@code layout}, in a paged-html output and a web output. */
    private void build(String markdown, String layout, boolean paged) throws IOException {
        write("docs/a.md", markdown);
        write("layouts/l.yml", layout);
        Path m = write("manifest.yml", "sources: [docs/a.md]\nsequence: [{ file: docs/a.md, layout: l }]\n"
                + "outputs: [{ name: o, format: " + (paged ? "paged-html" : "web") + ", file: o.html }]\n");
        SiteBuilder.build(Manifest.load(m));
    }

    private String buildError(String markdown, String layout) {
        return assertThrows(IOException.class, () -> build(markdown, layout, true)).getMessage();
    }

    @Test
    void aBreakNeedsABoxThatSplits() throws IOException {
        String noSplit = "boxes: [{ id: body, select: rest }]\n";
        assertEquals("docs/a.md line 9: {! break=force} is in box \"body\" of layout \"l\" (docs/a.md), which doesn't"
                + " split; give the box a split rule, or remove the marker", buildError(STEPS, noSplit));
        build(STEPS, noSplit, false); // web output has no pages to break
        build(STEPS.replace("break=force", "break=avoid"), noSplit, true); // nothing breaks, so avoid holds
    }

    @Test
    void narrowMarkersMustBeAtBreakPointsTheLayoutAllows() throws IOException {
        build(STEPS, "boxes: [{ id: body, select: rest, split: { between: section.h2 } }]\n", true);
        build(STEPS, "boxes: [{ id: body, select: rest, split: true }]\n", true); // between its blocks

        String para = STEPS.replace("Check it.", "Check it. {! break=prefer}").replace(" {! break=force}", "");
        assertEquals("docs/a.md line 11: {! break=prefer} is in box \"body\" of layout \"l\" (docs/a.md), but not at a"
                        + " break point the box allows (split.between: section.h2); move the marker, or let markers make"
                        + " break points with split.markers: add",
                buildError(para, "boxes: [{ id: body, select: rest, split: { between: section.h2 } }]\n"));
        build(para, "boxes: [{ id: body, select: rest, split: { between: section.h2, markers: add } }]\n", true);
        build(para, "boxes: [{ id: body, select: rest, split: { between: section.h2, markers: ignore } }]\n", true);

        String nested = STEPS.replace(" {! break=force}", "") + "\n- one\n- two {! break=force}\n";
        assertTrue(buildError(nested, "boxes: [{ id: body, select: rest, split: true }]\n")
                .contains("but not at a break point the box allows (between its blocks)"));
    }

    @Test
    void eachBoxesBreakBetweenTheirMatches() throws IOException {
        String layout = """
                boxes:
                  - { id: title, select: h1 }
                  - id: steps
                    each: section.h2
                    boxes: [{ id: all, select: rest }]
                    split: true
                unplaced: ignore
                """;
        build(STEPS, layout, true);
        String inside = STEPS.replace(" {! break=force}", "").replace("Check it.", "Check it. {! break=force}");
        assertTrue(buildError(inside, layout).contains("between its each: section.h2 matches"));
    }

    @Test
    void breaksCantGoAtTheStartOrInsideWhatIsKeptTogether() throws IOException {
        String first = STEPS.replace(" {! break=force}", "").replace("## Download", "## Download {! break=force}");
        assertTrue(buildError(first, """
                boxes:
                  - { id: intro, select: "section.h1 > p" }
                  - { id: steps, select: section.h2, split: true }
                unplaced: ignore
                """).endsWith("but it's at the very start of the box, with nothing before it to break from"));

        String inPre = STEPS.replace(" {! break=force}", "").replace("Check it.", "Check it.\n\n::: {! wrap}\n"
                + "One.\n\nTwo. {! break=force}\n:::");
        assertTrue(buildError(inPre, "boxes: [{ id: body, select: rest, split: { markers: add, keep-together: [.wrap] } }]\n")
                .contains("inside \".wrap\", which split.keep-together never breaks"));
    }

    @Test
    void aSplittingLayoutCantBeUsedInsideABox() throws IOException {
        write("layouts/inner.yml", "boxes: [{ id: x, select: rest, split: true }]\n");
        String message = buildError("# A\n\nText.\n", "boxes: [{ id: wrap, layout: inner }]\n");
        assertEquals("docs/a.md: layout \"inner\" is used inside box \"wrap\", but its box \"x\" splits; only a page's"
                + " own boxes can split", message);
    }
}
