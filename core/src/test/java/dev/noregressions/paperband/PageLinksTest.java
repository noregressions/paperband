package dev.noregressions.paperband;

import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Links to pages by their front matter id, and the check for links to ids that aren't there. */
class PageLinksTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private SiteBuilder.Build build(String sequence) throws IOException {
        write("docs/a.md", """
                ---
                id: intro
                title: Introduction
                ---
                # Intro

                See Chapter [#](#jdeps), [](#jdeps), [the tool](#jdeps), [the appendix](#changes) and [below](#later).

                ## Later
                """);
        write("docs/b.md", "---\nid: jdeps\nchapter: \"1.1\"\ntitle: jdeps\n---\n# jdeps\n\nBack to [#](#intro).\n");
        write("docs/c.md", "---\nid: changes\nappendix: A\ntitle: Changes\n---\n# Changes\n");
        write("manifest.yml", "sources: [docs/*.md]\nsequence: " + sequence + "\n");
        return SiteBuilder.build(Manifest.load(dir.resolve("manifest.yml")));
    }

    @Test
    void linksNameAPageByIdAndCanShowItsNumberOrTitle() throws IOException {
        SiteBuilder.Build b = build("[{ files: docs/*.md }]");
        String html = b.outputs().getFirst().html();
        assertTrue(html.contains("Chapter <a href=\"#docs-b-md\">1.1</a>"), html);
        assertTrue(html.contains("<a href=\"#docs-b-md\">jdeps</a>, <a href=\"#docs-b-md\">the tool</a>"), "no text: the title");
        assertTrue(html.contains("<a href=\"#docs-c-md\">the appendix</a>"), html);
        assertTrue(html.contains("<a href=\"#docs-a-md--later\">below</a>"), "a heading in the same file is still a heading");
        assertTrue(html.contains("Back to <a href=\"#docs-a-md\">Introduction</a>"), "no number: the title");
        assertEquals(List.of(), b.report().warnings());
    }

    @Test
    void aPageTheOutputLeavesOutKeepsTheTextButNotTheLink() throws IOException {
        String html = build("[{ file: docs/a.md }]").outputs().getFirst().html();
        assertTrue(html.contains("See Chapter 1.1, jdeps, the tool, the appendix and"), html);
    }

    @Test
    void linksToIdsThatArentThereAreReported() throws IOException {
        write("docs/d.md", "# D\n\n[Nowhere](#nowhere) and [nor here](#nor-here).\n");
        SiteBuilder.Build b = build("[{ files: docs/*.md }]");
        assertEquals(List.of("site: 2 link(s) to an id that isn't in the document: #nowhere, #nor-here"),
                b.report().warnings());
    }
}
