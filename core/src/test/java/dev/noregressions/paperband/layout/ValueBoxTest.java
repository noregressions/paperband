package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.SiteBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Boxes that show a value from the page's front matter. */
class ValueBoxTest {

    @TempDir
    Path dir;

    private static final String CHAPTER = """
            ---
            title: Removed packages
            level: crashes-at-runtime
            symptom: "`NoClassDefFoundError` for `javax.xml.bind.*` — [fatal]{! fatal} at *first use*"
            tools: [jdeps, "`jdeprscan`"]
            author: { name: Ada }
            ---
            # Removed packages

            Text.
            """;

    private static final String LEVELS = """
            wont-start: { name: "Won't Start" }
            crashes-at-runtime: { name: Crashes at Runtime }
            """;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private SiteBuilder.Build build(String markdown, String boxes) throws IOException {
        write("docs/a.md", markdown);
        write("data/levels.yml", LEVELS);
        write("layouts/l.yml", "boxes:\n" + boxes + "  - { id: body, select: rest }\n");
        write("manifest.yml", "sources: [docs/a.md]\nsequence: [{ file: docs/a.md, layout: l }]\n");
        return SiteBuilder.build(Manifest.load(dir.resolve("manifest.yml")));
    }

    private String html(String boxes) throws IOException {
        return build(CHAPTER, boxes).html();
    }

    @Test
    void aValueShowsAsInlineMarkdownWithItsMarkers() throws IOException {
        String html = html("  - { id: symptom, value: symptom }\n");
        assertTrue(html.contains("<div class=\"box box-symptom\" data-box=\"symptom\">\n<code>NoClassDefFoundError</code> for"
                + " <code>javax.xml.bind.*</code> — <span class=\"fatal\">fatal</span> at <em>first use</em>\n</div>"), html);
    }

    @Test
    void lookupShowsANameAndTheIdBecomesAClass() throws IOException {
        String html = html("  - { id: level, value: level, lookup: levels }\n  - { id: raw, value: level }\n");
        assertTrue(html.contains("<div class=\"box box-level level-crashes-at-runtime\" data-box=\"level\">\nCrashes at Runtime\n</div>"),
                html);
        assertTrue(html.contains("<div class=\"box box-raw level-crashes-at-runtime\" data-box=\"raw\">\ncrashes-at-runtime\n</div>"),
                html);
    }

    @Test
    void textListsAndNestedKeys() throws IOException {
        String html = html("  - { id: plain, value: symptom, as: text }\n  - { id: tools, value: tools }\n"
                + "  - { id: who, value: author.name }\n");
        assertTrue(html.contains("NoClassDefFoundError for javax.xml.bind.* — fatal at first use\n</div>"), html);
        assertTrue(html.contains("<ul>\n<li>jdeps</li>\n<li><code>jdeprscan</code></li>\n</ul>"), html);
        assertTrue(html.contains("data-box=\"who\">\nAda\n</div>"), html);
    }

    @Test
    void aMissingValueWarnsUnlessTheBoxIsOptional() throws IOException {
        SiteBuilder.Build warned = build(CHAPTER, "  - { id: note, value: level_note }\n");
        assertEquals(1, warned.report().warnings().size());
        assertTrue(warned.report().warnings().getFirst().endsWith("box \"note\": the page's front matter has no level_note"));
        assertFalse(warned.html().contains("box-note"));

        SiteBuilder.Build quiet = build(CHAPTER, "  - { id: note, value: level_note, when: { not-empty: true } }\n");
        assertEquals(java.util.List.of(), quiet.report().warnings());
    }

    @Test
    void aPanelOfValuesLeavesThePageForTheBoxesAfterIt() throws IOException {
        String html = html("  - id: head\n    boxes: [{ id: level, value: level }, { id: label, text: Level }]\n");
        assertTrue(html.contains("data-box=\"body\">\n<h1 id="), "the body still has the page: " + html);
        assertTrue(html.contains("<p>Text.</p>"), html);
    }

    @Test
    void mistakesAreReported() throws IOException {
        IOException unknown = assertThrows(IOException.class, () -> build(CHAPTER.replace("level: crashes-at-runtime",
                "level: wont-run"), "  - { id: level, value: level, lookup: levels }\n"));
        assertEquals("docs/a.md: level \"wont-run\" isn't in data.levels; it has [wont-start, crashes-at-runtime]",
                unknown.getMessage());

        IOException noData = assertThrows(IOException.class, () -> build(CHAPTER, "  - { id: level, value: level, lookup: tiers }\n"));
        assertTrue(noData.getMessage().endsWith("box \"level\": lookup: there's no data.tiers (a data file such as data/tiers.yml)"),
                noData.getMessage());

        IOException marker = assertThrows(IOException.class, () -> build(CHAPTER.replace("[fatal]{! fatal}", "fatal {!oops"),
                "  - { id: symptom, value: symptom }\n"));
        assertTrue(marker.getMessage().startsWith("docs/a.md front matter symptom line 1: "), marker.getMessage());

        write("layouts/bad.yml", "boxes: [{ id: a, value: symptom, select: h1 }]\n");
        assertTrue(assertThrows(IOException.class, () -> Layouts.load(dir.resolve("layouts"), PageSpec.A4)).getMessage()
                .endsWith("a value box can't also set select, text, content, each, layout, boxes, pick or scope"));
        write("layouts/bad.yml", "boxes: [{ id: a, value: symptom, as: list }]\n");
        assertTrue(assertThrows(IOException.class, () -> Layouts.load(dir.resolve("layouts"), PageSpec.A4)).getMessage()
                .endsWith("as: a value box shows html or text"));
        write("layouts/bad.yml", "boxes: [{ id: a, select: h1, lookup: levels }]\n");
        assertTrue(assertThrows(IOException.class, () -> Layouts.load(dir.resolve("layouts"), PageSpec.A4)).getMessage()
                .endsWith("lookup only applies to a value box"));
    }

    @Test
    void aValueCanBeShownAsAHeading() throws IOException {
        String out = html("  - { id: title, value: title, heading: 1 }\n  - { id: symptom, value: symptom, heading: 3 }\n");
        assertTrue(out.contains("<h1>Removed packages</h1>"), out);
        assertTrue(out.contains("<h3><code>NoClassDefFoundError</code> for"), "inline markdown stays: " + out);
        ConfigException e = assertThrows(ConfigException.class, () -> html("  - { id: title, value: title, heading: 7 }\n"));
        assertTrue(e.getMessage().contains("heading level from 1 to 6"), e.getMessage());
    }
}
