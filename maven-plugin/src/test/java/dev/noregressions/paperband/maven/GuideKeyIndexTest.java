package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guide's Key Index names only keys that exist.
 *
 * <p>The index (guide/src/main/paperband/content/04-configuration/06-key-index.md)
 * is a hand-written table, and a hand-written table drifts: a key renamed in
 * the code keeps its old row, and a row can be added for a key that was never
 * implemented. This test reads the table and checks each row against the code,
 * by the row's <em>Where</em> column:
 *
 * <ul>
 *   <li><b>POM</b> — the name must be a parameter (or alias) in the plugin's
 *       generated descriptor, {@code target/classes/META-INF/maven/plugin.xml};</li>
 *   <li><b>yaml</b> — the key's last segment ({@code page.size} → {@code size})
 *       must be a string literal in the config, core or layout sources, where
 *       yaml is read (the layout engine reads parts of {@code page:} itself);</li>
 *   <li><b>vars</b>, <b>frontmatter</b>, <b>{@code _section.md}</b> — the key
 *       must be a string literal in some module's main sources, or appear as
 *       {@code vars.<key>} / {@code frontmatter.<key>} in a bundled template.</li>
 * </ul>
 *
 * <p>It proves a key is read, not that the row describes it correctly. Skipped
 * when run outside the repository, where the guide isn't present.
 */
@DisplayName("Guide Key Index")
class GuideKeyIndexTest {

    private static final Pattern ROW = Pattern.compile("^\\| `([^`]+)` \\| ([^|]+) \\|");

    private static Path repo;
    private static List<String[]> rows;
    private static Set<String> configLiterals;
    private static Set<String> allLiterals;
    private static String templates;

    @BeforeAll
    static void load() throws IOException {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        repo = basedir.getParent();
        Path card = repo.resolve("guide/src/main/paperband/content/04-configuration/06-key-index.md");
        assumeTrue(Files.isRegularFile(card), "guide not present: " + card);

        rows = new ArrayList<>();
        for (String line : Files.readAllLines(card, StandardCharsets.UTF_8)) {
            Matcher m = ROW.matcher(line);
            if (m.find()) rows.add(new String[] {m.group(1).trim(), m.group(2).trim()});
        }
        configLiterals = literalsIn(repo.resolve("config/src/main/java"), repo.resolve("core/src/main/java"),
                repo.resolve("layout/src/main/java"));
        allLiterals = literalsIn(mainJavaDirs());
        templates = templatesText();
    }

    @Test
    @DisplayName("has a table to check")
    void hasRows() {
        assertTrue(rows.size() > 50, "expected the key table, found " + rows.size() + " rows");
    }

    @Test
    @DisplayName("is in A–Z order")
    void sorted() {
        List<String> problems = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (sortKey(rows.get(i - 1)[0]).compareTo(sortKey(rows.get(i)[0])) > 0) {
                problems.add(rows.get(i - 1)[0] + " before " + rows.get(i)[0]);
            }
        }
        assertTrue(problems.isEmpty(), "out of order: " + problems);
    }

    @Test
    @DisplayName("names only POM parameters the plugin has")
    void pomParametersExist() throws IOException {
        Path descriptor = repo.resolve("maven-plugin/target/classes/META-INF/maven/plugin.xml");
        assumeTrue(Files.isRegularFile(descriptor), "plugin descriptor not generated yet");
        String xml = Files.readString(descriptor, StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        Matcher params = Pattern.compile("(?s)<parameter>(.*?)</parameter>").matcher(xml);
        while (params.find()) {
            Matcher n = Pattern.compile("<(name|alias)>([^<]+)</\\1>").matcher(params.group(1));
            while (n.find()) names.add(n.group(2).trim());
        }
        List<String> missing = new ArrayList<>();
        for (String[] r : rows) {
            if (!r[1].contains("POM")) continue;
            String name = r[0].replaceAll("[<>]", "");
            if (!names.contains(name)) missing.add(r[0]);
        }
        assertTrue(missing.isEmpty(), "Key Index names POM parameters the plugin doesn't have: " + missing);
    }

    @Test
    @DisplayName("names only yaml keys the config loader reads")
    void yamlKeysAreRead() {
        List<String> missing = new ArrayList<>();
        for (String[] r : rows) {
            if (!r[1].contains("yaml")) continue;
            String leaf = r[0].substring(r[0].lastIndexOf('.') + 1);
            if (!configLiterals.contains(leaf)) missing.add(r[0]);
        }
        assertTrue(missing.isEmpty(), "Key Index names yaml keys nothing in config/core/layout reads: " + missing);
    }

    @Test
    @DisplayName("names only vars, frontmatter and section-body keys the engine reads")
    void varsAndFrontmatterKeysAreRead() {
        List<String> missing = new ArrayList<>();
        for (String[] r : rows) {
            String where = r[1];
            if (!(where.contains("vars") || where.contains("frontmatter") || where.contains("_section.md"))) {
                continue;
            }
            String key = r[0];
            boolean read = allLiterals.contains(key)
                    || templates.contains("vars." + key)
                    || templates.contains("frontmatter." + key);
            if (!read) missing.add(r[0] + " (" + where + ")");
        }
        assertTrue(missing.isEmpty(), "Key Index names keys nothing reads: " + missing);
    }

    /** The same ordering the index is written in: case-insensitive, ignoring {@code <>`_.}. */
    private static String sortKey(String key) {
        return key.replaceAll("[<>`_.]", "").toLowerCase(Locale.ROOT);
    }

    private static Path[] mainJavaDirs() throws IOException {
        try (Stream<Path> modules = Files.list(repo)) {
            return modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toArray(Path[]::new);
        }
    }

    private static final Pattern LITERAL = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"");

    private static Set<String> literalsIn(Path... dirs) throws IOException {
        Set<String> out = new HashSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        Matcher m = LITERAL.matcher(Files.readString(p, StandardCharsets.UTF_8));
                        while (m.find()) out.add(m.group(1));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        return out;
    }

    private static String templatesText() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> modules = Files.list(repo)) {
            for (Path res : modules.map(m -> m.resolve("src/main/resources")).filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.walk(res)) {
                    for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                        sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }
}
