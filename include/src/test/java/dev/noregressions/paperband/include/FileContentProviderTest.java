package dev.noregressions.paperband.include;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileContentProviderTest {

    private final FileContentProvider provider = new FileContentProvider();

    @Test
    void wholeFile(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        Path file = writeFile(tmp, "foo.java", """
                public class Foo {}
                """);
        Fragment f = provider.fetch("foo.java", ctx(source, tmp));
        assertEquals("public class Foo {}", f.content().stripTrailing());
        assertEquals("text/x-java", f.mediaType());
        assertEquals("java", f.inferredLanguage().orElseThrow());
    }

    @Test
    void anchorExtraction_javaComments(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "Foo.java", """
                package com.example;
                // ANCHOR: greeting
                public String hello() {
                    return "hi";
                }
                // ANCHOR_END: greeting
                public String goodbye() {
                    return "bye";
                }
                """);
        Fragment f = provider.fetch("Foo.java:greeting", ctx(source, tmp));
        assertEquals(
                "public String hello() {\n    return \"hi\";\n}",
                f.content());
    }

    @Test
    void anchorExtraction_yamlComments(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "config.yml", """
                global:
                  retries: 3
                # ANCHOR: timeouts
                timeout_ms: 5000
                idle_ms: 30000
                # ANCHOR_END: timeouts
                """);
        Fragment f = provider.fetch("config.yml:timeouts", ctx(source, tmp));
        assertEquals("timeout_ms: 5000\nidle_ms: 30000", f.content());
    }

    @Test
    void anchorExtraction_htmlComments(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "page.html", """
                <p>before</p>
                <!-- ANCHOR: card -->
                <div class="card">hi</div>
                <!-- ANCHOR_END: card -->
                <p>after</p>
                """);
        Fragment f = provider.fetch("page.html:card", ctx(source, tmp));
        assertEquals("<div class=\"card\">hi</div>", f.content());
        assertEquals("text/html", f.mediaType());
    }

    @Test
    void anchorWithDedent(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "indented.java", """
                class Outer {
                    // ANCHOR: inner
                    void inner() {
                        System.out.println("hi");
                    }
                    // ANCHOR_END: inner
                }
                """);
        Fragment f = provider.fetch("indented.java:inner", ctx(source, tmp));
        assertEquals(
                "void inner() {\n    System.out.println(\"hi\");\n}",
                f.content());
    }

    @Test
    void lineRange(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "ten.txt", """
                line one
                line two
                line three
                line four
                line five
                """);
        Fragment f = provider.fetch("ten.txt:2:4", ctx(source, tmp));
        assertEquals("line one", "line one"); // sanity
        assertEquals("line two\nline three\nline four", f.content());
    }

    @Test
    void missingFile_throws(@TempDir Path tmp) {
        Path source = writeMarkdown(tmp, "card.md");
        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch("ghost.java", ctx(source, tmp)));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void missingAnchor_throws(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "foo.java", "// no anchors here\n");
        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch("foo.java:nope", ctx(source, tmp)));
        assertTrue(ex.getMessage().contains("anchor 'nope'"));
    }

    @Test
    void unmatchedAnchor_throws(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "foo.java", """
                // ANCHOR: stub
                code goes here
                """);
        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch("foo.java:stub", ctx(source, tmp)));
        assertTrue(ex.getMessage().contains("no matching"));
    }

    @Test
    void customMarkerAttribute(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        writeFile(tmp, "foo.java", """
                // BEGIN: greeting
                String hi = "hi";
                // BEGIN_END: greeting
                """);
        IncludeContext ctx = new IncludeContext(
                source, tmp, Map.of("marker", "BEGIN"), Map.of());
        Fragment f = provider.fetch("foo.java:greeting", ctx);
        assertEquals("String hi = \"hi\";", f.content());
    }

    @Test
    void resolutionFromConfiguredPath(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        Path samples = Files.createDirectory(tmp.resolve("samples"));
        Files.writeString(samples.resolve("pom.xml"), "<project/>\n");

        IncludeContext ctx = new IncludeContext(
                source, tmp, Map.of(),
                Map.of("paths", java.util.List.of("samples")));
        Fragment f = provider.fetch("pom.xml", ctx);
        assertTrue(f.content().contains("<project/>"));
    }

    // ---- containment ----

    @Test
    void traversalOutsideBookRoot_throws(@TempDir Path tmp) throws Exception {
        Path book = Files.createDirectory(tmp.resolve("book"));
        Path source = writeMarkdown(book, "card.md");
        Files.writeString(tmp.resolve("secret.txt"), "s3cret\n");

        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch("../secret.txt", ctx(source, book)));
        assertTrue(ex.getMessage().contains("outside the book root"));
    }

    @Test
    void absolutePathOutsideBookRoot_throws(@TempDir Path tmp) throws Exception {
        Path book = Files.createDirectory(tmp.resolve("book"));
        Path source = writeMarkdown(book, "card.md");
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "s3cret\n");

        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch(secret.toString(), ctx(source, book)));
        assertTrue(ex.getMessage().contains("outside the book root"));
    }

    @Test
    void absolutePathInsideBookRoot_allowed(@TempDir Path tmp) throws Exception {
        Path source = writeMarkdown(tmp, "card.md");
        Path file = writeFile(tmp, "foo.txt", "hello\n");
        Fragment f = provider.fetch(file.toAbsolutePath().toString(), ctx(source, tmp));
        assertEquals("hello", f.content().stripTrailing());
    }

    @Test
    void outsideRoot_allowedByExternalRoot(@TempDir Path tmp) throws Exception {
        Path book = Files.createDirectory(tmp.resolve("book"));
        Path source = writeMarkdown(book, "card.md");
        Path ext = Files.createDirectory(tmp.resolve("shared"));
        Files.writeString(ext.resolve("notes.txt"), "external\n");

        IncludeContext ctx = new IncludeContext(
                source, book, Map.of(),
                Map.of("external_roots", java.util.List.of(ext.toString())));
        Fragment f = provider.fetch("../shared/notes.txt", ctx);
        assertEquals("external", f.content().stripTrailing());
    }

    @Test
    void outsideRoot_allowedByExternalFile(@TempDir Path tmp) throws Exception {
        Path book = Files.createDirectory(tmp.resolve("book"));
        Path source = writeMarkdown(book, "card.md");
        Path allowed = Files.writeString(tmp.resolve("allowed.txt"), "yes\n");
        Path denied  = Files.writeString(tmp.resolve("denied.txt"), "no\n");

        IncludeContext ctx = new IncludeContext(
                source, book, Map.of(),
                Map.of("external_files", java.util.List.of(allowed.toString())));

        // The allow-listed file resolves...
        assertEquals("yes", provider.fetch("../allowed.txt", ctx).content().stripTrailing());
        // ...but a sibling that wasn't listed is still rejected.
        ContentResolutionException ex = assertThrows(
                ContentResolutionException.class,
                () -> provider.fetch("../denied.txt", ctx));
        assertTrue(ex.getMessage().contains("outside the book root"));
    }

    @Test
    void configuredAbsolutePathBase_isAllowedRoot(@TempDir Path tmp) throws Exception {
        Path book = Files.createDirectory(tmp.resolve("book"));
        Path source = writeMarkdown(book, "card.md");
        Path ext = Files.createDirectory(tmp.resolve("external"));
        Files.writeString(ext.resolve("sample.txt"), "from external\n");

        IncludeContext ctx = new IncludeContext(
                source, book, Map.of(),
                Map.of("paths", java.util.List.of(ext.toString())));
        Fragment f = provider.fetch("sample.txt", ctx);
        assertEquals("from external", f.content().stripTrailing());
    }

    // ---- helpers ----

    private static Path writeMarkdown(Path dir, String name) {
        try {
            return Files.writeString(dir.resolve(name), "# Card\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    private static IncludeContext ctx(Path source, Path bookRoot) {
        return new IncludeContext(source, bookRoot, Map.of(), Map.of());
    }
}
