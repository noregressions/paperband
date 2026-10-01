package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guide's Template Reference lists the templates and filters there are.
 *
 * <p>The card (guide/src/main/paperband/content/05-rendering/05-template-reference.md)
 * is two hand-written tables a new template or filter can slip past. This test
 * reads them and compares each with the code: the Bundled templates table with
 * the template files the layout and cards modules ship, and the Filters table
 * with the filters the layout module's Pebble extensions register. Either side
 * having one the other doesn't fails. Skipped outside the repository.
 */
@DisplayName("Guide Template Reference")
class GuideTemplateReferenceTest {

    private static final Pattern TEMPLATE = Pattern.compile("`([A-Za-z0-9_/-]+\\.html)`");
    private static final Pattern FILTER_ROW = Pattern.compile("^\\| `([A-Za-z]+)(?:\\(|`)");
    private static final Pattern REGISTERED = Pattern.compile("\"([A-Za-z]+)\", new [A-Z][A-Za-z]*\\(\\)");

    private static Path repo;
    private static String card;

    @BeforeAll
    static void load() throws IOException {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        repo = basedir.getParent();
        Path file = repo.resolve("guide/src/main/paperband/content/05-rendering/05-template-reference.md");
        assumeTrue(Files.isRegularFile(file), "guide not present: " + file);
        card = Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The section under the {@code ##} {@code heading}, up to the next {@code ##} heading. */
    private static String section(String heading) {
        int start = card.indexOf("\n## " + heading + "\n");
        assumeTrue(start >= 0, "no section " + heading);
        int end = card.indexOf("\n## ", start + 1);
        return card.substring(start, end < 0 ? card.length() : end);
    }

    @Test
    @DisplayName("lists every bundled template, and only those")
    void templates() throws IOException {
        Set<String> listed = new TreeSet<>();
        Matcher m = TEMPLATE.matcher(section("Bundled templates"));
        while (m.find()) listed.add(m.group(1));
        Set<String> shipped = new TreeSet<>();
        for (Path root : new Path[] {repo.resolve("layout/src/main/resources/templates"),
                repo.resolve("cards/src/main/resources/templates")}) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.toString().endsWith(".html"))
                        .forEach(f -> shipped.add(root.relativize(f).toString().replace('\\', '/')));
            }
        }
        assertEquals(shipped, listed, "the Bundled templates table against the shipped templates");
    }

    @Test
    @DisplayName("lists every filter the layout registers, and only those")
    void filters() throws IOException {
        Set<String> listed = new TreeSet<>();
        String table = section("Filters");
        int sub = table.indexOf("\n### ");
        // The Filters table only, not the selector and entry tables under it.
        for (String line : (sub < 0 ? table : table.substring(0, sub)).split("\n")) {
            Matcher m = FILTER_ROW.matcher(line);
            if (m.find()) listed.add(m.group(1));
        }
        Set<String> registered = new TreeSet<>();
        try (Stream<Path> files = Files.list(repo.resolve(
                "layout/src/main/java/dev/noregressions/paperband/layout"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("Extension.java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                int filters = src.indexOf("getFilters()");
                if (filters < 0) continue;
                Matcher m = REGISTERED.matcher(src.substring(filters, src.indexOf('}', filters)));
                while (m.find()) registered.add(m.group(1));
            }
        }
        assertEquals(registered, listed, "the Filters table against the filters the layout registers");
    }
}
