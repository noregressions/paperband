package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.layout.BuiltInThemes;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Lists the theme bundles available to {@code --theme}. Built-ins ship inside
 * {@code pagewright-layout}; user themes are discovered under {@code --theme-dir}.
 *
 * <p>When a user theme has the same name as a built-in the user theme wins
 * (mirroring {@link ThemeResolver#resolve(String, Path)}), and the row notes
 * it overrides the built-in.
 *
 * <pre>
 * pagewright themes
 * pagewright themes --theme-dir ~/pagewright-themes
 * </pre>
 */
@Command(
        name = "themes",
        mixinStandardHelpOptions = true,
        description = "List the themes available to --theme (built-ins plus any under --theme-dir).")
public final class ThemesCommand implements Callable<Integer> {

    @Option(
            names = {"--theme-dir"},
            description = "Directory of user themes (each in its own subfolder with a manifest.txt).")
    Path themeDir;

    @Override
    public Integer call() {
        // Names supplied by --theme-dir (each must contain a manifest.txt).
        Set<String> userNames = new LinkedHashSet<>();
        if (themeDir != null) {
            if (!Files.isDirectory(themeDir)) {
                System.err.println("warn: --theme-dir is not a directory: " + themeDir);
            } else {
                try (Stream<Path> children = Files.list(themeDir)) {
                    children
                            .filter(Files::isDirectory)
                            .filter(p -> Files.isRegularFile(p.resolve("manifest.txt")))
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(userNames::add);
                } catch (IOException e) {
                    System.err.println("warn: failed to list " + themeDir + ": " + e.getMessage());
                }
            }
        }

        // Row order: built-ins first (in declared order), then user-only themes.
        List<Row> rows = new ArrayList<>();
        for (String name : BuiltInThemes.NAMES) {
            boolean overridden = userNames.contains(name);
            String source = overridden
                    ? themeDir.resolve(name) + " (overrides built-in)"
                    : "built-in";
            // Resolve via the same code path the build uses so we count the same
            // stylesheets the renderer would inline.
            Integer styles = countStyles(name, overridden ? themeDir : null);
            rows.add(new Row(name, source, styles));
        }
        for (String name : userNames) {
            if (BuiltInThemes.NAMES.contains(name)) continue;  // already shown as override
            rows.add(new Row(name, themeDir.resolve(name).toString(), countStyles(name, themeDir)));
        }

        // Width-fitted table.
        int nameW   = Math.max(4, rows.stream().mapToInt(r -> r.name().length()).max().orElse(4));
        int sourceW = Math.max(6, rows.stream().mapToInt(r -> r.source().length()).max().orElse(6));
        String fmt = "%-" + nameW + "s  %-" + sourceW + "s  %s%n";
        System.out.printf(fmt, "NAME", "SOURCE", "STYLES");
        for (Row r : rows) {
            System.out.printf(fmt, r.name(), r.source(),
                    r.styles() == null ? "?" : r.styles().toString());
        }
        return 0;
    }

    /**
     * Resolve {@code name} via {@link ThemeResolver} and return the inlined
     * stylesheet count, or null if resolution fails.
     */
    private static Integer countStyles(String name, Path themeDir) {
        try {
            ThemeBundle bundle = ThemeResolver.resolve(name, themeDir);
            return bundle.stylesheets().size();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private record Row(String name, String source, Integer styles) {}
}
