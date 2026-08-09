package dev.noregressions.paperband.config;

import dev.noregressions.paperband.predicate.PredicateEvaluator;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory tree honouring {@code pagewright.yaml} {@code order:} lists,
 * producing an ordered, flat list of markdown card files.
 *
 * <h2>Ordering rules</h2>
 * <p>For each directory:
 * <ol>
 *   <li>If a {@code pagewright.yaml} declares an {@code order:} list, those
 *       entries are emitted first in the given sequence.</li>
 *   <li>Any remaining children on disk are appended in alphabetical order.
 *       A warning is printed to {@code System.err} so authors can spot drift
 *       between the disk and the declared order.</li>
 *   <li>If no {@code pagewright.yaml} exists, or it has no {@code order:},
 *       all children are emitted alphabetically.</li>
 * </ol>
 *
 * <h2>{@code sort:} — frontmatter-driven ordering</h2>
 * <p>Instead of (or in addition to) a hand-kept {@code order:} list, a
 * directory's {@code pagewright.yaml} can declare
 * <pre>
 * sort: [tier, id]
 * </pre>
 * Entries not claimed by {@code order:} are then sorted by those frontmatter
 * fields (in sequence, first field most significant) instead of
 * alphabetically. This keeps e.g. a flat directory of cards grouped by an
 * axis value without listing every card by hand — new cards land in the right
 * place automatically. Details:
 * <ul>
 *   <li>Fields are read from each card's frontmatter — the YAML block of a
 *       {@code .md} card, or the whole document of a {@code .yaml} card
 *       (see {@code CardSchema}). The pseudo-field {@code id} falls back to
 *       the file basename when the card doesn't declare one, mirroring
 *       {@code CardLoader}.</li>
 *   <li>Prefix a field with {@code -} for descending ({@code sort: [-tier, id]}).</li>
 *   <li>Numeric values compare numerically, everything else as strings; a
 *       card missing the field (and subdirectories, which have no
 *       frontmatter) sorts after cards that have it. Ties break on
 *       filename.</li>
 *   <li>{@code sort:} applies to the directory that declares it, not its
 *       subtree — each directory can declare its own. When {@code sort:} is
 *       present the "unlisted entries" warning is suppressed: sorting the
 *       rest is the declared intent.</li>
 * </ul>
 *
 * <h2>Entry resolution</h2>
 * <p>{@code order:} entries are either basenames without extension or maps
 * of the form {@code { id: <name>, where: "<predicate>" }}. They resolve in
 * this order: subdirectory of that name, then {@code <name>.md}. Explicit
 * {@code .md} suffixes are also tolerated. Unresolved entries emit a warning
 * and are skipped.
 *
 * <p>Map-form entries may carry a Pebble {@code where} predicate evaluated
 * against the build target supplied to {@link #BookWalker(String)}. The
 * predicate sees {@code target} as a string variable; if it evaluates false
 * the subtree (or .md file) is skipped entirely. Use this to declare
 * web-only or PDF-only sections, e.g. {@code { id: tech, where: "target == 'web'" }}.
 *
 * <h2>Filters</h2>
 * <p>{@code .md} files are always emitted as cards. {@code .yaml}/{@code .yml}
 * files are emitted as cards too — but only when the book root
 * {@code pagewright.yaml} declares a {@code cardSchema:} (see
 * {@code dev.noregressions.paperband.model.CardSchema}); books that don't opt in see
 * no behaviour change, so stray data yamls in existing books stay invisible.
 * The book root is discovered the same way {@code ConfigLoader} does it: the
 * topmost {@code pagewright.yaml} walking parent-by-parent up from the walk's
 * starting point. Hidden files (starting with {@code .}) and
 * {@code pagewright.yaml}/{@code .yml} config files themselves are always
 * skipped. {@code README.md} is also skipped — it's conventionally a repo
 * readme, not a card.
 */
public final class BookWalker {

    private static final String CONFIG_FILENAME = "pagewright.yaml";

    private final Yaml yaml = new Yaml();
    private final PredicateEvaluator predicates = new PredicateEvaluator();
    private final Map<String, Object> predicateContext;

    /** Set per-walk: does the book root declare a {@code cardSchema:}? */
    private boolean acceptYamlCards;

    /** Walk in target-agnostic mode. Order entries with {@code where:} predicates are always included. */
    public BookWalker() {
        this.predicateContext = Map.of();
    }

    /**
     * Walk with a build target so {@code where:} predicates on order entries
     * can be evaluated.
     *
     * <p>Map-form order entries (e.g. {@code { id: tech, where: "target == 'web'" }})
     * are evaluated against a context exposing {@code target} as the supplied
     * string. Entries whose predicate evaluates false are skipped — the
     * subtree (or .md file) is never walked. Plain-string entries always
     * evaluate true.
     *
     * @param target build target name (e.g. {@code "web"}, {@code "pdf-a4"});
     *               null is treated as the empty string.
     */
    public BookWalker(String target) {
        this.predicateContext = Map.of("target", target == null ? "" : target);
    }

    /**
     * Flatten {@code start} into an ordered list of card files.
     *
     * @param start either a single {@code .md} file (returned as a singleton list)
     *              or a directory to walk recursively
     * @return ordered list of {@code .md} card files
     */
    public List<Path> walk(Path start) {
        if (start == null) return List.of();
        this.acceptYamlCards = bookDeclaresCardSchema(start);
        if (Files.isRegularFile(start)) {
            return isCard(start) ? List.of(start.toAbsolutePath()) : List.of();
        }
        if (!Files.isDirectory(start)) return List.of();

        List<Path> out = new ArrayList<>();
        walkDir(start.toAbsolutePath(), out);
        return out;
    }

    // ---- internals ----

    /**
     * Find the book root the same way {@code ConfigLoader} does — the topmost
     * {@code pagewright.yaml} walking parents up from {@code start} — and
     * report whether it declares a {@code cardSchema:}. That's the opt-in for
     * treating {@code .yaml} files as cards.
     */
    private boolean bookDeclaresCardSchema(Path start) {
        Path dir = start.toAbsolutePath();
        if (!Files.isDirectory(dir)) dir = dir.getParent();
        Path topmost = null;
        while (dir != null) {
            Path config = dir.resolve(CONFIG_FILENAME);
            if (Files.isRegularFile(config)) topmost = config;
            dir = dir.getParent();
        }
        if (topmost == null) return false;
        try {
            return readYaml(topmost).containsKey("cardSchema");
        } catch (ConfigParseException e) {
            // Leave the real error to ConfigLoader, which reports it properly.
            return false;
        }
    }

    private void walkDir(Path dir, List<Path> out) {
        List<Entry> order = readOrder(dir);
        List<SortKey> sort = readSort(dir);
        Set<String> claimed = new LinkedHashSet<>();

        if (order != null) {
            for (Entry e : order) {
                Path entry = resolveEntry(dir, e.name());
                if (entry == null) {
                    System.err.println(
                            "warn: order entry not found in " + dir + ": '" + e.name() + "'");
                    continue;
                }
                // Claim the path before evaluating the predicate so that a
                // predicate-excluded entry doesn't fall through to the
                // alphabetical append at the bottom of this method.
                claimed.add(entry.getFileName().toString());
                if (e.where() != null && !predicates.evaluate(e.where(), predicateContext)) {
                    continue;
                }
                visit(entry, out);
            }
        }

        // Append everything not already claimed: sorted by the declared
        // sort: fields when present, alphabetically otherwise.
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> remaining = stream
                    .filter(this::isContent)
                    .filter(p -> !claimed.contains(p.getFileName().toString()))
                    .sorted(sort != null
                            ? frontmatterComparator(sort)
                            : Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (order != null && sort == null && !remaining.isEmpty()) {
                System.err.println("warn: " + remaining.size()
                        + " unlisted entries in " + dir + " — appending alphabetically: "
                        + remaining.stream().map(p -> p.getFileName().toString()).toList());
            }
            for (Path p : remaining) visit(p, out);
        } catch (IOException e) {
            throw new ConfigParseException("Failed to list directory: " + dir, e);
        }
    }

    // ---- sort: support ----

    /** One parsed {@code sort:} field: name plus direction. */
    private record SortKey(String field, boolean descending) {}

    /** Match a YAML frontmatter block at the very start of a markdown file (same as {@code CardLoader}). */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*(?:\\R|\\z)", Pattern.DOTALL);

    /**
     * Parse the {@code sort:} list from {@code dir/pagewright.yaml}: field
     * names, optionally {@code -}-prefixed for descending. A bare string is
     * tolerated as a single-field list. Returns null when absent.
     */
    private List<SortKey> readSort(Path dir) {
        Path yamlFile = dir.resolve(CONFIG_FILENAME);
        if (!Files.isRegularFile(yamlFile)) return null;
        Object node = readYaml(yamlFile).get("sort");
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
     * Order paths by the declared frontmatter fields, most significant first.
     * Cards missing a field — and directories, which have no frontmatter —
     * sort after cards that have it (regardless of direction); ties break on
     * filename. Frontmatter is read once per path and memoised for the
     * duration of the sort.
     */
    private Comparator<Path> frontmatterComparator(List<SortKey> sort) {
        Map<Path, Map<String, Object>> cache = new java.util.HashMap<>();
        return (a, b) -> {
            Map<String, Object> fmA = cache.computeIfAbsent(a, this::readSortFields);
            Map<String, Object> fmB = cache.computeIfAbsent(b, this::readSortFields);
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
     * Best-effort frontmatter fields for sorting: the YAML block of a
     * {@code .md} card, or the whole document of a {@code .yaml}/{@code .yml}
     * card. Directories and unparseable files yield no fields (they sort
     * last); real parse errors are reported properly later by the card
     * loader, not here.
     */
    private Map<String, Object> readSortFields(Path p) {
        if (!Files.isRegularFile(p) || !isCard(p)) return Map.of();
        String name = p.getFileName().toString();
        try {
            String text = Files.readString(p);
            Object loaded;
            if (name.endsWith(".md")) {
                java.util.regex.Matcher m = FRONTMATTER.matcher(text);
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

    private void visit(Path p, List<Path> out) {
        if (Files.isDirectory(p)) {
            walkDir(p, out);
        } else if (isCard(p)) {
            out.add(p);
        }
    }

    private Path resolveEntry(Path dir, String name) {
        // Try as subdirectory first.
        Path subdir = dir.resolve(name);
        if (Files.isDirectory(subdir)) return subdir;

        // Then as a card file: explicit suffix as-is, else try each card
        // extension in precedence order (.md first, then yaml when enabled).
        if (name.endsWith(".md") || name.endsWith(".yaml") || name.endsWith(".yml")) {
            return Files.isRegularFile(subdir) && isCard(subdir) ? subdir : null;
        }
        List<String> extensions = acceptYamlCards
                ? List.of(".md", ".yaml", ".yml")
                : List.of(".md");
        for (String ext : extensions) {
            Path candidate = dir.resolve(name + ext);
            if (Files.isRegularFile(candidate) && isCard(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Parse the {@code order:} list from {@code dir/pagewright.yaml}.
     *
     * <p>Each list item is either a plain string (the entry name) or a map
     * with {@code id} and an optional {@code where} Pebble predicate. The
     * predicate is evaluated later in {@link #walkDir} against the build
     * target so subtrees can be conditionally included (e.g. web-only
     * sections excluded from PDF builds).
     */
    private List<Entry> readOrder(Path dir) {
        Path yamlFile = dir.resolve(CONFIG_FILENAME);
        if (!Files.isRegularFile(yamlFile)) return null;
        Map<String, Object> data = readYaml(yamlFile);
        Object orderNode = data.get("order");
        if (orderNode instanceof List<?> list && !list.isEmpty()) {
            List<Entry> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                if (item instanceof Map<?, ?> m) {
                    Object id = m.get("id");
                    if (id == null) {
                        System.err.println(
                                "warn: order entry in " + dir + " missing required 'id': " + m);
                        continue;
                    }
                    Object where = m.get("where");
                    out.add(new Entry(id.toString(), where == null ? null : where.toString()));
                } else {
                    out.add(new Entry(item.toString(), null));
                }
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    /** A parsed {@code order:} entry: a name and an optional Pebble {@code where} predicate. */
    private record Entry(String name, String where) {}

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            Object data = yaml.load(r);
            if (data == null) return Map.of();
            if (data instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new ConfigParseException(
                    file + ": top level must be a YAML mapping");
        } catch (IOException e) {
            throw new ConfigParseException("Failed to read " + file, e);
        }
    }

    private boolean isContent(Path p) {
        String name = p.getFileName().toString();
        if (name.startsWith(".")) return false;
        if (name.equals(CONFIG_FILENAME)) return false;
        if (Files.isDirectory(p)) return true;
        return isCard(p);
    }

    private boolean isCard(Path p) {
        String name = p.getFileName().toString();
        if (name.endsWith(".md")) {
            return !name.equalsIgnoreCase("README.md");
        }
        if (acceptYamlCards && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return !lower.equals("pagewright.yaml") && !lower.equals("pagewright.yml");
        }
        return false;
    }
}
