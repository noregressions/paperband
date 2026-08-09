package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.loader.FileLoader;
import io.pebbletemplates.pebble.loader.Loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve a theme name to a {@link ThemeBundle}.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>If {@code themeDir} is non-null and contains
 *       {@code &lt;name&gt;/manifest.txt}, load from filesystem (lets users
 *       ship custom themes without rebuilding pagewright).</li>
 *   <li>Otherwise look for a classpath bundle at {@code themes/&lt;name&gt;/}.</li>
 * </ol>
 *
 * <p>{@code manifest.txt} format: one stylesheet path per line, relative to
 * the theme directory; blank lines and lines starting with {@code #} are
 * ignored. Listed paths are inlined as CSS in declaration order — place
 * tokens / variables before component overrides.
 */
public final class ThemeResolver {

    private ThemeResolver() {}

    /**
     * Resolve {@code name} to a {@link ThemeBundle}, or return
     * {@link ThemeBundle#NONE} if {@code name} is null/blank.
     *
     * @param name      theme name (matches the directory name)
     * @param themeDir  optional filesystem search root for user themes; may be null
     * @throws IOException              if the theme exists but its manifest can't be read
     * @throws IllegalArgumentException if {@code name} is set but no theme is found
     */
    public static ThemeBundle resolve(String name, Path themeDir) throws IOException {
        if (name == null || name.isBlank()) return ThemeBundle.NONE;
        String n = name.trim();

        // 1. Filesystem theme dir wins, when supplied.
        if (themeDir != null) {
            Path candidate = themeDir.resolve(n);
            if (Files.isDirectory(candidate)) {
                return loadFromFilesystem(n, candidate);
            }
        }

        // 2. Classpath bundle.
        String resBase = "themes/" + n;
        ClassLoader cl = ThemeResolver.class.getClassLoader();
        InputStream manifest = cl.getResourceAsStream(resBase + "/manifest.txt");
        if (manifest == null) {
            throw new IllegalArgumentException(
                    "Theme not found: '" + name + "'. "
                            + "Expected classpath resource themes/" + n + "/manifest.txt "
                            + "or filesystem dir " + (themeDir == null ? "(no --theme-dir set)" : themeDir.resolve(n)));
        }

        List<String> stylePaths;
        try (manifest) {
            stylePaths = readManifest(manifest);
        }

        List<String> styles = new ArrayList<>(stylePaths.size());
        for (String p : stylePaths) {
            try (InputStream in = cl.getResourceAsStream(resBase + "/" + p)) {
                if (in == null) continue;
                styles.add("/* === theme:" + n + " " + p + " === */\n"
                        + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        // Always provide a template loader for classpath themes; if the theme
        // has no templates dir, the loader simply won't find anything and the
        // delegating chain falls through to the bundled defaults.
        ClasspathLoader templateLoader = new ClasspathLoader();
        templateLoader.setPrefix(resBase + "/templates/");
        templateLoader.setSuffix(".html");

        return new ThemeBundle(n, styles, templateLoader);
    }

    private static ThemeBundle loadFromFilesystem(String name, Path dir) throws IOException {
        Path manifestPath = dir.resolve("manifest.txt");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Theme manifest missing: " + manifestPath);
        }

        List<String> stylePaths;
        try (InputStream in = Files.newInputStream(manifestPath)) {
            stylePaths = readManifest(in);
        }

        List<String> styles = new ArrayList<>(stylePaths.size());
        for (String p : stylePaths) {
            Path file = dir.resolve(p);
            if (Files.isRegularFile(file)) {
                styles.add("/* === theme:" + name + " " + p + " === */\n"
                        + Files.readString(file, StandardCharsets.UTF_8));
            }
        }

        Loader<?> templateLoader = null;
        Path templates = dir.resolve("templates");
        if (Files.isDirectory(templates)) {
            // Pebble 4.1+ requires the prefix (an absolute path) at construction
            // time; setPrefix() alone is no longer sufficient (also now rejects
            // non-absolute paths — part of the CVE-2025-1686 traversal fix).
            FileLoader fl = new FileLoader(templates.toAbsolutePath().toString() + "/");
            fl.setSuffix(".html");
            templateLoader = fl;
        }

        return new ThemeBundle(name, styles, templateLoader);
    }

    private static List<String> readManifest(InputStream in) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                out.add(s);
            }
        }
        return out;
    }
}
