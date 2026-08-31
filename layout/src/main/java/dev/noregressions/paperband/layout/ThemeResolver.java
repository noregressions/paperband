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
 *       ship custom themes without rebuilding paperband).</li>
 *   <li>Otherwise look for a classpath bundle at {@code themes/&lt;name&gt;/}.</li>
 * </ol>
 *
 * <p>{@code manifest.txt} format: one stylesheet path per line, relative to
 * the theme directory; blank lines and lines starting with {@code #} are
 * ignored. Listed paths are inlined as CSS in declaration order — place
 * tokens / variables before component overrides.
 *
 * <p>A path may carry a target prefix, naming the output it applies to:
 *
 * <pre>
 * theme.css                 # shared — every target
 * print: theme-print.css    # paged output only
 * site:  theme-site.css     # the static site only
 * </pre>
 *
 * <p>Unprefixed is shared, so a manifest written before target scoping existed
 * behaves exactly as it did. The split is what lets one theme describe both
 * media honestly: a measure chosen for paper and a page-density type scale are
 * {@code print:} concerns, and a site that inherits them renders as a book in a
 * browser window.
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
                            + "or filesystem dir " + (themeDir == null ? "(no <themeDir> set)" : themeDir.resolve(n)));
        }

        Manifest entries;
        try (manifest) {
            entries = readManifest(manifest);
        }

        List<String> styles = new ArrayList<>();
        List<String> print = new ArrayList<>();
        List<String> site = new ArrayList<>();
        for (Entry e : entries.entries()) {
            String body;
            try (InputStream in = cl.getResourceAsStream(resBase + "/" + e.path())) {
                if (in == null) continue;
                body = "/* === theme:" + n + " " + e.path() + " === */\n"
                        + new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            bucket(e, styles, print, site).add(body);
        }

        // Always provide a template loader for classpath themes; if the theme
        // has no templates dir, the loader simply won't find anything and the
        // delegating chain falls through to the bundled defaults.
        ClasspathLoader templateLoader = new ClasspathLoader();
        templateLoader.setPrefix(resBase + "/templates/");
        templateLoader.setSuffix(".html");

        return new ThemeBundle(n, styles, print, site, templateLoader);
    }

    private static ThemeBundle loadFromFilesystem(String name, Path dir) throws IOException {
        Path manifestPath = dir.resolve("manifest.txt");
        if (!Files.isRegularFile(manifestPath)) {
            throw new IOException("Theme manifest missing: " + manifestPath);
        }

        Manifest entries;
        try (InputStream in = Files.newInputStream(manifestPath)) {
            entries = readManifest(in);
        }

        List<String> styles = new ArrayList<>();
        List<String> print = new ArrayList<>();
        List<String> site = new ArrayList<>();
        for (Entry e : entries.entries()) {
            Path file = dir.resolve(e.path());
            if (!Files.isRegularFile(file)) continue;
            bucket(e, styles, print, site).add("/* === theme:" + name + " " + e.path() + " === */\n"
                    + Files.readString(file, StandardCharsets.UTF_8));
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

        return new ThemeBundle(name, styles, print, site, templateLoader);
    }

    /** One manifest line: a stylesheet path and the target it applies to (null = shared). */
    private record Entry(Target target, String path) {}

    /** A parsed manifest. */
    private record Manifest(List<Entry> entries) {}

    private static List<String> bucket(Entry e, List<String> shared, List<String> print,
                                       List<String> site) {
        if (e.target() == Target.PRINT) return print;
        if (e.target() == Target.SITE) return site;
        return shared;
    }

    private static Manifest readManifest(InputStream in) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                out.add(parseEntry(s));
            }
        }
        return new Manifest(out);
    }

    /**
     * Parse one manifest line into its target and path.
     *
     * <p>An unknown prefix is an error rather than a filename: {@code web:
     * theme.css} would otherwise be read as a file literally called
     * {@code web: theme.css}, silently contributing nothing.
     */
    private static Entry parseEntry(String line) {
        int colon = line.indexOf(':');
        if (colon > 0) {
            String prefix = line.substring(0, colon).trim().toLowerCase();
            String rest = line.substring(colon + 1).trim();
            if (!rest.isEmpty() && prefix.chars().allMatch(Character::isLetter)) {
                switch (prefix) {
                    case "print": return new Entry(Target.PRINT, rest);
                    case "site":  return new Entry(Target.SITE, rest);
                    default:
                        throw new IllegalArgumentException("theme manifest: unknown target prefix '"
                                + prefix + ":' in \"" + line + "\" — expected 'print:' or 'site:', "
                                + "or no prefix for a stylesheet shared by both.");
                }
            }
        }
        return new Entry(null, line);
    }
}
