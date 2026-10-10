package dev.noregressions.paperband.config;

import dev.noregressions.paperband.layout.ConfigException;
import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildConfigTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void settingsAloneMakeAManifest() throws IOException {
        write("docs/a.md", "# A\n");
        Manifest m = BuildConfig.builder(dir).origin("pom.xml")
                .title("Built").sources(List.of("docs/*.md"))
                .sequence(List.of(Map.of("files", "docs/*.md", "where", Map.of("draft", Map.of("ne", true)))))
                .output(Path.of("target/site.html")).templates(Path.of("tpl")).vars(Map.of("product", "TG"))
                .env(List.of("HOME")).defaultLayout("plain").defaultPage(Map.of("size", "A5"))
                .build().manifest();

        assertEquals("Built", m.title());
        assertEquals(dir.resolve("target/site.html"), m.output());
        assertEquals(dir.resolve("tpl"), m.templates());
        assertEquals(Map.of("product", "TG"), m.vars());
        assertEquals(List.of("HOME"), m.env());
        assertEquals("plain", m.defaultLayout());
        assertEquals(148, m.defaultPage().width());
        assertEquals(List.of(Path.of("docs/a.md")), m.resolveSources());
        assertEquals("pom.xml", m.name());
        assertInstanceOf(Manifest.FilesEntry.class, m.sequence().getFirst());
    }

    @Test
    void settingsOverrideTheManifestFile() throws IOException {
        Path manifest = write("site/manifest.yml", """
                title: From file
                output: out/site.html
                vars: { product: File, version: "1" }
                defaults: { layout: chapter, page: { size: A4 } }
                sources: [docs/*.md]
                sequence: [{ files: docs/*.md }]
                """);
        BuildConfig config = BuildConfig.builder(dir).manifest(manifest)
                .title("From settings").vars(Map.of("product", "Settings")).defaultLayout("hero")
                .templates(Path.of("shared/templates"))
                .build();
        Manifest m = config.manifest();

        assertEquals("From settings", m.title(), "single values are replaced");
        assertEquals(dir.resolve("site/out/site.html"), m.output(), "untouched settings stay relative to the manifest");
        assertEquals(Map.of("product", "Settings", "version", "1"), m.vars(), "vars merge key by key");
        assertEquals("hero", m.defaultLayout());
        assertEquals(210, m.defaultPage().width(), "defaults merge key by key");
        assertEquals(dir.resolve("shared/templates"), m.templates(), "paths set here are relative to the base dir, not the manifest");
        assertEquals("manifest.yml", m.name());
    }

    @Test
    void filesReplaceSourcesAndSequence() throws IOException {
        Path manifest = write("manifest.yml", "sources: [docs/*.md]\nhidden: [x.md]\nsequence: [{ files: docs/*.md }]\n");
        Manifest m = BuildConfig.builder(dir).manifest(manifest).files(List.of(Path.of("one.md"), Path.of("two.md")))
                .build().manifest();
        assertEquals(List.of(Path.of("one.md"), Path.of("two.md")), m.resolveSources());
        assertTrue(m.hidden().isEmpty());
        assertEquals(2, m.sequence().size());
    }

    @Test
    void runOptions() throws IOException {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneId.of("UTC"));
        BuildConfig config = BuildConfig.builder(dir).files(List.of(Path.of("a.md"))).clock(clock)
                .astOutput(Path.of("ast.html")).draftOutput(Path.of("d/draft.html")).build();
        assertSame(clock, config.clock());
        assertEquals(dir.resolve("ast.html"), config.astOutput());
        assertEquals(dir.resolve("d/draft.html"), config.draftOutput());
        assertNull(BuildConfig.builder(dir).files(List.of(Path.of("a.md"))).build().astOutput());
    }

    @Test
    void mistakesAreReportedLikeAManifestsWithTheOrigin() {
        ConfigException e = assertThrows(ConfigException.class, () -> BuildConfig.builder(dir).origin("pom.xml").build());
        assertEquals("pom.xml: missing required key \"sources\" (or \"files\")", e.getMessage());

        e = assertThrows(ConfigException.class, () -> BuildConfig.builder(dir).origin("pom.xml").sources(List.of("*.md"))
                .sequence(List.of(Map.of("files", "*.md", "sort", "chapter"))).build());
        assertTrue(e.getMessage().startsWith("pom.xml: sequence[0].sort: sort key \"chapter\" must be"), e.getMessage());

        e = assertThrows(ConfigException.class, () -> BuildConfig.builder(dir).origin("pom.xml").files(List.of(Path.of("a.md")))
                .vars(Map.of("page", "x")).build());
        assertTrue(e.getMessage().startsWith("pom.xml: vars.page: \"page\" is reserved"), e.getMessage());

        e = assertThrows(ConfigException.class, () -> BuildConfig.builder(dir).origin("pom.xml").files(List.of(Path.of("a.md")))
                .set("titel", "typo").build());
        assertTrue(e.getMessage().startsWith("pom.xml: unknown key \"titel\""), e.getMessage());
    }

    @Test
    void runnerWritesTheSiteAndDebugPages() throws IOException {
        write("a.md", "# Hello\n");
        BuildConfig config = BuildConfig.builder(dir).files(List.of(Path.of("a.md"))).output(Path.of("out/site.html"))
                .astOutput(Path.of("out/ast.html")).draftOutput(Path.of("out/draft.html")).build();
        Runner.Outcome outcome = Runner.run(config);

        assertEquals(dir.resolve("out/site.html"), outcome.site());
        assertTrue(Files.readString(outcome.site()).contains(">Hello</h1>"));
        assertTrue(Files.readString(outcome.ast()).contains("Combined Markdown AST"));
        assertTrue(Files.readString(outcome.draft()).contains("Draft Markdown AST"));
        assertTrue(outcome.warnings().isEmpty());
    }

    @Test
    void dataGivenMustExistButTheDefaultIsOptional() throws IOException {
        write("a.md", "{{ data.site.name }}\n");
        assertDoesNotThrow(() -> Runner.run(BuildConfig.builder(dir).files(List.of(Path.of("a.md"))).build()));
        IOException e = assertThrows(IOException.class, () -> Runner.run(BuildConfig.builder(dir)
                .files(List.of(Path.of("a.md"))).data(List.of(Path.of("missing"))).build()));
        assertTrue(e.getMessage().startsWith("not a data file or directory"), e.getMessage());

        write("data/site.yml", "name: Paperband\n");
        Runner.Outcome o = Runner.run(BuildConfig.builder(dir).files(List.of(Path.of("a.md"))).build());
        assertTrue(Files.readString(o.site()).contains("Paperband"), "data/ is used when present");
    }
}
