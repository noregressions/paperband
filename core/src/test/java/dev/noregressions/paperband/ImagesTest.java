package dev.noregressions.paperband;

import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.fit.Problem;
import dev.noregressions.paperband.layout.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Markdown images: found from the file they're written in, wherever the output goes. */
class ImagesTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private Path root() {
        return dir.toAbsolutePath().normalize();
    }

    @Test
    void aPagedOutputLinksAnImageWhereItIs() throws IOException {
        write("images/cover.svg", "<svg/>");
        Images images = new Images(dir, dir.resolve("target/report"), false);
        assertEquals("../../images/cover.svg", images.src(Path.of("docs/a.md"), "../images/cover.svg"));
        assertEquals(Map.of(), images.copies());
        assertEquals(List.of(), images.missing());
    }

    @Test
    void aWebOutputGetsACopyAtTheImagesPathInTheProject() throws IOException {
        write("images/cover.svg", "<svg/>");
        write("docs/pic one.png", "x");
        Images images = new Images(dir, dir.resolve("target/site"), true);
        assertEquals("images/cover.svg", images.src(Path.of("docs/a.md"), "../images/cover.svg"));
        assertEquals("images/cover.svg#layer", images.src(Path.of("docs/deep/b.md"), "../../images/cover.svg#layer"));
        assertEquals("docs/pic%20one.png", images.src(Path.of("docs/a.md"), "pic%20one.png"));
        assertEquals(Map.of(Path.of("images/cover.svg"), root().resolve("images/cover.svg"),
                Path.of("docs/pic one.png"), root().resolve("docs/pic one.png")), images.copies());
    }

    @Test
    void imagesFromOutsideTheProjectAreCopiedIntoImagesWithoutClashing() throws IOException {
        write("shared/a/logo.png", "a");
        write("shared/b/logo.png", "b");
        write("project/images/logo.png", "c");
        write("project/out/there.png", "d");
        Images images = new Images(dir.resolve("project"), dir.resolve("project/out"), true);
        assertEquals("images/logo.png", images.src(Path.of("a.md"), "images/logo.png"));
        assertEquals("images/logo-2.png", images.src(Path.of("a.md"), "../shared/a/logo.png"));
        assertEquals("images/logo-3.png", images.src(Path.of("a.md"), "../shared/b/logo.png"));
        assertEquals("images/logo-2.png", images.src(Path.of("b.md"), "../shared/a/logo.png"), "the same file, the same copy");
        assertEquals("there.png", images.src(Path.of("a.md"), "out/there.png"), "already in the output: used where it is");
        assertEquals(3, images.copies().size());
    }

    @Test
    void whatIsNotARelativePathIsLeftAsWritten() {
        Images images = new Images(dir, dir.resolve("out"), true);
        for (String src : List.of("https://example.com/a.png", "//example.com/a.png", "data:image/png;base64,AAAA",
                "/abs/a.png", "#frag", "")) {
            assertEquals(src, images.src(Path.of("docs/a.md"), src));
        }
        assertEquals(List.of(), images.missing());
    }

    @Test
    void anImageThatIsNotThereIsLeftAsWrittenAndRemembered() {
        Images images = new Images(dir, dir.resolve("out"), false);
        assertEquals("../images/cover.svg", images.src(Path.of("docs/a.md"), "../images/cover.svg"));
        images.src(Path.of("docs/a.md"), "../images/cover.svg");
        Path looked = root().resolve("images/cover.svg");
        assertEquals(List.of(new Images.Missing(Path.of("docs/a.md"), "../images/cover.svg", looked)), images.missing());
        assertEquals("docs/a.md: image \"../images/cover.svg\" not found (looked in " + looked + ")",
                images.missing().getFirst().message());

        Problem broken = new Problem("p", Path.of("docs/a.md"), "body", Problem.Kind.IMAGE, 0, "../images/cover.svg");
        Problem other = new Problem("p", Path.of("docs/a.md"), "body", Problem.Kind.IMAGE, 0, "https://example.com/a.png");
        List<Problem> explained = images.explain(List.of(broken, other));
        assertEquals(Problem.Kind.IMAGE_NOT_FOUND, explained.get(0).kind());
        assertEquals("page p (docs/a.md), box body: image \"../images/cover.svg\" not found (looked in " + looked + ")",
                explained.get(0).message());
        assertFalse(explained.get(0).fixable());
        assertEquals(other, explained.get(1));
    }

    private static final String MANIFEST = """
            sources: [docs/*.md]
            sequence: [{ files: docs/*.md }]
            outputs: [{ name: site, format: web, file: target/site/index.html%s }]
            """;

    @Test
    void aSiteHasItsOwnCopiesAndEveryPageFindsThem() throws IOException {
        write("images/cover.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        write("docs/a.md", "# A\n\n![Cover](../images/cover.svg)\n");
        write("docs/b.md", "# B\n\n![Cover](../images/cover.svg \"Again\")\n");
        Runner.Outcome outcome = Runner.run(BuildConfig.builder(dir)
                .manifest(write("manifest.yml", MANIFEST.formatted(""))).build());
        assertEquals(List.of(), outcome.warnings());
        Path site = dir.resolve("target/site");
        assertEquals(Files.readString(dir.resolve("images/cover.svg")), Files.readString(site.resolve("images/cover.svg")));
        assertTrue(Files.readString(site.resolve("index.html")).contains("<img src=\"images/cover.svg\" alt=\"Cover\""));
        assertTrue(Files.readString(site.resolve("docs/b.html")).contains("<img src=\"../images/cover.svg\" alt=\"Cover\""));
    }

    @Test
    void aSinglePageHasItsCopiesBesideIt() throws IOException {
        write("docs/pic.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        write("docs/a.md", "# A\n\n![Pic](pic.svg)\n");
        Runner.run(BuildConfig.builder(dir).manifest(write("manifest.yml", MANIFEST.formatted(", single: true"))).build());
        assertTrue(Files.readString(dir.resolve("target/site/index.html")).contains("<img src=\"docs/pic.svg\""));
        assertTrue(Files.isRegularFile(dir.resolve("target/site/docs/pic.svg")));
    }

    @Test
    void aWebOutputWarnsAboutAnImageThatIsNotThere() throws IOException {
        write("docs/a.md", "# A\n\n![Cover](../images/cover.svg)\n");
        SiteBuilder.Build build = SiteBuilder.build(Manifest.load(write("manifest.yml", MANIFEST.formatted(""))));
        assertEquals(List.of("site: docs/a.md: image \"../images/cover.svg\" not found (looked in "
                + root().resolve("images/cover.svg") + ")"), build.report().warnings());
        assertTrue(build.html().contains("<img src=\"../images/cover.svg\""), "left as written");
    }
}
