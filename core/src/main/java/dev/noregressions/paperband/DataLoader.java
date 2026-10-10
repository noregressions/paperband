package dev.noregressions.paperband;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads YAML and JSON data files for templates, where they appear as {@code data}.
 * <ul>
 *   <li>A file contributes one entry named after it: {@code site.yml} becomes {@code data.site}.</li>
 *   <li>A directory contributes its files the same way, and each subdirectory as a nested map:
 *       {@code data/team/members.json} becomes {@code data.team.members}.</li>
 * </ul>
 * Other files are ignored. Two sources for the same name ({@code site.yml} and {@code site.json},
 * or {@code team.yml} and {@code team/}) are an error.
 */
public final class DataLoader {

    public static final String DEFAULT_DATA = "data";

    private DataLoader() {
    }

    /** @param roots data files and/or directories */
    public static Map<String, Object> load(List<Path> roots) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                loadDirectory(root, data);
            } else if (Files.isRegularFile(root) && isDataFile(root)) {
                put(data, name(root), readFile(root), root);
            } else {
                throw new IOException("not a data file or directory: " + root
                        + " (expected a directory or a .yml, .yaml or .json file)");
            }
        }
        return Collections.unmodifiableMap(data);
    }

    private static void loadDirectory(Path dir, Map<String, Object> into) throws IOException {
        List<Path> entries;
        try (Stream<Path> s = Files.list(dir)) {
            entries = s.sorted().toList();
        }
        for (Path p : entries) {
            if (Files.isDirectory(p)) {
                Map<String, Object> nested = new LinkedHashMap<>();
                loadDirectory(p, nested);
                put(into, p.getFileName().toString(), Collections.unmodifiableMap(nested), p);
            } else if (isDataFile(p)) {
                put(into, name(p), readFile(p), p);
            }
        }
    }

    private static Object readFile(Path file) throws IOException {
        try {
            return FrontMatter.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("invalid data file " + file + ": " + e.getMessage(), e);
        }
    }

    private static void put(Map<String, Object> into, String key, Object value, Path source) throws IOException {
        if (into.containsKey(key)) {
            throw new IOException("data name \"" + key + "\" is defined twice; second definition is " + source);
        }
        into.put(key, value);
    }

    private static boolean isDataFile(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".json");
    }

    private static String name(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.lastIndexOf('.'));
    }
}
