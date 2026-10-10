package dev.noregressions.paperband.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the mojo directly, with its fields set as Maven would from the plugin's XML. */
class BuildMojoTest {

    @TempDir
    Path project;

    private BuildMojo mojo;
    private final List<String> warnings = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mojo = new BuildMojo();
        mojo.basedir = project.toFile();
        mojo.buildDirectory = project.resolve("target").toFile();
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warnings.add(content.toString());
            }

            @Override
            public void info(CharSequence content) {
                infos.add(content.toString());
            }
        });
    }

    private Path write(String name, String content) throws IOException {
        Path p = project.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private static Entry entry(String file, String files, String layout, String where, String sort) {
        Entry e = new Entry();
        e.file = file;
        e.files = files;
        e.layout = layout;
        e.where = where;
        e.sort = sort;
        return e;
    }

    @Test
    void xmlSettingsAloneBuildTheSite() throws Exception {
        write("docs/one.md", "---\nchapter: 2\n---\n# One {{ product }}\n");
        write("docs/two.md", "---\nchapter: 1\n---\n# Two\n");
        write("docs/draft.md", "---\nchapter: 3\ndraft: true\n---\n# Draft\n");
        write("layouts/toc.yml", "boxes: [{ id: toc, select: h1, scope: sequence, as: links }]\n");
        mojo.title = "From XML";
        mojo.sources = List.of("docs/*.md");
        mojo.sequence = List.of(entry(null, null, "toc", null, null),
                entry(null, "docs/*.md", null, "{ draft: { ne: true } }", "page.chapter"));
        mojo.vars = Map.of("product", "Paperband");
        mojo.unused = "ignore";

        mojo.execute();

        Path site = project.resolve("target/paperband/site.html");
        String html = Files.readString(site);
        assertTrue(html.contains("<title>From XML</title>"), html);
        assertTrue(html.contains("<link rel=\"next\" href=\"docs/two.html\">"), "sorted by chapter: " + html);
        assertTrue(html.indexOf("href=\"docs/two.html\">Two") < html.indexOf("href=\"docs/one.html\">One"), html);
        assertFalse(Files.exists(site.resolveSibling("docs/draft.html")), "filtered out by where");
        assertTrue(Files.readString(site.resolveSibling("docs/one.html")).contains("One Paperband</h1>"));
        assertEquals(List.of("Paperband: site (3 page(s)) written to " + site), infos);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void xmlOverridesTheManifest() throws Exception {
        write("site/docs/a.md", "# {{ product }} {{ version }}\n");
        write("site/manifest.yml", "title: Manifest\nvars: { product: Manifest, version: '1' }\nsources: [docs/*.md]\n"
                + "sequence: [{ files: docs/*.md }]\noutput: out/site.html\n");
        mojo.manifest = project.resolve("site/manifest.yml").toFile();
        mojo.title = "Overridden";
        mojo.vars = Map.of("product", "XML");

        mojo.execute();

        String html = Files.readString(project.resolve("site/out/site.html"));
        assertTrue(html.contains("<title>Overridden</title>"), "the manifest's output is kept: " + html);
        assertTrue(html.contains("XML 1</h1>"), "vars merge: " + html);
        assertFalse(Files.exists(project.resolve("target/paperband/site.html")));
    }

    @Test
    void outputDefaultsToTheBuildDirectoryEvenWithAManifestThatDoesntSetOne() throws Exception {
        write("docs/a.md", "# A\n");
        write("manifest.yml", "files: [docs/a.md]\n");
        mojo.manifest = project.resolve("manifest.yml").toFile();
        mojo.execute();
        assertTrue(Files.exists(project.resolve("target/paperband/site.html")));
        assertFalse(Files.exists(project.resolve("site.html")), "nothing written into the source tree");
    }

    @Test
    void filesDebugPagesAndDataFromXml() throws Exception {
        write("a.md", "# {{ data.site.name }}\n");
        write("info/site.yml", "name: Data\n");
        mojo.files = List.of(project.resolve("a.md").toFile());
        mojo.data = List.of(project.resolve("info").toFile());
        mojo.astOutput = project.resolve("target/ast.html").toFile();
        mojo.draftOutput = project.resolve("target/draft.html").toFile();
        mojo.defaultPage = "{ size: A5 }";

        mojo.execute();

        assertTrue(Files.readString(project.resolve("target/paperband/site.html")).contains("Data</h1>"));
        assertTrue(Files.exists(project.resolve("target/ast.html")));
        assertTrue(Files.exists(project.resolve("target/draft.html")));
    }

    @Test
    void configurationMistakesFailTheBuildWithTheirLocation() {
        mojo.sources = List.of("docs/*.md");
        mojo.sequence = List.of(entry(null, "docs/*.md", null, "{ chapter: { gtt: 2 } }", null));
        MojoFailureException e = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(e.getMessage().startsWith("Paperband: pom.xml: sequence[0].where.chapter: unknown operator \"gtt\""),
                e.getMessage());

        mojo.sequence = List.of(entry(null, "docs/*.md", null, "{ unclosed", null));
        e = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(e.getMessage().startsWith("Paperband: pom.xml: sequence[0].where: invalid YAML"), e.getMessage());
    }

    @Test
    void warningsAreLoggedAndCanFailTheBuild() throws Exception {
        write("docs/a.md", "# A\n");
        write("docs/b.md", "# B\n");
        mojo.sources = List.of("docs/*.md");
        mojo.sequence = List.of(entry("docs/a.md", null, null, null, null));

        mojo.execute();
        assertEquals(List.of("Paperband: pom.xml: 1 source(s) not in the sequence or hidden: [docs/b.md]"), warnings);

        mojo.failOnWarnings = true;
        MojoFailureException e = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("Paperband: 1 warning(s), and failOnWarnings is set", e.getMessage());
    }

    @Test
    void buildDateFixesBuildDateForReproducibleOutput() throws Exception {
        write("a.md", "{{ build.date | date(\"yyyy-MM-dd HH:mm\") }}\n");
        mojo.files = List.of(project.resolve("a.md").toFile());

        mojo.buildDate = "2026-10-03T12:00:00Z";
        mojo.execute();
        assertTrue(Files.readString(project.resolve("target/paperband/site.html")).contains("2026-10-03 12:00"));

        mojo.buildDate = "1790000000";
        mojo.execute();
        assertTrue(Files.readString(project.resolve("target/paperband/site.html")).contains("2026-09-21"), "epoch seconds");

        mojo.buildDate = "yesterday";
        MojoFailureException e = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(e.getMessage().contains("buildDate: \"yesterday\" isn't an ISO-8601 instant"), e.getMessage());
    }

    @Test
    void outputsFromXml() throws Exception {
        write("docs/a.md", "# A\n\n:::: {! variants}\n::: {! variant=full}\nLong text.\n:::\n"
                + "::: {! variant=short}\nBrief.\n:::\n::::\n");
        write("layouts/print.yml", "stack: {}\nboxes: [{ id: all, select: rest }]\n");
        mojo.sources = List.of("docs/*.md");
        mojo.sequence = List.of(entry(null, "docs/*.md", null, null, null));
        Output web = new Output();
        web.name = "web";
        web.format = "web";
        web.file = project.resolve("target/web.html").toFile();
        Output print = new Output();
        print.name = "print";
        print.format = "paged-html";
        print.file = project.resolve("target/print.html").toFile();
        print.layout = "print";
        print.page = "{ size: A5 }";
        print.prefer = "short";
        mojo.outputs = List.of(web, print);

        mojo.execute();

        String site = Files.readString(project.resolve("target/web.html"));
        assertTrue(site.contains("<body class=\"web\">") && site.contains("Long text.") && !site.contains("Brief."), site);
        String paged = Files.readString(project.resolve("target/print.html"));
        assertTrue(paged.contains("<body class=\"paged\">") && paged.contains("width: 148mm; height: 210mm")
                && paged.contains("Brief.") && !paged.contains("Long text."), paged);
        assertEquals(List.of("Paperband: output web (1 page(s)) written to " + project.resolve("target/web.html"),
                "Paperband: output print (1 page(s)) written to " + project.resolve("target/print.html")), infos);
        assertFalse(Files.exists(project.resolve("target/paperband/site.html")), "no default output when outputs are given");
    }

    @Test
    void browserIsPassedToTheBuild() throws Exception {
        write("docs/a.md", "# A\n");
        mojo.sources = List.of("docs/*.md");
        mojo.sequence = List.of(entry(null, "docs/*.md", null, null, null));
        assertNull(mojo.config().manifest().browser());
        mojo.browser = "chrome";
        assertEquals("chrome", mojo.config().manifest().browser());
    }

    @Test
    void skip() throws Exception {
        mojo.skip = true;
        mojo.execute();
        assertEquals(List.of("Paperband: skipped"), infos);
        assertFalse(new File(mojo.buildDirectory, "paperband").exists());
    }
}
