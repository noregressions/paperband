package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Target-scoped theme stylesheets.
 *
 * <p>A theme describes one design across two media. Most of it is shared, but
 * a measure chosen for a 210mm trim and a type scale in points are decisions
 * about paper — and a site that inherits them renders as a book in a browser
 * window, which is exactly what happened before this split existed.
 */
@DisplayName("Theme target layers")
class ThemeTargetLayerTest {

    private static Path theme(Path dir, String manifest, String... files) throws IOException {
        Path t = Files.createDirectories(dir.resolve("mytheme"));
        Files.writeString(t.resolve("manifest.txt"), manifest);
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(t.resolve(files[i]), files[i + 1]);
        }
        return dir;
    }

    private static String joined(List<String> sheets) {
        return String.join("\n", sheets);
    }

    @Test
    void an_unprefixed_manifest_behaves_exactly_as_before(@TempDir Path dir) throws IOException {
        Path root = theme(dir, "theme.css\n", "theme.css", ".shared{}");

        ThemeBundle b = ThemeResolver.resolve("mytheme", root);

        assertTrue(joined(b.stylesheets()).contains(".shared{}"));
        assertTrue(joined(b.stylesheets(Target.PRINT)).contains(".shared{}"));
        assertTrue(joined(b.stylesheets(Target.SITE)).contains(".shared{}"));
    }

    @Test
    void print_sheets_reach_only_paged_output(@TempDir Path dir) throws IOException {
        Path root = theme(dir, "theme.css\nprint: p.css\n",
                "theme.css", ".shared{}", "p.css", ".paper{}");

        ThemeBundle b = ThemeResolver.resolve("mytheme", root);

        assertTrue(joined(b.stylesheets(Target.PRINT)).contains(".paper{}"));
        assertFalse(joined(b.stylesheets(Target.SITE)).contains(".paper{}"),
                "a paper measure must not reach the site");
    }

    @Test
    void site_sheets_reach_only_the_site(@TempDir Path dir) throws IOException {
        Path root = theme(dir, "theme.css\nsite: s.css\n",
                "theme.css", ".shared{}", "s.css", ".web{}");

        ThemeBundle b = ThemeResolver.resolve("mytheme", root);

        assertTrue(joined(b.stylesheets(Target.SITE)).contains(".web{}"));
        assertFalse(joined(b.stylesheets(Target.PRINT)).contains(".web{}"));
    }

    @Test
    void the_target_layer_comes_after_the_shared_one(@TempDir Path dir) throws IOException {
        // So a target layer can correct the shared one rather than having to
        // pre-empt it with higher specificity.
        Path root = theme(dir, "theme.css\nsite: s.css\n",
                "theme.css", ".shared{}", "s.css", ".web{}");

        String css = joined(ThemeResolver.resolve("mytheme", root).stylesheets(Target.SITE));

        assertTrue(css.indexOf(".shared{}") < css.indexOf(".web{}"), css);
    }

    @Test
    void whitespace_around_the_prefix_is_tolerated(@TempDir Path dir) throws IOException {
        Path root = theme(dir, "theme.css\nsite:   s.css\nprint:\tp.css\n",
                "theme.css", ".shared{}", "s.css", ".web{}", "p.css", ".paper{}");

        ThemeBundle b = ThemeResolver.resolve("mytheme", root);

        assertTrue(joined(b.stylesheets(Target.SITE)).contains(".web{}"));
        assertTrue(joined(b.stylesheets(Target.PRINT)).contains(".paper{}"));
    }

    @Test
    void an_unknown_prefix_is_an_error_not_a_filename(@TempDir Path dir) throws IOException {
        // Otherwise "web: theme.css" reads as a file literally called
        // "web: theme.css", is not found, and contributes nothing in silence.
        Path root = theme(dir, "web: s.css\n", "s.css", ".web{}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ThemeResolver.resolve("mytheme", root));

        assertTrue(e.getMessage().contains("unknown target prefix"), e.getMessage());
        assertTrue(e.getMessage().contains("web"), e.getMessage());
    }

    @Test
    void a_null_target_returns_the_shared_sheets_alone(@TempDir Path dir) throws IOException {
        Path root = theme(dir, "theme.css\nsite: s.css\n",
                "theme.css", ".shared{}", "s.css", ".web{}");

        assertFalse(joined(ThemeResolver.resolve("mytheme", root).stylesheets(null))
                .contains(".web{}"));
    }

    @Test
    void the_bundled_noregressions_theme_keeps_its_paper_scale_off_the_web() throws IOException {
        ThemeBundle b = ThemeResolver.resolve("noregressions", null);

        String print = joined(b.stylesheets(Target.PRINT));
        String site = joined(b.stylesheets(Target.SITE));

        assertTrue(print.contains("size-a4"), "point sizes stay with paged output");
        assertFalse(site.contains("size-a4"), "and never reach the site");
        assertTrue(site.contains("paperband-site"), "the site layer targets the site hook");
    }
}
