package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.SiteBuilder;
import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.fit.FitException;
import dev.noregressions.paperband.layout.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Boxes that split across pages, measured in Chromium. */
class SplitTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private BuildConfig config(String format, String markdown, String... layouts) throws IOException {
        write("docs/a.md", markdown);
        for (int i = 0; i < layouts.length; i += 2) {
            write("layouts/" + layouts[i] + ".yml", layouts[i + 1]);
        }
        write("manifest.yml", "sources: [docs/a.md]\nsequence: [{ file: docs/a.md, layout: l }]\n"
                + "outputs: [{ name: print, format: " + format + ", file: out/a." + (format.equals("pdf") ? "pdf" : "html")
                + ", page: { size: A5, margin: 10mm } }]\n");
        BuildConfig.Builder b = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (ChromiumBrowserTest.BROWSER != null) {
            b.browser(ChromiumBrowserTest.BROWSER);
        }
        return b.build();
    }

    private SiteBuilder.Output build(String markdown, String... layouts) throws IOException {
        BuildConfig c = config("paged-html", markdown, layouts);
        return SiteBuilder.build(c.manifest(), c.clock()).outputs().getFirst();
    }

    private static String text(int sentences) {
        return "A sentence that takes up some room on the page. ".repeat(sentences);
    }

    /** A tutorial: a title and {@code n} sections of {@code sentences} each. */
    private static String sections(int n, int sentences) {
        StringBuilder sb = new StringBuilder("# Tutorial\n\nIntro.\n");
        for (int i = 1; i <= n; i++) {
            sb.append("\n## Step ").append(i).append("\n\n").append(text(sentences)).append('\n');
        }
        return sb.toString();
    }

    private static final String STACK = """
            stack: {}
            boxes:
              - { id: title, select: h1 }
              - id: body
                select: rest
                split: SPLIT
            """;

    private static int count(String html, String what) {
        Matcher m = Pattern.compile(Pattern.quote(what)).matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void aLongBoxContinuesOnAsManyPagesAsItNeeds() throws IOException {
        SiteBuilder.Output out = build(sections(8, 9), "l", STACK.replace("SPLIT", "{ between: section.h2 }"));
        assertEquals(List.of(), out.problems());
        List<String> ids = out.pages().stream().map(Page::id).toList();
        assertTrue(ids.size() >= 3, ids.toString());
        assertEquals("docs-a-md", ids.get(0));
        assertEquals("docs-a-md--2", ids.get(1));
        assertEquals("docs-a-md--3", ids.get(2));
        String html = out.html();
        assertEquals(1, count(html, "<h1"), "the title isn't shown again on continuation pages");
        for (int i = 1; i <= 8; i++) {
            assertEquals(1, count(html, ">Step " + i + "</h2>"), "step " + i + " appears exactly once");
        }
        assertTrue(out.remedies().getFirst().startsWith("page docs-a-md (docs/a.md): box body: units 1-"), out.remedies().toString());
        assertTrue(out.remedies().getFirst().contains("of 9 here, the rest on page docs-a-md--2 (the break is before line "),
                out.remedies().toString());
        // Every break is between sections: each continuation's body starts with a step heading.
        for (Page p : out.pages().subList(1, out.pages().size())) {
            Page.PlacedBox body = p.boxes().stream().filter(b -> b.box().id().equals("body")).findFirst().orElseThrow();
            assertTrue(body.nodes().getFirst() instanceof org.commonmark.node.Heading, p.id());
        }
    }

    @Test
    void forcedAndPreferredBreaks() throws IOException {
        String split = STACK.replace("SPLIT", "{ between: section.h2 }");
        SiteBuilder.Output forced = build(sections(3, 1).replace("## Step 3", "## Step 3 {! break=force}"), "l", split);
        assertEquals(2, forced.pages().size(), "a forced break splits even with room left");
        assertTrue(forced.html().indexOf("Step 3") > forced.html().indexOf("id=\"docs-a-md--2\""));

        // Without the marker the box breaks as late as it can; with it, at the preferred break if the page is full enough.
        SiteBuilder.Output late = build(sections(5, 9), "l", split);
        SiteBuilder.Output preferred = build(sections(5, 9).replace("## Step 2", "## Step 2 {! break=prefer}"), "l",
                STACK.replace("SPLIT", "{ between: section.h2, prefer-fill: 10% }"));
        int lateBreak = firstBreak(late);
        assertTrue(lateBreak > 2, "breaks late: before unit " + lateBreak);
        assertEquals(2, firstBreak(preferred), preferred.remedies().toString());
    }

    /** The unit (1-based, counting the intro) the first page's body breaks before. */
    private static int firstBreak(SiteBuilder.Output out) {
        Matcher m = Pattern.compile("units 1-(\\d+) of").matcher(out.remedies().getFirst());
        assertTrue(m.find(), out.remedies().toString());
        return Integer.parseInt(m.group(1));
    }

    @Test
    void minUnitsKeepsUnitsTogether() throws IOException {
        SiteBuilder.Output out = build(sections(4, 12), "l", STACK.replace("SPLIT", "{ between: section.h2, min-units: 2 }"));
        for (String r : out.remedies()) {
            Matcher m = Pattern.compile("units (\\d+)-(\\d+) of (\\d+)").matcher(r);
            if (m.find()) {
                assertTrue(Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(1)) + 1 >= 2, r);
            }
        }
    }

    @Test
    void continuationsRepeatTheHeadingTheyContinue() throws IOException {
        StringBuilder md = new StringBuilder("# Guide\n\n## Installing\n");
        for (int i = 1; i <= 8; i++) {
            md.append("\n### Part ").append(i).append("\n\n").append(text(8)).append('\n');
        }
        SiteBuilder.Output out = build(md.toString(), "l",
                STACK.replace("SPLIT", "{ between: section.h3, repeat: h2, label: continued }"));
        assertEquals(List.of(), out.problems());
        assertTrue(out.pages().size() >= 2);
        assertTrue(out.html().contains("<h2 class=\"continued\">Installing<span class=\"continued-label\"> (continued)</span></h2>"),
                out.html());
    }

    @Test
    void continuationLayoutsAndEachBoxes() throws IOException {
        String main = """
                stack: {}
                boxes:
                  - { id: title, select: h1 }
                  - id: steps
                    each: section.h2
                    boxes: [{ id: all, select: rest }]
                    split: { continue: more, into: rest-of-steps }
                unplaced: ignore
                """;
        String more = """
                stack: {}
                boxes:
                  - { id: note, text: "Steps, continued" }
                  - id: rest-of-steps
                    each: section.h2
                    boxes: [{ id: all, select: rest }]
                """;
        SiteBuilder.Output out = build(sections(6, 10), "l", main, "more", more);
        assertEquals(List.of(), out.problems(), out.remedies().toString());
        assertTrue(out.pages().size() >= 2);
        Page second = out.pages().get(1);
        assertEquals("more", second.layout().name());
        assertTrue(out.html().contains("Steps, continued"));
        assertTrue(out.html().contains("data-box=\"rest-of-steps[0]/all\""), "the rest goes into the continuation's box");
        for (int i = 1; i <= 6; i++) {
            assertEquals(1, count(out.html(), ">Step " + i + "</h2>"), "step " + i);
        }
    }

    @Test
    void splittingIsOnlyForContentThatRunsOverTheBottom() throws IOException {
        SiteBuilder.Output out = build("# T\n\n## A\n\nShort.\n\n## B\n\n```\n" + "x".repeat(90) + "\n```\n", "l", """
                stack: {}
                boxes:
                  - { id: title, select: h1 }
                  - { id: body, select: rest, split: true, fit: [{ shrink: { min: 50% } }] }
                """);
        assertEquals(List.of(), out.problems());
        assertEquals(1, out.pages().size(), "a line too wide isn't fixed by another page");
        assertTrue(out.remedies().getFirst().contains("box body: text at "), out.remedies().toString());
    }

    /** One step too many for an A5 page at full size: 4 steps of 9 sentences don't fit, but nearly do. */
    private static final String NEARLY = sections(4, 9);

    private static String splitWith(String fit) {
        return STACK.replace("SPLIT", "{ between: section.h2 }\n    fit: " + fit);
    }

    @Test
    void remediesComeBeforeSplitting() throws IOException {
        SiteBuilder.Output plain = build(NEARLY, "l", STACK.replace("SPLIT", "{ between: section.h2 }"));
        assertEquals(2, plain.pages().size(), "without remedies it splits: " + plain.remedies());

        SiteBuilder.Output shrunk = build(NEARLY, "l", splitWith("[{ shrink: { min: 70% } }]"));
        assertEquals(List.of(), shrunk.problems());
        assertEquals(1, shrunk.pages().size(), "slightly smaller text instead of a second page: " + shrunk.remedies());
        assertTrue(shrunk.remedies().getFirst().contains("box body: text at "), shrunk.remedies().toString());

        SiteBuilder.Output first = build(NEARLY, "l", splitWith("[split, { shrink: { min: 70% } }]"));
        assertEquals(2, first.pages().size(), "split listed first: it splits straight away");
        assertFalse(first.html().contains("zoom"), "and each part fits at full size");
    }

    @Test
    void remediesThatAreNotEnoughAreUndoneBeforeSplitting() throws IOException {
        SiteBuilder.Output out = build(sections(8, 9), "l", splitWith("[{ shrink: { min: 90% } }]"));
        assertEquals(List.of(), out.problems());
        assertTrue(out.pages().size() >= 3);
        assertFalse(out.html().contains("zoom"), "the text is back at full size: " + out.remedies());
        assertTrue(out.remedies().getFirst().contains("box body: units 1-"), out.remedies().toString());
    }

    @Test
    void aUnitTooBigForAPageFailsTheBuild() throws IOException {
        BuildConfig c = config("paged-html", "# T\n\n## Small\n\nShort.\n\n## Huge\n\n" + text(150) + "\n", "l",
                STACK.replace("SPLIT", "{ between: section.h2 }"));
        FitException e = assertThrows(FitException.class, () -> Runner.run(c));
        assertTrue(e.getMessage().contains("page docs-a-md--2 (docs/a.md), box body: "), e.getMessage());
        assertTrue(e.getMessage().contains("split: the unit at line 7 doesn't fit on a page by itself"), e.getMessage());
    }

    @Test
    void thePdfHasEveryContinuationPage() throws IOException {
        BuildConfig c = config("pdf", sections(8, 9), "l", STACK.replace("SPLIT", "{ between: section.h2 }"));
        Runner.Outcome outcome = Runner.run(c);
        int pages = outcome.build().outputs().getFirst().pages().size();
        assertEquals(pages, ChromiumBrowserTest.pages(Files.readAllBytes(dir.resolve("out/a.pdf"))).size());
    }
}
