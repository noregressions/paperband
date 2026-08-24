package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.layout.BuiltInThemes;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Lists the themes {@code <theme>} can name: the built-ins bundled in
 * {@code layout}, plus any found under {@code <themeDir>}.
 *
 * <p>A user theme with the same name as a built-in wins — mirroring what a
 * build would resolve — and its row says so.
 *
 * <pre>
 * mvn paperband:themes
 * mvn paperband:themes -Dpaperband.themeDir=~/paperband-themes
 * </pre>
 */
@Mojo(name = "themes", requiresProject = false, threadSafe = true)
public class ThemesMojo extends AbstractPaperbandMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("themes")) return;

        Path dir = themeDirPath();
        Set<String> userNames = new LinkedHashSet<>();
        if (dir != null) {
            if (!Files.isDirectory(dir)) {
                getLog().warn("<themeDir> is not a directory: " + dir);
            } else {
                try (Stream<Path> children = Files.list(dir)) {
                    children.filter(Files::isDirectory)
                            .filter(p -> Files.isRegularFile(p.resolve("manifest.txt")))
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(userNames::add);
                } catch (IOException e) {
                    getLog().warn("failed to list " + dir + ": " + e.getMessage());
                }
            }
        }

        // Built-ins first, in declared order, then user-only themes.
        List<Row> rows = new ArrayList<>();
        for (String name : BuiltInThemes.NAMES) {
            boolean overridden = userNames.contains(name);
            String source = overridden ? dir.resolve(name) + " (overrides built-in)" : "built-in";
            rows.add(new Row(name, source, countStyles(name, overridden ? dir : null)));
        }
        for (String name : userNames) {
            if (BuiltInThemes.NAMES.contains(name)) continue;      // already shown as an override
            rows.add(new Row(name, dir.resolve(name).toString(), countStyles(name, dir)));
        }

        int nameW = Math.max(4, rows.stream().mapToInt(r -> r.name().length()).max().orElse(4));
        int sourceW = Math.max(6, rows.stream().mapToInt(r -> r.source().length()).max().orElse(6));
        String fmt = "%-" + nameW + "s  %-" + sourceW + "s  %s";
        getLog().info(String.format(fmt, "NAME", "SOURCE", "STYLES"));
        for (Row r : rows) {
            getLog().info(String.format(fmt, r.name(), r.source(),
                    r.styles() == null ? "?" : r.styles().toString()));
        }
    }

    /** Resolve through the same path a build uses, so the count is the one it would inline. */
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
