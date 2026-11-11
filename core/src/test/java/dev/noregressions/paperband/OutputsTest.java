package dev.noregressions.paperband;

import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.layout.ConfigException;
import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Several outputs from one build: each with its own format, pages and layouts. */
class OutputsTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private void project() throws IOException {
        write("docs/a.md", "---\nlayout: card\n---\n# Alpha\n\nA paragraph.\n");
        write("docs/b.md", "# Beta\n\nB paragraph. {! print}\n");
        write("layouts/card.yml", "boxes:\n  - { id: title, select: h1 }\n  - { id: body, select: rest }\n");
        write("layouts/print.yml", """
                page: { size: A5, margin: 10mm }
                grid: { areas: ["title", "body"] }
                boxes:
                  - { id: title, area: title, select: h1 }
                  - { id: only-pdf, area: body, select: ".print", when: { outputs: [handbook] } }
                """);
        write("layouts/toc.yml", "boxes: [{ id: toc, select: h1, scope: sequence, as: links }]\n");
    }

    private SiteBuilder.Build build(String manifest) throws IOException {
        Path m = write("manifest.yml", manifest);
        return SiteBuilder.build(Manifest.load(m));
    }

    @Test
    void withoutOutputsThereIsOneWebOutputNamedSite() throws IOException {
        project();
        SiteBuilder.Build build = build("sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\n");
        assertEquals(1, build.outputs().size());
        SiteBuilder.Output site = build.outputs().getFirst();
        assertEquals("site", site.name());
        assertEquals(Manifest.Format.WEB, site.format());
        assertEquals(dir.resolve("site.html"), site.file());
        assertTrue(site.html().contains("<body class=\"web\">"));
    }

    @Test
    void eachOutputHasItsFormatPagesAndLayouts() throws IOException {
        project();
        SiteBuilder.Build build = build("""
                sources: [docs/*.md]
                sequence: [{ files: docs/*.md }]
                outputs:
                  - { name: web, format: web, file: out/web.html }
                  - name: handbook
                    format: paged-html
                    file: out/handbook.html
                    layout: print                  # pages from files use print...
                    page: { size: A4 }
                    sequence:
                      - { layout: toc }            # ...but a generated page keeps its own layout
                      - { files: docs/*.md }
                  - { name: renamed, format: web, file: out/renamed.html, layouts: { card: print } }
                """);
        assertEquals(List.of("web", "handbook", "renamed"), build.outputs().stream().map(SiteBuilder.Output::name).toList());

        SiteBuilder.Output web = build.outputs().get(0);
        assertEquals(List.of("card", "default"), web.pages().stream().map(p -> p.layout().name()).toList(),
                "the manifest's sequence and each file's own layout");

        SiteBuilder.Output handbook = build.outputs().get(1);
        assertEquals(dir.resolve("out/handbook.html"), handbook.file());
        assertEquals(List.of("toc", "print", "print"), handbook.pages().stream().map(p -> p.layout().name()).toList());
        assertTrue(handbook.html().contains("<body class=\"paged\">"));
        assertEquals(148, handbook.pages().get(1).size().width(), "the layout's own page wins over the output's");
        assertEquals(210, handbook.pages().getFirst().size().width(), "toc has no page, so the output's A4 applies");
        assertTrue(handbook.html().contains("B paragraph."), "when.outputs: [handbook] holds in this output");
        assertFalse(web.html().contains("data-box=\"only-pdf\""));

        SiteBuilder.Output renamed = build.outputs().get(2);
        assertEquals(List.of("print", "default"), renamed.pages().stream().map(p -> p.layout().name()).toList(),
                "layouts: renames card to print, leaves default alone");
    }

    @Test
    void outputMistakesAreCaughtBeforeTemplating() throws IOException {
        project();
        String base = "sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\n";
        assertBuildError(base + "output: x.html\noutputs: [{ name: a, format: web, file: a.html }]\n",
                "manifest.yml: use either output or outputs");
        assertBuildError(base + "outputs: [{ name: a, format: docx, file: a.docx }]\n",
                "\"docx\" isn't one of [web, paged-html, pdf]");
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html }, { name: a, format: web, file: b.html }]\n",
                "another output is already named \"a\"");
        assertBuildError(base + "outputs: [{ name: 9a, format: web, file: a.html }]\n", "must start with a letter");
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html, layout: x, layouts: { a: b } }]\n",
                "use either layout or layouts, not both");
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html, layout: nowhere }]\n",
                "unknown layout \"nowhere\"");
        assertBuildError(base + "outputs: [{ name: a, format: pdf, file: a.pdf }]\n",
                "manifest.yml: outputs[0]: format pdf needs dev.noregressions.paperband:chromium on the classpath (in a Maven build, add it"
                        + " as a dependency of paperband-maven-plugin)");
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html, sequence: [] }]\n", "sequence is empty");
    }

    private void assertBuildError(String manifest, String fragment) {
        ConfigException e = assertThrows(ConfigException.class, () -> build(manifest));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void runnerWritesEveryOutput() throws IOException {
        project();
        Path m = write("manifest.yml", "sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\noutputs:\n"
                + "  - { name: one, format: web, file: out/one.html }\n"
                + "  - { name: two, format: paged-html, file: out/two.html }\n");
        Runner.Outcome outcome = Runner.run(BuildConfig.builder(dir).manifest(m).build());
        assertEquals(List.of(dir.resolve("out/one.html"), dir.resolve("out/two.html")), outcome.files());
        assertTrue(Files.isRegularFile(dir.resolve("out/two.html")));
        String paged = Files.readString(dir.resolve("out/two.html"));
        assertTrue(paged.contains("src: url(\"paperband-fonts/inter-latin-400-normal.woff2\")"), "paged html loads the bundled fonts");
        assertTrue(paged.contains("--pb-font-body: \"Inter\", sans-serif;") && paged.contains("font: 11pt/1.45 var(--pb-font-body)"));
        for (String f : Fonts.files()) {
            assertTrue(Files.isRegularFile(dir.resolve("out/paperband-fonts").resolve(f)), f + " is copied beside it");
        }
        assertFalse(Files.readString(dir.resolve("out/one.html")).contains("@font-face"), "web output uses the reader's fonts");
    }

    @Test
    void webOutputGivesCopyButtonsToTheLanguagesItNames() throws IOException {
        write("docs/a.md", "# A\n\n```command\nmvn package\n```\n\n```output\nBUILD SUCCESS\n```\n");
        SiteBuilder.Build b = build("""
                sources: [docs/a.md]
                sequence: [{ file: docs/a.md }]
                copy: [command]
                outputs:
                  - { name: web, format: web, file: out/a.html }
                  - { name: paged, format: paged-html, file: out/b.html }
                """);
        String web = b.outputs().get(0).html();
        assertTrue(web.contains("var languages = [\"command\"];"), "the script copies command blocks");
        assertTrue(web.contains("class=\"copy-button\"") || web.contains("copy-button"), web);
        assertFalse(b.outputs().get(1).html().contains("copy-button"), "paged output has no buttons");
        ConfigException e = assertThrows(ConfigException.class, () -> build("""
                sources: [docs/a.md]
                sequence: [{ file: docs/a.md }]
                copy: ["not a language"]
                """));
        assertTrue(e.getMessage().contains("isn't a code fence language"), e.getMessage());
    }

    @Test
    void webOutputIsASiteWithAPagePerFile() throws IOException {
        project();
        write("docs/a.md", "---\nid: alpha\n---\n# Alpha\n\nA paragraph.\n");
        write("docs/c.md", "# Gamma\n\nBack to [alpha](#alpha).\n\n```bash\nls\n```\n");
        Path m = write("manifest.yml", """
                title: Guide
                sources: [docs/*.md]
                copy: [bash]
                sequence: [{ layout: toc }, { files: docs/*.md }]
                outputs:
                  - { name: site, format: web, file: out/site/index.html }
                  - { name: one, format: web, file: out/one.html, single: true }
                """);
        Runner.run(BuildConfig.builder(dir).manifest(m).build());
        Path site = dir.resolve("out/site");

        String index = Files.readString(site.resolve("index.html"));
        assertTrue(index.contains("<link rel=\"stylesheet\" href=\"site.css\">"), index);
        assertTrue(index.contains("href=\"docs/a.html\">Alpha</a>"), "the contents links each page's file: " + index);
        assertTrue(index.contains("<link rel=\"next\" href=\"docs/a.html\">"));
        assertFalse(index.contains("section.page {"), "styles are in site.css");

        String c = Files.readString(site.resolve("docs/c.html"));
        assertTrue(c.contains("<title>Gamma · Guide</title>"), c);
        assertTrue(c.contains("href=\"../site.css\""), "relative to the page");
        assertTrue(c.contains("href=\"a.html\">alpha</a>"), "a page link points at the other file: " + c);
        assertTrue(c.contains("<a class=\"prev\" rel=\"prev\" href=\"b.html\"><span>Previous</span>Beta</a>"), c);
        assertFalse(c.contains("class=\"next\""), "the last page has no next");
        assertTrue(c.contains("<script src=\"../site.js\" defer></script>"));
        assertEquals(1, c.split("<section class=\"page ", -1).length - 1, "one page per file");

        assertTrue(Files.readString(site.resolve("site.css")).contains(".site-pager"));
        assertTrue(Files.readString(site.resolve("site.js")).contains("var languages = [\"bash\"];"));
        assertEquals(4, Files.readString(dir.resolve("out/one.html")).split("<section class=\"page ", -1).length - 1,
                "a single web output keeps every page in one file");
        assertFalse(Files.exists(dir.resolve("out/site.css")), "a single output writes nothing beside it");

        assertBuildError("sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\n"
                + "outputs: [{ name: a, format: paged-html, file: a.html, single: true }]\n", "only a web output can be single");
    }

    @Test
    void webSiteShowsItsContentsAsSideNavigation() throws IOException {
        project();
        write("layouts/contents.yml", "boxes: [{ id: toc, select: h1, scope: sequence, as: links }]\n");
        Path m = write("manifest.yml", """
                title: Guide
                sources: [docs/*.md]
                sequence: [{ layout: contents }, { files: docs/*.md }]
                outputs:
                  - { name: side, format: web, file: side/index.html }
                  - { name: plain, format: web, file: plain/index.html, nav: none }
                  - { name: listed, format: web, file: listed/index.html, contents: nowhere }
                """);
        Runner.Outcome outcome = Runner.run(BuildConfig.builder(dir).manifest(m).build());

        String index = Files.readString(dir.resolve("side/index.html"));
        assertTrue(index.contains("<h1 id=\"docs-a-md--alpha\">Alpha</h1>"), "the contents isn't a page: " + index);
        assertTrue(index.contains("<body class=\"web has-nav\">"));
        assertTrue(index.contains("<nav class=\"site-nav\" id=\"site-nav\" aria-label=\"Contents\">\n<div class=\"layout-contents"),
                "the contents page's own content, as its layout, so its styles apply: " + index);
        assertTrue(index.contains("<a href=\"index.html\" aria-current=\"page\">Alpha</a>"), "the current page is marked");
        assertTrue(index.contains("aria-controls=\"site-nav\""), "a Contents button for narrow screens");
        String b = Files.readString(dir.resolve("side/docs/b.html"));
        assertTrue(b.contains("<a href=\"../index.html\">Alpha</a>"), b);
        assertTrue(b.contains("<a href=\"b.html\" aria-current=\"page\">Beta</a>"), b);
        assertFalse(b.contains(" id=\"contents"), "the navigation's ids aren't repeated");
        assertTrue(Files.readString(dir.resolve("side/site.css")).contains("nav.site-nav"));
        assertTrue(Files.readString(dir.resolve("side/site.js")).contains(".site-nav-toggle"));

        String plain = Files.readString(dir.resolve("plain/index.html"));
        assertFalse(plain.contains("site-nav"), "nav: none has no side navigation");
        assertTrue(plain.contains("data-layout=\"contents\""), "and the contents stays a page");

        String listed = Files.readString(dir.resolve("listed/docs/a.html"));
        assertTrue(listed.contains("<div class=\"site-nav-pages\"><ul><li><a href=\"../index.html\">"), listed);
        assertTrue(outcome.warnings().contains("listed: contents nowhere isn't a page's file or layout, so the"
                + " navigation lists every page"), outcome.warnings().toString());

        String base = "sources: [docs/*.md]\nsequence: [{ files: docs/*.md }]\n";
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html, nav: left }]\n", "isn't one of [side, top, none]");
        assertBuildError(base + "outputs: [{ name: a, format: web, file: a.html, nav: none, contents: x }]\n", "nav is none");
        assertBuildError(base + "outputs: [{ name: a, format: paged-html, file: a.html, nav: side }]\n",
                "only a web output has nav");
    }

    @Test
    void webSiteShowsItsContentsAsANavigationBar() throws IOException {
        write("docs/intro.md", "# Intro\n\nHello.\n");
        write("docs/part1/a.md", "# Alpha\n\nA.\n");
        write("docs/part1/b.md", "# Beta\n\nB, after [alpha](#docs-part1-a-md).\n");
        write("docs/part2/c.md", "# Gamma\n\nC.\n");
        write("docs/contents.md", """
                # Contents

                - [Intro](#docs-intro-md) [0](#docs-intro-md)

                ## Part 1: Getting going

                - [Alpha](#docs-part1-a-md) [0](#docs-part1-a-md)
                - [Beta](#docs-part1-b-md) [0](#docs-part1-b-md)

                ## A part without a short name, though: it has a colon

                - [Gamma](#docs-part2-c-md)
                """);
        write("layouts/contents.yml", "boxes: [{ id: title, select: h1 }, { id: entries, select: rest }]\n");
        Path m = write("manifest.yml", """
                title: Guide
                sources: [docs/**/*.md]
                sequence:
                  - { file: docs/contents.md, layout: contents }
                  - { file: docs/intro.md }
                  - { files: "docs/part*/*.md" }
                outputs:
                  - { name: top, format: web, file: top/index.html, nav: top }
                  - { name: folders, format: web, file: folders/index.html, nav: top, contents: nowhere }
                """);
        Runner.run(BuildConfig.builder(dir).manifest(m).build());

        String index = Files.readString(dir.resolve("top/index.html"));
        assertTrue(index.contains("<h1 id=\"docs-intro-md--intro\">Intro</h1>"), "the contents isn't a page: " + index);
        assertTrue(index.contains("<body class=\"web has-topnav\">"), index);
        assertFalse(index.contains("site-nav-toggle"), "no Contents button: the bar is always there");
        assertTrue(index.contains("<nav class=\"site-topnav\" id=\"site-nav\" aria-label=\"Contents\"><ul>\n"
                + "<li><a href=\"index.html\" title=\"Intro\" aria-current=\"page\">Intro</a></li>\n"
                + "<li><details><summary title=\"Part 1: Getting going\">Part 1</summary><ul>\n"
                + "<li><a href=\"docs/part1/a.html\">Alpha</a></li>\n"
                + "<li><a href=\"docs/part1/b.html\">Beta</a></li>\n</ul></details></li>\n"
                + "<li><details><summary title=\"A part without a short name, though: it has a colon\">"
                + "A part without a short name, though: it has a colon</summary><ul>\n"
                + "<li><a href=\"docs/part2/c.html\">Gamma</a></li>\n</ul></details></li>\n</ul></nav>"), index);
        String b = Files.readString(dir.resolve("top/docs/part1/b.html"));
        assertTrue(b.contains("<li><details class=\"current\"><summary title=\"Part 1: Getting going\">"), b);
        assertTrue(b.contains("<a href=\"b.html\" aria-current=\"page\">Beta</a>"), b);
        assertTrue(b.contains("<a href=\"../../index.html\" title=\"Intro\">Intro</a>"), b);
        assertTrue(Files.readString(dir.resolve("top/site.css")).contains("nav.site-topnav"));
        assertFalse(Files.readString(dir.resolve("top/site.css")).contains("nav.site-nav "), "no side navigation styles");
        assertTrue(Files.readString(dir.resolve("top/site.js")).contains(".site-topnav"));

        // Without a contents page: the pages by folder, a folder of one page as that page.
        String folders = Files.readString(dir.resolve("folders/docs/part2/c.html"));
        assertTrue(folders.contains("<nav class=\"site-topnav\" id=\"site-nav\" aria-label=\"Contents\"><ul>\n"
                + "<li><a href=\"../../index.html\" title=\"Contents\">Contents</a></li>\n"
                + "<li><a href=\"../intro.html\" title=\"Intro\">Intro</a></li>\n"
                + "<li><details><summary title=\"Alpha\">Alpha</summary><ul>\n"
                + "<li><a href=\"../part1/a.html\">Alpha</a></li>\n"
                + "<li><a href=\"../part1/b.html\">Beta</a></li>\n</ul></details></li>\n"
                + "<li><a href=\"c.html\" title=\"Gamma\" aria-current=\"page\">Gamma</a></li>\n</ul></nav>"), folders);
    }
}
