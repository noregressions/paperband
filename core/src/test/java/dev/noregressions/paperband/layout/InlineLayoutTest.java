package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.SiteBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Layouts written in a markdown file's own front matter. */
class InlineLayoutTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private Layouts withInline(String markdown) throws IOException {
        Path md = write("docs/a.md", markdown);
        Map<String, Object> fm = FrontMatter.split(md, Files.readString(md)).data();
        return Layouts.builtIn().withInline(Map.of(Path.of("docs/a.md"), fm), dir, PageSpec.A4);
    }

    @Test
    void frontMatterLayoutIsNamedAfterItsFile() throws IOException {
        Layouts layouts = withInline("""
                ---
                layout:
                  grid: { areas: ["title body"] }
                  css: |
                    h1 { color: navy }
                  boxes:
                    - { id: title, area: title, select: h1 }
                    - { id: body, area: body, select: rest }
                ---
                # A
                """);
        assertEquals("inline-docs-a-md", Layouts.inlineName(Path.of("docs/a.md")));
        Layout inline = layouts.get("inline-docs-a-md");
        assertEquals(List.of("title", "body"), inline.boxes().stream().map(Layout.Box::id).toList());
        assertNotNull(inline.grid());
        assertEquals(".layout-inline-docs-a-md h1 { color: navy }\n", inline.css());
        assertTrue(layouts.source("inline-docs-a-md").startsWith("# inline layout, from the front matter of docs/a.md\nlayout:\n  grid:"),
                layouts.source("inline-docs-a-md"));
    }

    @Test
    void stylesheetIsRelativeToTheMarkdownFile() throws IOException {
        write("docs/a.css", "p { margin: 0 }");
        Layouts layouts = withInline("---\nlayout:\n  stylesheet: a.css\n  boxes: [{ id: b, select: rest }]\n---\nText\n");
        assertEquals(".layout-inline-docs-a-md p { margin: 0 }\n", layouts.get("inline-docs-a-md").css());
    }

    @Test
    void mistakesPointIntoTheFrontMatter() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> withInline("---\nlayout:\n  boxes: [{ id: b, select: h1, colour: red }]\n---\n"));
        assertTrue(e.getMessage().startsWith("docs/a.md front matter layout: boxes[0]: unknown key \"colour\""), e.getMessage());

        e = assertThrows(ConfigException.class,
                () -> withInline("---\nlayout:\n  boxes: [{ id: b, layout: nowhere }]\n---\n"));
        assertTrue(e.getMessage().startsWith("inline-docs-a-md: unknown layout \"nowhere\""), e.getMessage());

        e = assertThrows(ConfigException.class, () -> withInline("---\nlayout:\n  css: \"body { margin: 0 }\"\n  boxes: [{ id: b, select: rest }]\n---\n"));
        assertTrue(e.getMessage().contains("docs/a.md front matter layout: css line 1: \"body\" can't be styled"), e.getMessage());
    }

    @Test
    void namedAndInlineLayoutsMixInOneBuild() throws IOException {
        write("layouts/card.yml", "css: \"& { border: 1px solid }\"\nboxes: [{ id: all, select: rest }]\n");
        write("docs/inline.md", """
                ---
                layout:
                  boxes:
                    - { id: title, select: h1 }
                    - { id: summary, select: "#summary", layout: card }
                ---
                # Inline

                ## Summary {! id=summary}

                Short.
                """);
        write("docs/named.md", "---\nlayout: card\n---\n# Named\n");
        write("docs/plain.md", "# Plain\n");
        write("layouts/show.yml", "boxes:\n  - { id: yaml, content: layout-source }\n  - { id: result, layout: page }\n");
        Path manifest = write("manifest.yml", "sources: [docs/*.md]\nsequence:\n  - files: docs/*.md\n");

        SiteBuilder.Build build = SiteBuilder.build(Manifest.load(manifest));
        String html = build.html();
        assertTrue(html.contains("<section class=\"page layout-inline-docs-inline-md\" id=\"docs-inline-md\""), html);
        assertTrue(html.contains("<div class=\"layout layout-card\" data-layout=\"card\">"), "inline layout uses a named one");
        assertTrue(html.contains("<section class=\"page layout-card\" id=\"docs-named-md\""), html);
        assertTrue(html.contains(".layout-card { border: 1px solid }"), html);
        assertTrue(build.report().warnings().isEmpty(), build.report().warnings().toString());
    }

    @Test
    void layoutPageAndLayoutSourceFindTheInlineLayout() throws IOException {
        write("layouts/show.yml", "boxes:\n  - { id: yaml, content: layout-source }\n  - { id: result, layout: page }\n");
        write("docs/a.md", "---\nlayout:\n  boxes: [{ id: only, select: h1 }]\n---\n# Title\n");
        Path manifest = write("manifest.yml", "sources: [docs/*.md]\nsequence:\n  - { files: docs/*.md, layout: show }\n");

        SiteBuilder.Build build = SiteBuilder.build(Manifest.load(manifest));
        Page.PlacedBox yaml = build.pages().getFirst().boxes().get(0);
        assertTrue(yaml.source().contains("  boxes:\n    - id: only\n      select: h1"), yaml.source());
        Page.Nested result = build.pages().getFirst().boxes().get(1).nested().getFirst();
        assertEquals("inline-docs-a-md", result.layout().name());
    }
}
