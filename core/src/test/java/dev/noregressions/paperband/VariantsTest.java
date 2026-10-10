package dev.noregressions.paperband;

import dev.noregressions.paperband.layout.Manifest;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.noregressions.paperband.Variants.Variant.*;
import static org.junit.jupiter.api.Assertions.*;

class VariantsTest {

    @TempDir
    Path dir;

    private static final String GROUP = """
            Before.

            :::: {! variants}
            ::: {! variant=shortest}
            Tiny.
            :::
            ::: {! variant=full}
            The full explanation.
            :::
            ::: {! variant=short}
            Shorter.
            :::
            ::::

            After.
            """;

    private static MarkdownFile parse(String markdown) throws IOException {
        return new CombinedAst().add(Path.of("a.md"), markdown);
    }

    private static Node group(MarkdownFile f) {
        for (Node n = f.getFirstChild(); n != null; n = n.getNext()) {
            if (Variants.isGroup(n)) {
                return n;
            }
        }
        throw new AssertionError("no group");
    }

    private static String error(String markdown) {
        return assertThrows(IOException.class, () -> parse(markdown)).getMessage();
    }

    @Test
    void choosesTheLongestByDefaultAndFallsBackByRank() throws IOException {
        MarkdownFile f = parse(GROUP);
        Node g = group(f);
        assertEquals(FULL, Variants.variantOf(Variants.choose(g, null)), "written order doesn't matter");
        assertEquals(SHORT, Variants.variantOf(Variants.choose(g, SHORT)));

        Node partial = group(parse(":::: {! variants}\n::: {! variant=full}\nA\n:::\n::: {! variant=shortest}\nC\n:::\n::::\n"));
        assertEquals(SHORTEST, Variants.variantOf(Variants.choose(partial, SHORT)), "no short: the next shorter");
        Node onlyFull = group(parse(":::: {! variants}\n::: {! variant=full}\nA\n:::\n::::\n"));
        assertEquals(FULL, Variants.variantOf(Variants.choose(onlyFull, SHORTEST)), "nothing shorter: the next longer");
    }

    @Test
    void renderingAndTextShowOnlyTheChosenVariant() throws IOException {
        MarkdownFile f = parse(GROUP);
        Node g = group(f);
        String full = MarkdownHtmlRenderer.renderNodes(List.of(g), new HtmlIds("p", new java.util.HashSet<>()), null);
        assertTrue(full.contains("The full explanation.") && !full.contains("Shorter.") && !full.contains("Tiny."), full);
        assertTrue(full.contains("class=\"variants\"") && full.contains("data-variant=\"full\""), full);
        String shortest = MarkdownHtmlRenderer.renderNodes(List.of(g), new HtmlIds("p", new java.util.HashSet<>()), SHORTEST);
        assertTrue(shortest.contains("Tiny.") && !shortest.contains("The full"), shortest);

        assertEquals("Shorter.", Site.text(g, SHORT));
        assertEquals("The full explanation.", Site.text(g));
    }

    @Test
    void groupsAreCheckedWhenParsed() {
        assertTrue(error(":::: {! variants}\nLoose paragraph.\n::::\n")
                .startsWith("a.md line 2: a {! variants} group can only hold ::: {! variant=...} groups; found Paragraph"));
        assertEquals("a.md line 2: variant \"tiny\" isn't one of [full, short, shortest]",
                error(":::: {! variants}\n::: {! variant=tiny}\nA\n:::\n::::\n"));
        assertEquals("a.md line 5: variant \"short\" appears twice in this group",
                error(":::: {! variants}\n::: {! variant=short}\nA\n:::\n::: {! variant=short}\nB\n:::\n::::\n"));
        assertEquals("a.md line 1: a ::: {! variant=...} group belongs inside a :::: {! variants} group",
                error("::: {! variant=full}\nA\n:::\n"));
        assertEquals("a.md line 1: {! optional} priority must be a whole number from 1, got \"high\"",
                error("Background. {! optional priority=high}\n"));
    }

    @Test
    void optionalContentIsJustTagged() throws IOException {
        MarkdownFile f = parse("Background. {! optional priority=2}\n");
        assertEquals(List.of("optional"), f.getAttributes(f.getFirstChild()).tags());
        assertEquals("2", f.getAttributes(f.getFirstChild()).values().get("priority"));
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void preferenceComesFromTheOutputTheLayoutOrTheBox() throws IOException {
        write("docs/a.md", "# Title\n\n" + GROUP);
        write("layouts/plain.yml", "boxes:\n  - { id: all, select: rest }\n");
        write("layouts/brief.yml", "prefer: short\nboxes:\n  - { id: all, select: rest }\n");
        write("layouts/mixed.yml", "boxes:\n  - { id: title, select: h1 }\n  - { id: all, select: rest, prefer: shortest }\n");
        Path m = write("manifest.yml", """
                sources: [docs/a.md]
                sequence: [{ file: docs/a.md }]
                outputs:
                  - { name: web, format: web, file: web.html, layout: plain }
                  - { name: quick, format: web, file: quick.html, layout: plain, prefer: short }
                  - { name: layout-wins, format: web, file: lw.html, layout: brief, prefer: shortest }
                  - { name: box-wins, format: web, file: bw.html, layout: mixed, prefer: full }
                """);
        SiteBuilder.Build build = SiteBuilder.build(Manifest.load(m));
        assertTrue(build.outputs().get(0).html().contains("The full explanation."));
        assertTrue(build.outputs().get(1).html().contains("Shorter."), "the output's prefer");
        assertTrue(build.outputs().get(2).html().contains("Shorter."), "the layout's prefer wins over the output's");
        assertTrue(build.outputs().get(3).html().contains("Tiny."), "the box's prefer wins for what it shows");
        for (SiteBuilder.Output o : build.outputs()) {
            long shown = List.of("The full explanation.", "Shorter.", "Tiny.").stream().filter(o.html()::contains).count();
            assertEquals(1, shown, o.name() + " shows exactly one variant");
        }
    }

    @Test
    void selectorsOnlySeeTheChosenVariant() throws IOException {
        write("docs/a.md", GROUP);
        write("layouts/pick.yml", "unplaced: ignore\nboxes:\n  - { id: inside, select: \".variants p\" }\n");
        Path m = write("manifest.yml", "sources: [docs/a.md]\nsequence: [{ file: docs/a.md, layout: pick }]\n"
                + "outputs: [{ name: q, format: web, file: q.html, prefer: short }]\n");
        String html = SiteBuilder.build(Manifest.load(m)).html();
        assertTrue(html.contains("Shorter.") && !html.contains("The full") && !html.contains("Tiny."), html);
    }

    @Test
    void preferValuesAreChecked() throws IOException {
        write("docs/a.md", "# A\n");
        write("layouts/bad.yml", "prefer: brief\nboxes: [{ id: a, select: rest }]\n");
        Path m = write("manifest.yml", "sources: [docs/a.md]\nsequence: [{ file: docs/a.md }]\n");
        IOException e = assertThrows(IOException.class, () -> SiteBuilder.build(Manifest.load(m)));
        assertTrue(e.getMessage().contains("bad.yml: prefer: \"brief\" isn't one of [full, short, shortest]"), e.getMessage());
    }
}
