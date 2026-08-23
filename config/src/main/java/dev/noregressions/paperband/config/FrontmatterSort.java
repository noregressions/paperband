package dev.noregressions.paperband.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordering by card frontmatter fields, as declared by a folder's {@code sort:}
 * key or a planned part's {@code <sort>} element.
 *
 * <p>Fields are read from each card's frontmatter — the YAML block of a
 * {@code .md} card, or the whole document of a {@code .yaml} card. The
 * pseudo-field {@code id} falls back to the file basename when the card
 * doesn't declare one, mirroring {@code CardLoader}. Prefix a field with
 * {@code -} for descending. Numeric values compare numerically, everything
 * else as strings; a card missing the field (and subdirectories, which have
 * no frontmatter at all) sorts after cards that have it, regardless of
 * direction. Ties break on filename.
 */
final class FrontmatterSort {

    /** Match a YAML frontmatter block at the very start of a markdown file (same as {@code CardLoader}). */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*(?:\\R|\\z)", Pattern.DOTALL);

    private FrontmatterSort() {}

    /** One parsed sort field: name plus direction. */
    record SortKey(String field, boolean descending) {}

    /**
     * Parse a declared sort list: field names, optionally {@code -}-prefixed
     * for descending. A bare string is tolerated as a single-field list.
     *
     * @param node the {@code sort:} value — a list, a scalar, or null
     * @return the parsed keys, or null when nothing usable was declared
     */
    static List<SortKey> parse(Object node) {
        if (node == null) return null;
        List<?> items = node instanceof List<?> list ? list : List.of(node);
        List<SortKey> out = new ArrayList<>();
        for (Object item : items) {
            if (item == null) continue;
            String s = item.toString().trim();
            if (s.isEmpty()) continue;
            boolean desc = s.startsWith("-");
            String field = desc ? s.substring(1).trim() : s;
            if (!field.isEmpty()) out.add(new SortKey(field, desc));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Order paths by the given frontmatter fields, most significant first.
     * Frontmatter is read once per path and memoised for the duration of the
     * sort, so the comparator is single-use-ish: cheap to build, not safe to
     * share across a mutating file tree.
     *
     * @param sort            the parsed sort keys
     * @param acceptYamlCards whether {@code .yaml} files count as cards
     * @return the comparator
     */
    static Comparator<Path> comparator(List<SortKey> sort, boolean acceptYamlCards) {
        Map<Path, Map<String, Object>> cache = new HashMap<>();
        Yaml yaml = new Yaml();
        return (a, b) -> {
            Map<String, Object> fmA = cache.computeIfAbsent(a, p -> readFields(yaml, p, acceptYamlCards));
            Map<String, Object> fmB = cache.computeIfAbsent(b, p -> readFields(yaml, p, acceptYamlCards));
            for (SortKey key : sort) {
                Object va = sortValue(fmA, key.field(), a);
                Object vb = sortValue(fmB, key.field(), b);
                int c = compareValues(va, vb);
                if (c != 0) return key.descending() && va != null && vb != null ? -c : c;
            }
            return a.getFileName().toString().compareTo(b.getFileName().toString());
        };
    }

    /** Field lookup with the {@code id} pseudo-field falling back to the file basename, mirroring {@code CardLoader}. */
    private static Object sortValue(Map<String, Object> fm, String field, Path p) {
        Object v = fm.get(field);
        if (v == null && field.equals("id") && Files.isRegularFile(p)) {
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return v;
    }

    /** Numeric when both sides are numbers, string otherwise; null (missing field) sorts last. */
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return a.toString().compareTo(b.toString());
    }

    /**
     * Best-effort frontmatter fields for sorting. Directories and unparseable
     * files yield no fields (they sort last); real parse errors are reported
     * properly later by the card loader, not here.
     */
    private static Map<String, Object> readFields(Yaml yaml, Path p, boolean acceptYamlCards) {
        if (!Files.isRegularFile(p) || !CardFiles.isCard(p, acceptYamlCards)) return Map.of();
        String name = p.getFileName().toString();
        try {
            String text = Files.readString(p);
            Object loaded;
            if (name.endsWith(".md")) {
                Matcher m = FRONTMATTER.matcher(text);
                if (!m.find()) return Map.of();
                loaded = yaml.load(m.group(1));
            } else {
                loaded = yaml.load(text);
            }
            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            return Map.of();
        } catch (IOException | RuntimeException e) {
            System.err.println("warn: sort: could not read frontmatter of " + p
                    + " (" + e.getMessage() + ") — sorting it last");
            return Map.of();
        }
    }
}
