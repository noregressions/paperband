package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {

    @TempDir
    Path dir;

    private static final Set<String> LAYOUTS = Set.of("default", "chapter", "contents");

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Manifest manifest(String yaml) throws IOException {
        write("manifest.yml", yaml);
        return Manifest.load(dir.resolve("manifest.yml"));
    }

    private void book() throws IOException {
        write("docs/intro.md", "---\ntitle: Intro\n---\n");
        write("docs/chapters/one.md", "---\nchapter: 2\n---\n");
        write("docs/chapters/two.md", "---\nchapter: 1\nlayout: contents\n---\n");
        write("docs/chapters/draft.md", "---\nchapter: 3\ndraft: true\n---\n");
        write("docs/partials/footer.md", "x");
        write("notes.txt", "not markdown");
    }

    private Map<Path, Map<String, Object>> frontMatter(List<Path> sources) throws IOException {
        Map<Path, Map<String, Object>> fm = new HashMap<>();
        for (Path p : sources) {
            fm.put(p, dev.noregressions.paperband.FrontMatter.split(p, Files.readString(dir.resolve(p))).data());
        }
        return fm;
    }

    private ResolvedSequence resolve(Manifest m, RunReport report) throws IOException {
        List<Path> sources = m.resolveSources();
        return SequenceResolver.resolve(m, sources, frontMatter(sources), LAYOUTS, report);
    }

    @Test
    void loadsSettingsRelativeToItsDirectory() throws IOException {
        book();
        Manifest m = manifest("""
                title: Guide
                output: build/out.html
                stylesheets: [styles/a.css]
                sources: [docs/**/*.md]
                defaults:
                  page: { size: A5, orientation: landscape, margin: 1cm }
                  layout: chapter
                sequence:
                  - file: docs/intro.md
                """);

        assertEquals("Guide", m.title());
        assertEquals(dir.resolve("build/out.html"), m.output());
        assertEquals(List.of(dir.resolve("styles/a.css")), m.stylesheets());
        assertEquals(dir.resolve("templates"), m.templates());
        assertEquals(new PageSpec(210, 148, 10), m.defaultPage());
        assertEquals("chapter", m.defaultLayout());
        assertEquals(List.of(Path.of("docs/chapters/draft.md"), Path.of("docs/chapters/one.md"),
                Path.of("docs/chapters/two.md"), Path.of("docs/intro.md"), Path.of("docs/partials/footer.md")),
                m.resolveSources(), "** also matches zero directories; sorted by path");
    }

    @Test
    void resolvesSequenceWithFiltersSortingAndLayoutPrecedence() throws IOException {
        book();
        Manifest m = manifest("""
                sources: [docs/**/*.md]
                hidden: [docs/partials/*.md]
                sequence:
                  - layout: contents
                  - file: docs/intro.md
                  - files: docs/chapters/*.md
                    where: { draft: { ne: true } }
                    sort: page.chapter
                """);
        RunReport report = new RunReport();
        ResolvedSequence seq = resolve(m, report);

        assertEquals(List.of(
                new ResolvedSequence.Item("manifest.yml: sequence[0]", null, "contents"),
                new ResolvedSequence.Item("manifest.yml: sequence[1]", Path.of("docs/intro.md"), "default"),
                new ResolvedSequence.Item("manifest.yml: sequence[2]", Path.of("docs/chapters/two.md"), "contents"),
                new ResolvedSequence.Item("manifest.yml: sequence[2]", Path.of("docs/chapters/one.md"), "default")),
                seq.items());
        assertEquals(List.of(Path.of("docs/chapters/draft.md")), seq.unused());
        assertEquals(List.of("manifest.yml: 1 source(s) not in the sequence or hidden: [docs/chapters/draft.md]"),
                report.warnings());
    }

    @Test
    void entryLayoutOverridesFrontMatter() throws IOException {
        book();
        Manifest m = manifest("""
                sources: [docs/chapters/two.md]
                sequence: [{ file: docs/chapters/two.md, layout: chapter }]
                """);
        assertEquals("chapter", resolve(m, new RunReport()).items().getFirst().layout());
    }

    @Test
    void frontMatterLayoutDefinitionsGetTheirInlineName() throws IOException {
        write("docs/a.md", "---\nlayout:\n  boxes: [{ id: x, select: rest }]\n---\n");
        Manifest m = manifest("sources: [docs/a.md]\nsequence: [{ file: docs/a.md }]\n");
        List<Path> sources = m.resolveSources();
        ResolvedSequence seq = SequenceResolver.resolve(m, sources, frontMatter(sources),
                java.util.Set.of("default", "inline-docs-a-md"), new RunReport());
        assertEquals("inline-docs-a-md", seq.items().getFirst().layout());

        write("docs/a.md", "---\nlayout: [a, b]\n---\n");
        ConfigException e = assertThrows(ConfigException.class, () -> SequenceResolver.resolve(m, sources,
                frontMatter(sources), LAYOUTS, new RunReport()));
        assertTrue(e.getMessage().contains("must be a layout name or a layout definition"), e.getMessage());
    }

    @Test
    void filesPatternsSkipHiddenSources() throws IOException {
        book();
        Manifest m = manifest("sources: [docs/**/*.md]\nhidden: [docs/partials/*.md]\nunused: ignore\n"
                + "sequence: [{ files: \"docs/**/*.md\" }]\n");
        assertFalse(resolve(m, new RunReport()).files().contains(Path.of("docs/partials/footer.md")));

        Manifest allHidden = manifest("sources: [docs/partials/*.md]\nhidden: [docs/partials/*.md]\n"
                + "sequence: [{ files: \"docs/partials/*.md\" }]\n");
        RunReport report = new RunReport();
        assertTrue(resolve(allHidden, report).items().isEmpty());
        assertEquals(List.of("manifest.yml: sequence[0]: every source matching \"docs/partials/*.md\" is hidden"),
                report.warnings());
    }

    @Test
    void unusedCanBeAnErrorOrIgnored() throws IOException {
        book();
        Manifest strict = manifest("sources: [docs/*.md, docs/partials/*.md]\nunused: error\nsequence: [{ file: docs/intro.md }]\n");
        ConfigException e = assertThrows(ConfigException.class, () -> resolve(strict, new RunReport()));
        assertTrue(e.getMessage().contains("not in the sequence or hidden: [docs/partials/footer.md]"), e.getMessage());

        Manifest quiet = manifest("sources: [docs/*.md, docs/partials/*.md]\nunused: ignore\nsequence: [{ file: docs/intro.md }]\n");
        RunReport report = new RunReport();
        resolve(quiet, report);
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void whereExcludingEverythingIsAWarning() throws IOException {
        book();
        Manifest m = manifest("sources: [docs/chapters/*.md]\nunused: ignore\n"
                + "sequence: [{ files: docs/chapters/*.md, where: { chapter: 99 } }]\n");
        RunReport report = new RunReport();
        assertTrue(resolve(m, report).items().isEmpty());
        assertTrue(report.warnings().getFirst().contains("where filter excluded all 3 file(s)"), report.warnings().toString());
    }

    @Test
    void mistakesAreReportedBeforeAnyProcessing() throws IOException {
        book();
        assertError("sources: [docs/*.md]\nsequence: [{ file: docs/nope.md }]\n",
                "manifest.yml: sequence[0]: docs/nope.md isn't one of the sources");
        assertError("sources: [docs/*.md]\nsequence: [{ files: \"src/*.md\" }]\n",
                "manifest.yml: sequence[0]: \"src/*.md\" matches none of the sources");
        assertError("sources: [docs/*.md]\nsequence: [{ file: docs/intro.md }, { files: \"docs/*.md\" }]\n",
                "manifest.yml: docs/intro.md is in the sequence twice: manifest.yml: sequence[0] and manifest.yml: sequence[1]");
        assertError("sources: [docs/*.md]\nsequence: [{ file: docs/intro.md, layout: fancy }]\n",
                "manifest.yml: sequence[0] (docs/intro.md): unknown layout \"fancy\"; available: [chapter, contents, default]");
        assertError("sources: [src/*.md]\nsequence: [{ layout: contents }]\n",
                "manifest.yml: sources pattern \"src/*.md\" matches no files in " + dir.toAbsolutePath());
        assertError("sources: [docs/*.md]\nhidden: [docs/*.md]\nsequence: [{ file: docs/intro.md }]\n",
                "manifest.yml: docs/intro.md is hidden (\"docs/*.md\") but also in the sequence (manifest.yml: sequence[0])");
        assertError("sources: [docs/*.md]\nhidden: [x/*.md]\nsequence: [{ file: docs/intro.md }]\n",
                "manifest.yml: hidden pattern \"x/*.md\" matches none of the sources");
    }

    @Test
    void structuralMistakesFailWhenLoaded() throws IOException {
        assertLoadError("sources: [a.md]\nsequence: [{ file: a.md }]\ntitel: x\n", "manifest.yml: unknown key \"titel\"");
        assertLoadError("sequence: [{ file: a.md }]\n", "manifest.yml: missing required key \"sources\"");
        assertLoadError("sources: [a.md]\nsequence: [{ file: a.md, files: \"*.md\" }]\n", "use either file or files, not both");
        assertLoadError("sources: [a.md]\nsequence: [{ file: a.md, sort: path }]\n", "where and sort only apply to files: patterns");
        assertLoadError("sources: [a.md]\nsequence: [{ }]\n", "expected file, files, or a layout for a generated page");
        assertLoadError("sources: [a.md]\nunused: maybe\nsequence: [{ file: a.md }]\n", "manifest.yml: unused: \"maybe\" isn't one of [warn, error, ignore]");
        assertLoadError("sources: [a.md]\ndefaults: { page: { size: B7 } }\nsequence: [{ file: a.md }]\n", "unknown size \"B7\"");
        assertLoadError("sources: [a.md]\ndefaults: { page: { margin: 2 } }\nsequence: [{ file: a.md }]\n", "isn't a length");
        assertLoadError("sources: [a.md\n", "manifest.yml: invalid YAML");
    }

    @Test
    void implicitManifestUsesCommandLineFilesInOrder() throws IOException {
        Manifest m = Manifest.implicit(List.of(Path.of("b.md"), Path.of("a.md")));
        assertEquals(List.of(Path.of("b.md"), Path.of("a.md")), m.resolveSources());
        Map<Path, Map<String, Object>> fm = Map.of(Path.of("a.md"), Map.of("layout", "chapter"), Path.of("b.md"), Map.of());
        ResolvedSequence seq = SequenceResolver.resolve(m, m.resolveSources(), fm, LAYOUTS, new RunReport());
        assertEquals(List.of("default", "chapter"), seq.items().stream().map(ResolvedSequence.Item::layout).toList());
    }

    private void assertError(String yaml, String message) throws IOException {
        Manifest m = manifest(yaml);
        ConfigException e = assertThrows(ConfigException.class, () -> resolve(m, new RunReport()));
        assertEquals(message, e.getMessage());
    }

    private void assertLoadError(String yaml, String fragment) throws IOException {
        write("manifest.yml", yaml);
        ConfigException e = assertThrows(ConfigException.class, () -> Manifest.load(dir.resolve("manifest.yml")));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
