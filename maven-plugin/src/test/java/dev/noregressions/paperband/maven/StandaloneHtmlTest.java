package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code emitHtml} asset-inlining scan — {@code BookBuild.standaloneForDebug}.
 */
@DisplayName("Standalone emitHtml inlining")
class StandaloneHtmlTest {

    @Test
    void should_inline_a_local_image_as_a_data_uri(@TempDir Path dir) throws IOException {
        asset(dir, "diagrams/gc.png", new byte[] {1, 2, 3});

        String out = BookBuild.standaloneForDebug(
                "<p><img src=\"diagrams/gc.png\" alt=\"GC\"></p>", base(dir));

        assertTrue(out.contains("src=\"data:image/png;base64,AQID\""), out);
        // Everything else on the tag survives: only the value is spliced.
        assertTrue(out.contains("alt=\"GC\""), out);
    }

    @Test
    void should_inline_any_element_not_just_images(@TempDir Path dir) throws IOException {
        // An allow-list of element names would quietly stop inlining whatever
        // it forgot; the scan takes every tag instead.
        asset(dir, "app.js", "console.log(1)".getBytes(StandardCharsets.UTF_8));

        String out = BookBuild.standaloneForDebug("<script src=\"app.js\"></script>", base(dir));

        assertTrue(out.contains("src=\"data:"), out);
        assertFalse(out.contains("src=\"app.js\""), out);
    }

    @Test
    void should_not_rewrite_an_escaped_example_in_a_code_block(@TempDir Path dir) throws IOException {
        // The bug: a book documenting this syntax shows the markup in a fence,
        // which is escaped text by now. A bare src=" scan replaced the example
        // with a base64 blob, so the card taught the wrong thing.
        asset(dir, "diagrams/gc.png", new byte[] {1, 2, 3});
        String html = "<pre><code>&lt;img src=\"diagrams/gc.png\"&gt;</code></pre>";

        String out = BookBuild.standaloneForDebug(html, base(dir));

        assertEquals(html, out);
    }

    @Test
    void should_leave_remote_and_inline_references_alone(@TempDir Path dir) {
        String html = "<img src=\"https://example.dev/x.png\">"
                + "<img src=\"data:image/gif;base64,R0lGOD\">";

        assertEquals(html, BookBuild.standaloneForDebug(html, base(dir)));
    }

    @Test
    void should_stamp_a_base_when_a_local_reference_survives(@TempDir Path dir) {
        // Nothing on disk to inline, so the file needs a base to resolve from.
        String out = BookBuild.standaloneForDebug(
                "<head></head><body><img src=\"missing.png\"></body>", base(dir));

        assertTrue(out.contains("<base href=\""), out);
        assertTrue(out.contains("src=\"missing.png\""), out);
    }

    @Test
    void should_not_stamp_a_base_when_everything_inlined(@TempDir Path dir) throws IOException {
        asset(dir, "gc.png", new byte[] {1, 2, 3});

        String out = BookBuild.standaloneForDebug(
                "<head></head><body><img src=\"gc.png\"></body>", base(dir));

        assertFalse(out.contains("<base href=\""), out);
    }

    @Test
    void should_ignore_a_comment_that_mentions_src(@TempDir Path dir) throws IOException {
        asset(dir, "gc.png", new byte[] {1, 2, 3});
        String html = "<!-- src=\"gc.png\" is the old spelling -->";

        assertEquals(html, BookBuild.standaloneForDebug(html, base(dir)));
    }

    private static String base(Path dir) {
        return dir.toAbsolutePath().toUri().toString();
    }

    private static void asset(Path dir, String rel, byte[] bytes) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
