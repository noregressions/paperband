package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a {@code <book>} element roots when it doesn't say.
 *
 * <p>A {@code <book>} that carries only config must not move the book. It used
 * to drop straight to the module basedir, so adding one to a conventionally
 * laid-out project silently re-rooted it and the walk swept up {@code src/},
 * {@code target/} and everything else as sections.
 */
@DisplayName("<book> root default")
class BookRootDefaultTest {

    /** Set a private field the way Maven's configurator would. */
    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no such field: " + field, e);
        }
    }

    /** A mojo whose basedir() is {@code basedir} — MavenProject derives it from the POM file. */
    private static AbstractPaperbandMojo mojoAt(Path basedir) {
        ScanMojo mojo = new ScanMojo();
        org.apache.maven.project.MavenProject project = new org.apache.maven.project.MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        mojo.project = project;
        return mojo;
    }

    private static BookLayout bookRootedAt(Path root) {
        BookLayout book = new BookLayout();
        set(book, "root", root.toFile());
        return book;
    }

    @Test
    void declared_root_always_wins(@TempDir Path basedir) throws IOException {
        Files.createDirectories(basedir.resolve("src/main/paperband/content"));
        Path elsewhere = Files.createDirectories(basedir.resolve("elsewhere"));
        BookLayout book = bookRootedAt(elsewhere);

        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(elsewhere.toAbsolutePath().normalize(),
                mojoAt(basedir).bookRoot(book, geo).toAbsolutePath().normalize());
    }

    @Test
    void undeclared_root_prefers_the_content_wrapper(@TempDir Path basedir) throws IOException {
        // The content root is the cascade boundary and the root the model
        // reports; the home is passed separately as where the yaml lives.
        // Rooting at home instead would collapse content/ into one section.
        Path content = Files.createDirectories(basedir.resolve("src/main/paperband/content"));

        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(content.toAbsolutePath().normalize(),
                mojoAt(basedir).bookRoot(new BookLayout(), geo).toAbsolutePath().normalize());
    }

    @Test
    void undeclared_root_falls_back_to_home_without_a_content_wrapper(@TempDir Path basedir)
            throws IOException {
        Path home = Files.createDirectories(basedir.resolve("src/main/paperband"));

        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(home.toAbsolutePath().normalize(),
                mojoAt(basedir).bookRoot(new BookLayout(), geo).toAbsolutePath().normalize());
    }

    @Test
    void basedir_only_when_the_convention_finds_nothing(@TempDir Path basedir) {
        // No src/main/paperband at all: an unconventional project keeps the
        // old behaviour rather than being pointed at a directory that isn't there.
        Geography geo = Geography.resolve(basedir, null, null, null);

        assertEquals(basedir.toAbsolutePath().normalize(),
                mojoAt(basedir).bookRoot(new BookLayout(), geo).toAbsolutePath().normalize());
    }

    @Test
    void a_book_root_matches_what_the_same_project_uses_with_no_book_element(@TempDir Path basedir)
            throws IOException {
        // The regression guard: declaring a config-only <book> must not change
        // where the book roots. geo.content() is what BuildMojo/SiteMojo use on
        // the no-<book> path, so the two have to agree.
        Path content = Files.createDirectories(basedir.resolve("src/main/paperband/content"));

        Geography geo = Geography.resolve(basedir, null, null, null);
        BookLayout configOnly = new BookLayout();
        set(configOnly, "title", "A book");

        assertEquals(geo.content(),
                mojoAt(basedir).bookRoot(configOnly, geo).toAbsolutePath().normalize());
        assertEquals(content.toAbsolutePath().normalize(), geo.content());
    }
}
