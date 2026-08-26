package dev.noregressions.paperband.config;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One declared glob pattern, as the set of matchers it expands to — shared by
 * {@link BookPlan}'s include/exclude patterns and {@link BookWalker}'s
 * {@code ignore:} lists, so every glob in a book means the same thing.
 *
 * <p>Ant-style {@code **}{@code /} means "zero or more directories", but
 * {@link java.nio.file.FileSystem#getPathMatcher(String)} compiles it to a regex
 * that needs at least one — so plain {@code glob:docs/**}{@code /*.md}
 * would quietly miss {@code docs/overview.md}. Since these patterns are
 * written in POMs and yaml, where the Ant reading is the one every other tool
 * uses, each whole-segment {@code **}{@code /} also expands to nothing and
 * a path matching any variant counts as a match.
 */
record GlobSet(List<PathMatcher> variants) {

    static GlobSet of(String pattern) {
        Set<String> expanded = new LinkedHashSet<>();
        expandZeroDirectories(pattern.trim(), expanded);
        List<PathMatcher> matchers = new ArrayList<>(expanded.size());
        for (String variant : expanded) {
            matchers.add(compile(variant, pattern));
        }
        return new GlobSet(matchers);
    }

    static List<GlobSet> of(List<String> patterns) {
        List<GlobSet> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            if (p != null && !p.isBlank()) out.add(of(p));
        }
        return out;
    }

    boolean matches(Path relative) {
        for (PathMatcher m : variants) {
            if (m.matches(relative)) return true;
        }
        return false;
    }

    /** Collect {@code pattern} plus every variant with a whole-segment {@code **}{@code /} dropped. */
    private static void expandZeroDirectories(String pattern, Set<String> out) {
        if (pattern.isEmpty() || !out.add(pattern)) return;
        for (int i = pattern.indexOf("**/"); i >= 0; i = pattern.indexOf("**/", i + 1)) {
            if (i == 0 || pattern.charAt(i - 1) == '/') {
                expandZeroDirectories(pattern.substring(0, i) + pattern.substring(i + 3), out);
            }
        }
    }

    private static PathMatcher compile(String variant, String declared) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + variant);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new ConfigParseException("Invalid glob pattern: '" + declared
                    + "' (" + e.getMessage() + ")", e);
        }
    }
}
