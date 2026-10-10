package dev.noregressions.paperband.examples;

import dev.noregressions.paperband.AstHtmlRenderer;
import dev.noregressions.paperband.MarkdownPipeline;
import dev.noregressions.paperband.SiteBuilder;
import dev.noregressions.paperband.config.BuildConfig;

import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example-driven test harness for the examples module. Every entry in {@code docs/}
 * is one case:
 * <ul>
 *   <li>a {@code .md} file is built on its own;</li>
 *   <li>a directory with a {@code manifest.yml} is built from that manifest;</li>
 *   <li>any other directory has all its {@code .md} files built together, in name order.</li>
 * </ul>
 * Each case goes through {@link SiteBuilder} with the globals below, this module's
 * {@code templates/}, {@code layouts/} and {@code data/}, and a fixed build date. Into {@code target/ast-examples/} go the final AST ({@code <case>.html}), the draft
 * AST before the post pass ({@code <case>.draft.html}), the laid-out site
 * ({@code <case>.site.html}), and an {@code index.html} linking them all.
 */
class ExamplesTest {

    // Relative to the examples module, which is the working directory when its tests run.
    private static final Path EXAMPLES = Path.of("docs");
    private static final Path TEMPLATES = Path.of("templates");
    private static final Path OUTPUT = Path.of("target/ast-examples");
    private static final Path DATA = Path.of("data");
    private static final Path LAYOUTS = Path.of("layouts");
    private static final Map<String, String> GLOBALS = Map.of("product", "Paperband", "version", "1.0");
    private static final Clock BUILD_TIME = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneId.of("UTC"));

    /** @param manifest the case's manifest, or null to build {@code files} in order */
    record ExampleCase(String name, List<Path> files, Path manifest) {
    }

    @TestFactory
    Stream<DynamicTest> examples() throws IOException {
        Files.createDirectories(OUTPUT);
        List<ExampleCase> cases = findCases();
        writeIndex(cases);
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> run(c)));
    }

    private void run(ExampleCase example) throws IOException {
        // File paths relative to the examples dir (or the case's manifest dir) keep the output stable
        // across machines; this module's templates, layouts and data override the case's own.
        BuildConfig.Builder b = BuildConfig.builder(EXAMPLES).clock(BUILD_TIME).vars(GLOBALS)
                .templates(TEMPLATES.toAbsolutePath()).layouts(LAYOUTS.toAbsolutePath()).data(List.of(DATA.toAbsolutePath()));
        if (example.manifest() != null) {
            b.manifest(example.manifest().toAbsolutePath());
        } else {
            b.files(example.files().stream().map(EXAMPLES::relativize).toList());
        }
        BuildConfig config = b.build();
        SiteBuilder.Build build = SiteBuilder.build(config.manifest(), config.clock());
        MarkdownPipeline.Result result = build.result();
        write(example.name() + ".html", AstHtmlRenderer.render(result.ast()));
        write(example.name() + ".draft.html", AstHtmlRenderer.render(result.draft(), AstHtmlRenderer.DRAFT_TITLE));
        write(example.name() + ".site.html", build.html());

        // TODO: per-case checks (e.g. compare against expected output) go here.
    }

    /**
     * The showcase is built by this project's own Maven plugin (see this module's pom.xml), in
     * generate-resources, before the tests run. This checks what it produced: a contents page
     * plus one page per example. Run the build with Maven, not just this test, to refresh it.
     */
    @Test
    void showcaseIsBuiltByThePlugin() throws IOException {
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        Path showcase = manifest.dir().resolve(manifest.outputs().getFirst().file());
        assertTrue(Files.isRegularFile(showcase),
                showcase + " is missing: it's built by paperband-maven-plugin; run mvn test (or mvn generate-resources)");
        String html = Files.readString(showcase, StandardCharsets.UTF_8);
        long examples;
        try (Stream<Path> files = Files.walk(EXAMPLES)) {
            // Partials are hidden in manifest.yml: they feed other examples but aren't shown themselves.
            examples = files.filter(ExamplesTest::isMarkdown).filter(p -> !p.toString().contains("/partials/")).count();
        }
        assertEquals(examples + 1, html.split("<section class=\"page ", -1).length - 1,
                "a contents page plus one page per example");
        assertFalse(html.contains("data-box=\"result/body\">\n</div>"),
                "no example's body box is empty (a panel of values takes none of the page's content)");
        assertTrue(html.contains("<title>Paperband examples</title>"), "title from manifest.yml");
        assertTrue(html.contains("2026-10-03"), "build.date fixed by project.build.outputTimestamp");
    }

    /** The handbook output: six examples (one continued over three pages) on fixed A4 pages, from the same build as the showcase. */
    @Test
    void handbookIsBuiltAsPagedHtml() throws IOException {
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        Path handbook = manifest.dir().resolve(manifest.outputs().get(1).file());
        assertTrue(Files.isRegularFile(handbook), handbook + " is missing: run mvn test");
        String html = Files.readString(handbook, StandardCharsets.UTF_8);
        assertEquals(8, html.split("<section class=\"page layout-print-page", -1).length - 1);
        assertTrue(html.contains("@page p210x297 { size: 210mm 297mm; margin: 0;"), "A4 sheets");
        assertTrue(html.contains("grid-template-rows: auto minmax(120mm, 1fr) auto;"), "body sized by priority");
        assertTrue(html.contains("Download the archive, check its checksum") && !html.contains("from the project's releases"),
                "the handbook prefers short variants");
        assertTrue(html.contains("<style id=\"paperband-fit\">"), "fitting.md only fits with its layout's remedies");
        assertTrue(html.contains("section[id=\"docs-fitting-md\"] [data-box=\"body\"]"), html);
        assertTrue(html.contains("id=\"docs-long-tutorial-md--2\"") && html.contains("id=\"docs-long-tutorial-md--3\""),
                "the long tutorial continues on two more pages");
    }

    /** The same handbook, printed to PDF in Chromium: one A4 page per sheet. */
    @Test
    void handbookIsPrintedToPdf() throws IOException {
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        Path pdf = manifest.dir().resolve(manifest.outputs().get(2).file());
        assertTrue(Files.isRegularFile(pdf), pdf + " is missing: run mvn test");
        String text = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/MediaBox\\s*\\[\\s*0\\s+0\\s+([\\d.]+)\\s+([\\d.]+)").matcher(text);
        int pages = 0;
        while (m.find()) {
            pages++;
            assertEquals(595.3, Double.parseDouble(m.group(1)), 0.5, "A4 width");
            assertEquals(841.9, Double.parseDouble(m.group(2)), 0.5, "A4 height");
        }
        assertEquals(8, pages);
        assertTrue(text.contains("Inter"), "printed in the bundled font");
    }

    /** The web-site example as a site: a page per file, its contents page beside every page. */
    @Test
    void siteHasSideNavigation() throws IOException {
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        Path index = manifest.dir().resolve(manifest.outputs().get(3).file());
        assertTrue(Files.isRegularFile(index), index + " is missing: run mvn test");
        Path bar = index.resolveSibling("docs/web-site/03-navigation-bar.html");
        String html = Files.readString(bar, StandardCharsets.UTF_8);
        assertTrue(html.contains("<body class=\"web has-nav\">"), html);
        assertTrue(html.contains("<h2>Part 1: Navigation</h2>"), "the contents page's own content: " + html);
        assertTrue(html.contains("<a href=\"03-navigation-bar.html\" aria-current=\"page\">A navigation bar</a>"), html);
        assertFalse(Files.exists(index.resolveSibling("docs/web-site/00-contents.html")), "the contents isn't a page");
        assertTrue(Files.isRegularFile(index.resolveSibling("site.css")));
    }

    /** The same site with a navigation bar: a dropdown per part of the contents. */
    @Test
    void siteTopHasANavigationBar() throws IOException {
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        Path index = manifest.dir().resolve(manifest.outputs().get(4).file());
        assertTrue(Files.isRegularFile(index), index + " is missing: run mvn test");
        String html = Files.readString(index.resolveSibling("docs/web-site/03-navigation-bar.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<body class=\"web has-topnav\">"), html);
        assertTrue(html.contains("<li><a href=\"../../index.html\" title=\"A page per file\">A page per file</a></li>"),
                "a link before the first heading is an entry of its own: " + html);
        assertTrue(html.contains("<li><details class=\"current\"><summary title=\"Part 1: Navigation\">Part 1</summary>"),
                "the current page's part, labelled up to its colon");
        assertTrue(html.contains("<summary title=\"Part 2: Reference\">Part 2</summary>"));
        assertTrue(html.contains("<a href=\"03-navigation-bar.html\" aria-current=\"page\">A navigation bar</a>"));
        assertFalse(html.contains("site-nav-toggle"), "no Contents button: the bar is always shown");
    }

    private static void write(String name, String html) throws IOException {
        Files.writeString(OUTPUT.resolve(name), html, StandardCharsets.UTF_8);
    }

    private static List<ExampleCase> findCases() throws IOException {
        List<ExampleCase> cases = new ArrayList<>();
        for (Path entry : sortedList(EXAMPLES)) {
            String name = entry.getFileName().toString();
            if (Files.isRegularFile(entry.resolve("manifest.yml"))) {
                cases.add(new ExampleCase(name, List.of(entry.resolve("manifest.yml")), entry.resolve("manifest.yml")));
            } else if (Files.isDirectory(entry)) {
                List<Path> files = sortedList(entry).stream().filter(ExamplesTest::isMarkdown).toList();
                if (!files.isEmpty()) {
                    cases.add(new ExampleCase(name, files, null));
                }
            } else if (isMarkdown(entry)) {
                cases.add(new ExampleCase(name.substring(0, name.length() - ".md".length()), List.of(entry), null));
            }
        }
        assertFalse(cases.isEmpty(), "no examples found in " + EXAMPLES.toAbsolutePath());
        return cases;
    }

    private static List<Path> sortedList(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    private static boolean isMarkdown(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md");
    }

    private static void writeIndex(List<ExampleCase> cases) throws IOException {
        // The showcase is built by showcase() into the manifest's output; link to it relative to this index.
        Manifest manifest = Manifest.load(Path.of("manifest.yml"));
        String showcase = OUTPUT.toAbsolutePath().relativize(manifest.dir().resolve(manifest.outputs().getFirst().file()))
                .toString().replace('\\', '/');
        StringBuilder sb = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>AST Examples</title>
                <style>
                  body { font: 14px/1.6 system-ui, sans-serif; margin: 24px 16px; }
                  @media (prefers-color-scheme: dark) { body { background: #1c1917; color: #f5f5f4; } a { color: #60a5fa; } }
                  .files { color: #78716c; font-family: ui-monospace, monospace; font-size: 12px; }
                  .showcase { font-size: 16px; margin-bottom: 24px; }
                </style></head>
                <body>
                <h1>AST Examples</h1>
                """);
        sb.append("<p class=\"showcase\"><a href=\"").append(showcase).append("\">Showcase</a>: every example's markdown"
                + " beside its rendered result</p>\n");
        sb.append("<p>Each example below links to its final AST, its draft AST (after pass 1), and its site built on its own.</p>\n<ul>\n");
        for (ExampleCase c : cases) {
            sb.append("<li><a href=\"").append(c.name()).append(".html\">").append(c.name()).append("</a> (<a href=\"")
                    .append(c.name()).append(".draft.html\">draft</a>, <a href=\"").append(c.name()).append(".site.html\">site</a>) <span class=\"files\">")
                    .append(String.join(", ", c.files().stream().map(f -> EXAMPLES.relativize(f).toString()).toList()))
                    .append("</span></li>\n");
        }
        sb.append("</ul>\n</body>\n</html>\n");
        Files.writeString(OUTPUT.resolve("index.html"), sb.toString(), StandardCharsets.UTF_8);
    }
}
