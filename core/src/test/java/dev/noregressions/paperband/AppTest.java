package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    /** Options for files, with every output and directory inside the temp dir. */
    private App.Options options(String output, List<Path> files, Map<String, String> vars, Path draft,
                                List<Path> data, List<String> env) {
        return new App.Options(output, files, vars, dir, draft, data, env, null, dir.resolve("site.html"), null, null);
    }

    private record Output(String out, String err) {
    }

    private static Output run(App.Options options) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        App.run(options, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Output(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String usageError(String... args) {
        return assertThrows(App.UsageException.class, () -> App.parse(args)).getMessage();
    }

    @Test
    void parsesAllOptions() throws Exception {
        Path a = write("a.md", "a");
        Path b = write("b.md", "b");
        App.Options o = App.parse(new String[] {
                "-o", "out.html", "--var", "x=1", "--var", "eq=a=b", "--templates", "tpl",
                "--draft", "draft.html", "--site", "s.html", "--layouts", "lay", a.toString(), b.toString()});

        assertEquals("out.html", o.output());
        assertEquals(List.of(a, b), o.files());
        assertEquals(Map.of("x", "1", "eq", "a=b"), o.vars());
        assertEquals(Path.of("tpl"), o.templates());
        assertEquals(Path.of("draft.html"), o.draft());
        assertEquals(Path.of("s.html"), o.site());
        assertEquals(Path.of("lay"), o.layouts());
        assertNull(o.manifest());
    }

    @Test
    void parsesRepeatableDataAndEnv() throws Exception {
        App.Options o = App.parse(new String[] {
                "--data", "d1", "--data", "d2.yml", "--env", "HOME", "--env", "CI", write("a.md", "a").toString()});
        assertEquals(List.of(Path.of("d1"), Path.of("d2.yml")), o.data());
        assertEquals(List.of("HOME", "CI"), o.env());
    }

    @Test
    void parsesManifest() throws Exception {
        Path m = write("manifest.yml", "x");
        App.Options o = App.parse(new String[] {"--manifest", m.toString()});
        assertEquals(m, o.manifest());
        assertTrue(o.files().isEmpty());
    }

    @Test
    void defaults() throws Exception {
        App.Options o = App.parse(new String[] {write("a.md", "a").toString()});
        assertEquals(App.DEFAULT_OUTPUT, o.output());
        assertNull(o.templates(), "null means the manifest's (or implicit manifest's) default");
        assertNull(o.layouts());
        assertNull(o.site());
        assertNull(o.draft());
        assertTrue(o.vars().isEmpty());
        assertTrue(o.env().isEmpty());
        assertTrue(o.data().isEmpty());
    }

    @Test
    void rejectsBadArguments() throws IOException {
        String a = write("a.md", "a").toString();
        assertEquals("no markdown files given (or use --manifest)", usageError());
        assertEquals("missing value for -o", usageError(a, "-o"));
        assertEquals("missing value for --draft", usageError(a, "--draft"));
        assertEquals("--var expects name=value, got: novalue", usageError("--var", "novalue", a));
        assertEquals("--var expects name=value, got: =x", usageError("--var", "=x", a));
        assertTrue(usageError(dir.resolve("missing.md").toString()).startsWith("not a file: "));
        assertNull(usageError("--help"), "help has no error message");
        assertTrue(usageError("--var", "page=x", a).startsWith("--var name \"page\" is reserved"));
        assertEquals("missing value for --data", usageError(a, "--data"));
        assertEquals("missing value for --env", usageError(a, "--env"));
        assertEquals("give either markdown files or --manifest, not both", usageError("--manifest", a, a));
        assertTrue(usageError("--manifest", dir.resolve("nope.yml").toString()).startsWith("not a file: "));
    }

    @Test
    void writesAstPageSiteAndTextTree() throws IOException {
        Path a = write("a.md", "---\ntitle: T\n---\n# {{ page.title }}\n");
        Path out = dir.resolve("out.html");

        String stdout = run(options(out.toString(), List.of(a), Map.of(), null, List.of(), List.of())).out();

        assertTrue(stdout.contains("Heading h1"), stdout);
        assertTrue(stdout.contains("Text \"T\""), stdout);
        assertTrue(stdout.contains("written to " + out.toAbsolutePath()), stdout);
        assertTrue(stdout.contains("Site (1 page(s)) written to " + dir.resolve("site.html").toAbsolutePath()), stdout);
        assertTrue(Files.readString(out).contains("<!doctype html>"));
        assertTrue(Files.readString(dir.resolve("site.html")).contains("a-md--t\">T</h1>"), "id prefixed with the page id (from the file's full path)");
    }

    @Test
    void dashWritesAstHtmlToStdoutOnly() throws IOException {
        Path a = write("a.md", "# A\n");
        String stdout = run(options("-", List.of(a), Map.of(), null, List.of(), List.of())).out();

        assertTrue(stdout.startsWith("<!doctype html>"), stdout);
        assertFalse(stdout.contains("└──"), "no text tree mixed into the HTML");
    }

    @Test
    void draftOptionWritesThePrePassAst() throws IOException {
        Path a = write("a.md", "pre {{ v }} post [[ v ]]\n");
        Path out = dir.resolve("out.html");
        Path draft = dir.resolve("draft.html");

        run(options(out.toString(), List.of(a), Map.of("v", "V"), draft, List.of(), List.of()));

        assertTrue(Files.readString(draft).contains("pre V post [[ v ]]"));
        assertTrue(Files.readString(out).contains("pre V post V"));
    }

    @Test
    void passesDataAndOnlyTheNamedEnvVariables() throws IOException {
        write("data/site.yml", "name: Paperband\n");
        Path a = write("a.md", "{{ data.site.name }} path:{% if env.PATH %}set{% endif %} home:{{ env.HOME }}\n");
        Path out = dir.resolve("out.html");

        run(options(out.toString(), List.of(a), Map.of(), null, List.of(dir.resolve("data")), List.of("PATH")));

        assertTrue(Files.readString(out).contains("Paperband path:set home:"), "HOME wasn't named, so it's empty");
    }

    @Test
    void noDraftFileUnlessAsked() throws IOException {
        Path a = write("a.md", "# A\n");
        run(options(dir.resolve("out.html").toString(), List.of(a), Map.of(), null, List.of(), List.of()));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("a.md", "out.html", "site.html"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void buildsFromAManifestAndPrintsWarnings() throws IOException {
        write("docs/a.md", "---\ntitle: A\nlayout: hero\n---\n# Alpha\n\nIntro.\n\nMore.\n");
        write("docs/b.md", "# Beta\n");
        write("docs/unused.md", "# Unused\n");
        write("layouts/hero.yml", "boxes:\n  - { id: title, select: h1 }\n");
        Path m = write("manifest.yml", """
                title: Book
                output: out/site.html
                sources: [docs/*.md]
                sequence:
                  - file: docs/a.md
                  - file: docs/b.md
                """);
        App.Options o = new App.Options(dir.resolve("ast.html").toString(), List.of(), Map.of(), null, null,
                List.of(), List.of(), m, null, null, null);

        Output result = run(o);

        String site = Files.readString(dir.resolve("out/site.html"));
        assertTrue(site.contains("<title>Alpha · Book</title>"), site);
        assertTrue(site.contains("<section class=\"page layout-hero\" id=\"docs-a-md\""), site);
        assertTrue(Files.readString(dir.resolve("out/docs/b.html")).contains("<h1 id=\"docs-b-md--beta\">Beta</h1>"),
                "each page has its own file");
        assertFalse(site.contains("Intro."), "hero has no rest box, so the paragraphs are dropped");
        assertTrue(result.err().contains("warning: manifest.yml: 1 source(s) not in the sequence or hidden: [docs/unused.md]"),
                result.err());
        assertTrue(result.err().contains("warning: docs/a.md (layout hero): 2 block(s) not placed by any box"),
                result.err());
    }
}
