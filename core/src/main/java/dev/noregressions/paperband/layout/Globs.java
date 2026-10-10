package dev.noregressions.paperband.layout;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Glob patterns over paths relative to a base directory, always written with {@code /}.
 * {@code *} matches within one directory, {@code **} across directories; unlike plain Java
 * globs, {@code docs/**}{@code /*.md} also matches files directly in {@code docs/}.
 */
final class Globs {

    private Globs() {
    }

    static Predicate<Path> matcher(String pattern) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String variant : variants(pattern)) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
        }
        return path -> matchers.stream().anyMatch(m -> m.matches(path));
    }

    /** The pattern plus each version with one or more {@code **}{@code /} removed, so they can match zero directories. */
    private static Set<String> variants(String pattern) {
        Set<String> out = new LinkedHashSet<>();
        out.add(pattern);
        boolean added = true;
        while (added) {
            added = false;
            for (String p : List.copyOf(out)) {
                int i = p.indexOf("**/");
                while (i >= 0) {
                    added |= out.add(p.substring(0, i) + p.substring(i + 3));
                    i = p.indexOf("**/", i + 1);
                }
            }
        }
        return out;
    }

    /** Every regular file under {@code dir}, relative to it, sorted by path. */
    static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(dir::relativize)
                    .sorted((a, b) -> slashed(a).compareTo(slashed(b)))
                    .toList();
        }
    }

    static String slashed(Path p) {
        return p.toString().replace('\\', '/');
    }
}
