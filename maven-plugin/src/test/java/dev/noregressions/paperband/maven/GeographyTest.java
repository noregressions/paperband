package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The POM is the sole authority on where the book's pieces live: explicit
 * parameters win, the convention fills what's unsaid, and a project without
 * {@code src/main/paperband} resolves to an all-null geography so legacy
 * behavior is untouched.
 */
class GeographyTest {

    @Test
    void conventionalLayoutResolvesEverything(@TempDir Path basedir) throws IOException {
        Path home = Files.createDirectories(basedir.resolve("src/main/paperband"));
        Path content = Files.createDirectory(home.resolve("content"));
        Path layouts = Files.createDirectory(home.resolve("layouts"));

        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(home.toAbsolutePath(), geo.home());
        assertEquals(content.toAbsolutePath(), geo.content());
        assertEquals(layouts.toAbsolutePath(), geo.layouts());
    }

    @Test
    void noConvention_allNull_legacyUntouched(@TempDir Path basedir) {
        Geography geo = Geography.resolve(basedir, null, null, null);

        assertNull(geo.home());
        assertNull(geo.content());
        assertNull(geo.layouts());
    }

    @Test
    void explicitParametersWin_evenWhenTheConventionExists(@TempDir Path basedir) throws IOException {
        Files.createDirectories(basedir.resolve("src/main/paperband/content"));
        Path docs = Files.createDirectory(basedir.resolve("docs"));
        Path templates = Files.createDirectory(basedir.resolve("book-templates"));

        Geography geo = Geography.resolve(basedir, null, docs, templates);

        assertEquals(docs.toAbsolutePath(), geo.content(), "explicit <content> beats the convention");
        assertEquals(templates.toAbsolutePath(), geo.layouts());
        assertEquals(basedir.resolve("src/main/paperband").toAbsolutePath(), geo.home(),
                "home still resolves conventionally for the yaml and styles");
    }

    @Test
    void movingHomeMovesTheDefaultsBelowIt(@TempDir Path basedir) throws IOException {
        Path home = Files.createDirectories(basedir.resolve("book"));
        Path content = Files.createDirectory(home.resolve("content"));
        Path layouts = Files.createDirectory(home.resolve("layouts"));
        // The conventional location also exists and must lose:
        Files.createDirectories(basedir.resolve("src/main/paperband/content"));

        Geography geo = Geography.resolve(basedir, home, null, null);

        assertEquals(home.toAbsolutePath(), geo.home());
        assertEquals(content.toAbsolutePath(), geo.content());
        assertEquals(layouts.toAbsolutePath(), geo.layouts());
    }

    @Test
    void homeWithoutContentOrLayouts_leavesThoseUnresolved(@TempDir Path basedir) throws IOException {
        Path home = Files.createDirectories(basedir.resolve("src/main/paperband"));

        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(home.toAbsolutePath(), geo.home(), "a flat home still anchors the yaml");
        assertNull(geo.content(), "no content/: the goal falls back to a legacy walk of home");
        assertNull(geo.layouts(), "no layouts/: the engine derives contentRoot/layouts as before");
    }

    @Test
    void explicitContentIsTakenAsDeclaredEvenIfMissing(@TempDir Path basedir) {
        Path docs = basedir.resolve("does-not-exist");

        Geography geo = Geography.resolve(basedir, null, docs, null);

        assertEquals(docs.toAbsolutePath(), geo.content(),
                "a declared path that's missing should fail loudly downstream, not fall back");
    }
}
