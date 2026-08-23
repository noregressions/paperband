package dev.noregressions.paperband.config;

import dev.noregressions.paperband.predicate.PredicateEvaluator;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks a directory tree honouring the {@code paperband.yaml} sequencing
 * keys, producing an ordered, flat list of markdown card files.
 *
 * <h2>Declaration and discovery</h2>
 * <p>A directory's content can be discovered (whatever is on disk),
 * declared (an explicit list), or a mix of both. Three keys express that, and
 * because they all answer the same question — what does this directory emit,
 * and in what order — exactly one applies per directory, in this precedence:
 *
 * <ol>
 *   <li>{@code parts:} — a list of titled groups of subfolders. Their folders
 *       are emitted in declared order, then anything unclaimed is discovered
 *       and appended alphabetically. The mixed case is the point, so no
 *       "unlisted entries" warning is printed. Book-level structure lives
 *       here; see {@code dev.noregressions.paperband.model.Part}.</li>
 *   <li>{@code include:} — an <em>exclusive</em> list: exactly these entries,
 *       in this order, and nothing else. There is no discovery pass, so a
 *       file added to the folder later stays out of the book until it's
 *       listed. {@code sort:} has nothing left to order and is ignored.</li>
 *   <li>{@code order:} — an <em>additive</em> list: these entries first, then
 *       every remaining child appended (alphabetically, or per {@code sort:}).
 *       A warning names the unlisted leftovers, since drift between disk and
 *       declared order is usually a mistake rather than an intent.</li>
 *   <li>None of the above — everything in the directory, alphabetically.</li>
 * </ol>
 *
 * <p>{@code include:} vs {@code order:} is the whole declaration/discovery
 * dial: the first is a whitelist, the second a preamble. Declaring a losing
 * key alongside a winning one is a config mistake and warns rather than
 * silently merging.
 *
 * <p>Each directory decides independently, so a book root can declare
 * {@code parts:} over its folders while one folder uses {@code include:} to
 * pin an exact card list and its sibling just lets its cards be discovered.
 *
 * <h2>{@code sort:} — frontmatter-driven ordering</h2>
 * <p>Instead of (or in addition to) a hand-kept {@code order:} list, a
 * directory's {@code paperband.yaml} can declare (this has no effect under
 * {@code include:}, which is already an exact ordered list)
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
 * <p>{@code order:} and {@code include:} entries are either basenames without
 * extension or maps of the form {@code { id: <name>, where: "<predicate>" }};
 * a {@code parts:} entry contributes its {@code folders:} names the same way.
 * All resolve relative to the directory that declared them, in this order:
 * subdirectory of that name, then {@code <name>.md}. Explicit {@code .md}
 * suffixes are also tolerated. Unresolved entries emit a warning and are
 * skipped.
 *
 * <p>Map-form entries may carry a Pebble {@code where} predicate evaluated
 * against the build target supplied to {@link #BookWalker(String)}. The
 * predicate sees {@code target} as a string variable; if it evaluates false
 * the subtree (or .md file) is skipped entirely. Use this to declare
 * web-only or PDF-only sections, e.g. {@code { id: tech, where: "target == 'web'" }}.
 * A whole {@code parts:} entry may carry one too, skipping every folder that
 * part claims.
 *
 * <h2>Filters</h2>
 * <p>{@code .md} files are always emitted as cards. {@code .yaml}/{@code .yml}
 * files are emitted as cards too — but only when the book root
 * {@code paperband.yaml} declares a {@code cardSchema:} (see
 * {@code dev.noregressions.paperband.model.CardSchema}); books that don't opt in see
 * no behaviour change, so stray data yamls in existing books stay invisible.
 * The book root is discovered the same way {@code ConfigLoader} does it: the
 * topmost {@code paperband.yaml} walking parent-by-parent up from the walk's
 * starting point. Hidden files (starting with {@code .}) and
 * {@code paperband.yaml}/{@code .yml} config files themselves are always
 * skipped. {@code README.md} is also skipped — it's conventionally a repo
 * readme, not a card.
 */
public final class BookWalker {

    private static final String CONFIG_FILENAME = "paperband.yaml";

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
        this.acceptYamlCards = CardFiles.declaresCardSchema(start);
        if (Files.isRegularFile(start)) {
            return isCard(start) ? List.of(start.toAbsolutePath()) : List.of();
        }
        if (!Files.isDirectory(start)) return List.of();

        List<Path> out = new ArrayList<>();
        walkDir(start.toAbsolutePath(), out);
        return out;
    }

    // ---- internals ----

    private void walkDir(Path dir, List<Path> out) {
        Directive directive = readDirective(dir);
        List<FrontmatterSort.SortKey> sort = readSort(dir);
        Set<String> claimed = new LinkedHashSet<>();

        for (Entry e : directive.entries()) {
            Path entry = resolveEntry(dir, e.name());
            if (entry == null) {
                System.err.println("warn: " + directive.key() + " entry not found in "
                        + dir + ": '" + e.name() + "'");
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

        // include: is exclusive -- the declared list IS the folder's content,
        // so there is no discovery pass and sort: has nothing left to order.
        if (directive.exclusive()) return;

        // Append everything not already claimed: sorted by the declared
        // sort: fields when present, alphabetically otherwise.
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> remaining = stream
                    .filter(this::isContent)
                    .filter(p -> !claimed.contains(p.getFileName().toString()))
                    .sorted(sort != null
                            ? FrontmatterSort.comparator(sort, acceptYamlCards)
                            : Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (directive.warnsOnUnlisted() && sort == null && !remaining.isEmpty()) {
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

    /**
     * Parse the {@code sort:} list from {@code dir/paperband.yaml}. Returns
     * null when absent; see {@link FrontmatterSort} for the semantics.
     */
    private List<FrontmatterSort.SortKey> readSort(Path dir) {
        Path yamlFile = dir.resolve(CONFIG_FILENAME);
        if (!Files.isRegularFile(yamlFile)) return null;
        return FrontmatterSort.parse(readYaml(yamlFile).get("sort"));
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
     * A directory's resolved sequencing directive: which entries it declares,
     * whether that declaration is exhaustive, and whether unlisted entries
     * deserve a warning.
     *
     * @param key             the yaml key this came from, for diagnostics; null when nothing was declared
     * @param entries         declared entries, in emission order
     * @param exclusive       true for {@code include:} — skip the discovery pass entirely
     * @param warnsOnUnlisted true for {@code order:}, where leftovers on disk usually mean drift
     */
    private record Directive(String key, List<Entry> entries,
                             boolean exclusive, boolean warnsOnUnlisted) {
        static final Directive DISCOVER = new Directive(null, List.of(), false, false);
    }

    /**
     * Resolve which sequencing directive applies to {@code dir}, in
     * precedence order: {@code parts:}, then {@code include:}, then
     * {@code order:}, then plain discovery. The three keys answer the same
     * question — what does this directory emit, and in what order — so only
     * the winner applies; a losing key present alongside it is a config
     * mistake worth a warning rather than a silent merge.
     */
    private Directive readDirective(Path dir) {
        Path yamlFile = dir.resolve(CONFIG_FILENAME);
        if (!Files.isRegularFile(yamlFile)) return Directive.DISCOVER;
        Map<String, Object> data = readYaml(yamlFile);

        List<Entry> parts = readParts(dir, data.get("parts"));
        List<Entry> include = readEntries(dir, data.get("include"), "include");
        List<Entry> order = readEntries(dir, data.get("order"), "order");

        if (parts != null) {
            if (include != null) warnIgnored(dir, "include", "parts");
            if (order != null) warnIgnored(dir, "order", "parts");
            // Folders no part claims still get discovered and emitted after
            // the declared ones -- that mix is the point of parts:, so an
            // "unlisted entries" warning would just be noise (same reasoning
            // as sort: suppressing it).
            return new Directive("parts", parts, false, false);
        }
        if (include != null) {
            if (order != null) warnIgnored(dir, "order", "include");
            if (data.get("sort") != null) warnIgnored(dir, "sort", "include");
            return new Directive("include", include, true, false);
        }
        if (order != null) {
            return new Directive("order", order, false, true);
        }
        return Directive.DISCOVER;
    }

    private static void warnIgnored(Path dir, String ignored, String winner) {
        System.err.println("warn: " + dir + " declares both '" + winner + "' and '"
                + ignored + "' — '" + ignored + "' is ignored ('" + winner + "' wins)");
    }

    /**
     * Parse an {@code order:} or {@code include:} list from an already-read
     * folder config.
     *
     * <p>Each list item is either a plain string (the entry name) or a map
     * with {@code id} and an optional {@code where} Pebble predicate. The
     * predicate is evaluated later in {@link #walkDir} against the build
     * target so subtrees can be conditionally included (e.g. web-only
     * sections excluded from PDF builds).
     *
     * @return the parsed entries, or null when the key is absent or empty
     */
    private List<Entry> readEntries(Path dir, Object node, String key) {
        if (!(node instanceof List<?> list) || list.isEmpty()) return null;
        List<Entry> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) continue;
            if (item instanceof Map<?, ?> m) {
                Object id = m.get("id");
                if (id == null) {
                    System.err.println("warn: " + key + " entry in " + dir
                            + " missing required 'id': " + m);
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

    /**
     * Flatten a {@code parts:} declaration into the folder entries it claims,
     * in declared order.
     *
     * <p>The walker only cares about the sequence a {@code parts:} block
     * implies — the titles and ids that make a part a visible group in the
     * output are parsed separately into
     * {@code dev.noregressions.paperband.model.Part} by {@code ConfigLoader},
     * which is also where structural validation lives. Here a part is just
     * its {@code folders:} list, resolved relative to the directory that
     * declared it exactly like an {@code order:} entry.
     *
     * <p>A part may carry a {@code where} predicate; when it evaluates false
     * the whole part's folders are skipped. The predicate is attached to each
     * flattened folder entry rather than evaluated here, so a skipped part's
     * folders are still <em>claimed</em> and don't reappear in the discovery
     * pass — matching how an excluded {@code order:} entry behaves.
     *
     * @return the flattened folder entries, or null when {@code parts:} is absent or empty
     */
    private List<Entry> readParts(Path dir, Object node) {
        if (!(node instanceof List<?> list) || list.isEmpty()) return null;
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object where = m.get("where");
            String predicate = where == null ? null : where.toString();
            Object foldersNode = m.get("folders");
            List<?> folders = foldersNode instanceof List<?> fl ? fl
                    : foldersNode == null ? List.of() : List.of(foldersNode);
            for (Object f : folders) {
                if (f == null || f.toString().isBlank()) continue;
                String name = f.toString().trim();
                if (!seen.add(name)) {
                    System.err.println("warn: folder '" + name + "' listed by more than one part in "
                            + dir + " — emitting it once, in its first part");
                    continue;
                }
                out.add(new Entry(name, predicate));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** A parsed {@code order:}/{@code include:}/{@code parts:} entry: a name and an optional Pebble {@code where} predicate. */
    private record Entry(String name, String where) {}

    private Map<String, Object> readYaml(Path file) {
        return CardFiles.readYaml(yaml, file);
    }

    private boolean isContent(Path p) {
        String name = p.getFileName().toString();
        if (name.startsWith(".")) return false;
        if (name.equals(CONFIG_FILENAME)) return false;
        if (Files.isDirectory(p)) return true;
        return isCard(p);
    }

    private boolean isCard(Path p) {
        return CardFiles.isCard(p, acceptYamlCards);
    }
}
