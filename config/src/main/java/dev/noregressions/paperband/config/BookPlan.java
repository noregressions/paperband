package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.predicate.PredicateEvaluator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves a <em>declared</em> book structure — an ordered list of titled
 * sections, each selecting its cards by glob pattern — into the same flat,
 * ordered card list {@link BookWalker} produces from a directory tree, plus
 * the {@link Section}s that group them.
 *
 * <p>This is the declaration-first counterpart to {@code BookWalker}. The
 * walker infers structure from the disk (folders become sections, filename
 * order becomes reading order) and lets {@code paperband.yaml} files nudge
 * it; a plan states the structure outright and reaches into the tree for the
 * files it wants, wherever they happen to live:
 *
 * <pre>
 * BookPlan.resolve(root, List.of(
 *         new BookPlan.SectionSpec("traces", "Execution Traces", null, null,
 *                 List.of("services/&#42;/TRACE.md"), List.of("services/wip/&#42;&#42;"),
 *                 List.of("tier", "-id"))),
 *         "pdf-a4");
 * </pre>
 *
 * <p>That selects one card out of each service directory and groups all of
 * them under a single "Execution Traces" divider — a shape no directory
 * layout expresses, since the cards' own folders say nothing about the
 * grouping. The Maven plugin's {@code <book>} element is the front end for
 * this; nothing stops another one existing.
 *
 * <h2>Ordering</h2>
 * <p>Emission order is fully determined by the declaration: sections in declared
 * order, then within a section each {@code include} pattern in declared order,
 * then within one pattern by frontmatter {@code sort} fields (see
 * {@link FrontmatterSort}) or, with no sort declared, alphabetically by path
 * relative to the root. Paths sort by their <em>relative path</em>, not their
 * filename, because a pattern like <code>&#42;/TRACE.md</code> matches a run
 * of identically-named files whose enclosing folder is the only thing
 * distinguishing them.
 *
 * <h2>Claiming</h2>
 * <p>A file is emitted once: the first section (and within it, the first
 * pattern) to match it claims it, and later patterns skip it. That mirrors
 * how a yaml {@code sections:} entry claims a folder, and makes overlapping
 * patterns predictable rather than duplicating cards into the book.
 *
 * <h2>Pattern syntax</h2>
 * <p>Patterns are {@code glob:} patterns as
 * {@link java.nio.file.FileSystem#getPathMatcher(String)} understands them, matched
 * against each candidate's path relative to the root — {@code *} stops at a
 * path separator, {@code **} crosses it, {@code {a,b}} alternates,
 * {@code [0-9]} is a character class. One deviation, in favour of the reading
 * every other Maven plugin uses: a whole-segment {@code **}{@code /} matches
 * <em>zero</em> or more directories, so {@code docs/**}{@code /*.md} finds
 * {@code docs/overview.md} as well as {@code docs/api/v2/types.md}.
 *
 * <h2>Filters</h2>
 * <p>Only card files can be matched, by exactly the rules {@code BookWalker}
 * applies (see {@link CardFiles#isCard}): {@code .md} except
 * {@code README.md}, plus {@code .yaml}/{@code .yml} when the book root
 * declares a {@code cardSchema:}. Hidden files and directories (names
 * starting with {@code .}) are never matched, whatever the pattern says.
 */
public final class BookPlan {

    private BookPlan() {}

    /**
     * One declared section of a planned book: its identity, and the patterns
     * that select its cards.
     *
     * <p>A spec with neither {@code id} nor {@code title} is
     * <em>anonymous</em>: its cards are emitted in place but no {@link Section}
     * is produced for them, so they fall back to folder-derived sections
     * exactly as walked cards do. That's the "just glob me some files" case,
     * with no structural claim attached.
     *
     * @param id              section id; when null or blank, derived from {@code title}
     *                        via {@link Section#slug}. Must be filename-safe — it
     *                        becomes {@code <id>.html} on the static site.
     * @param title           human-readable section title, shown on the divider and
     *                        landing page
     * @param landingTemplate optional per-section landing/divider template — a
     *                        built-in preset name or a template path, resolved
     *                        here via {@link NamedTemplates#resolveSectionTemplate}
     * @param where           optional Pebble predicate evaluated against the build
     *                        target; when it evaluates false the whole section is
     *                        skipped, cards and all
     * @param includes        glob patterns selecting this section's cards, relative to
     *                        the root, in emission order. {@code *} stops at a path
     *                        separator, {@code **} crosses it.
     * @param excludes        glob patterns removing files an {@code include} matched
     * @param sort            frontmatter field names to order matches by, most
     *                        significant first, each optionally {@code -}-prefixed
     *                        for descending; null or empty sorts by relative path
     * @param landingPage     whether this section gets a page of its own — the PDF
     *                        divider and the site {@code <id>.html} landing page.
     *                        On by default; false groups and orders the cards
     *                        without fronting them with a page (see
     *                        {@link Section#landingPage()}). Meaningless on an
     *                        anonymous spec, which produces no {@link Section} at all.
     */
    public record SectionSpec(
            String id,
            String title,
            String landingTemplate,
            String where,
            List<String> includes,
            List<String> excludes,
            List<String> sort,
            boolean landingPage
    ) {
        /** Normalises the three pattern lists to immutable, non-null copies. */
        public SectionSpec {
            includes = includes == null ? List.of() : List.copyOf(includes);
            excludes = excludes == null ? List.of() : List.copyOf(excludes);
            sort     = sort     == null ? List.of() : List.copyOf(sort);
        }

        /**
         * Convenience constructor for a spec whose section gets its own page —
         * the default for a declaration that doesn't opt out.
         */
        public SectionSpec(String id, String title, String landingTemplate, String where,
                        List<String> includes, List<String> excludes, List<String> sort) {
            this(id, title, landingTemplate, where, includes, excludes, sort, true);
        }

        /** True when this spec declares no identity, so it contributes cards but no {@link Section}. */
        boolean anonymous() {
            return isBlank(id) && isBlank(title);
        }
    }

    /**
     * A resolved plan: the flat card list to build, and the sections grouping it.
     *
     * @param cards        ordered card files, absolute and normalised
     * @param sections     the sections that claim them, in declared order — each
     *                     carrying its claimed cards in {@link Section#cards()}.
     *                     Anonymous and predicate-excluded specs contribute none.
     * @param tocCardIndex index into {@code cards} before which the printed
     *                     table of contents renders — {@code cards.size()}
     *                     puts it after the last card — or null when the plan
     *                     declared no TOC position
     */
    public record Plan(List<Path> cards, List<Section> sections, List<String> warnings,
                       Integer tocCardIndex) {
        /** Normalises the lists to immutable, non-null copies. */
        public Plan {
            cards    = cards    == null ? List.of() : List.copyOf(cards);
            sections = sections == null ? List.of() : List.copyOf(sections);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** A plan with no declared TOC position. */
        public Plan(List<Path> cards, List<Section> sections, List<String> warnings) {
            this(cards, sections, warnings, null);
        }
    }

    /**
     * Resolve {@code specs} against {@code root}.
     *
     * @param root   the book root; every pattern is relative to it, and every
     *               matched card must live under it (the config cascade resolves
     *               a card's book from its own parent chain, so a card outside
     *               this tree would silently belong to a different book)
     * @param specs  the declared sections, in emission order
     * @param target current build target, for {@code where} predicates; may be null
     * @return the resolved plan
     * @throws ConfigParseException if the root isn't a directory, or a spec is
     *         unusable (no patterns, no derivable id, or a duplicate id)
     */
    public static Plan resolve(Path root, List<SectionSpec> specs, String target) {
        return resolve(root, specs, null, target);
    }

    /**
     * Resolve {@code specs} against {@code root}, with a declared position for
     * the printed table of contents.
     *
     * @param tocAfterSpec how many specs precede the TOC marker — 0 puts the
     *        contents page before everything, {@code specs.size()} after
     *        everything; null declares no position. The resolved
     *        {@link Plan#tocCardIndex()} is the card count those preceding
     *        specs actually claimed, so skipped and empty specs cost nothing.
     */
    public static Plan resolve(Path root, List<SectionSpec> specs, Integer tocAfterSpec, String target) {
        if (root == null || !Files.isDirectory(root)) {
            throw new ConfigParseException("Book root is not a directory: " + root);
        }
        Path base = root.toAbsolutePath().normalize();
        if (specs == null || specs.isEmpty()) {
            throw new ConfigParseException("Book plan declares no sections");
        }

        boolean acceptYamlCards = CardFiles.declaresCardSchema(base);
        List<Path> candidates = candidateCards(base, acceptYamlCards);
        PredicateEvaluator predicates = new PredicateEvaluator();
        Map<String, Object> predicateContext = Map.of("target", target == null ? "" : target);

        List<Path> cards = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        Set<Path> claimed = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        // Files a pattern asked for that aren't cards. Reported rather than
        // dropped in silence: a glob written as `**/*.md` plainly means "these
        // files", so the one rule that quietly disagrees -- README.md is a repo
        // readme, not a card -- has to say so, or content goes missing with no
        // way to find out why.
        warnings.addAll(nearMissWarnings(base, specs, acceptYamlCards));

        Integer tocCardIndex = null;
        for (int s = 0; s < specs.size(); s++) {
            SectionSpec spec = specs.get(s);
            // The marker sits BEFORE spec s: however many cards the specs
            // ahead of it actually claimed is where the contents page goes.
            if (tocAfterSpec != null && s == tocAfterSpec) {
                tocCardIndex = cards.size();
            }
            String id = idOf(spec);
            if (id != null && !ids.add(id)) {
                throw new ConfigParseException("Duplicate section id in book plan: '" + id + "'");
            }
            if (spec.includes().isEmpty()) {
                throw new ConfigParseException("Section '" + (id == null ? "(anonymous)" : id)
                        + "' in book plan declares no include patterns");
            }
            // Claim nothing and emit nothing: an excluded section's cards are out
            // of the book entirely, the same way a false `where:` on a yaml
            // section skips every folder it claims.
            if (!isBlank(spec.where()) && !predicates.evaluate(spec.where(), predicateContext)) {
                continue;
            }

            List<Path> matched = match(base, spec, candidates, claimed, acceptYamlCards);
            if (matched.isEmpty()) {
                warnings.add("no cards matched " + describe(id)
                        + " under " + base + ": " + spec.includes());
                continue;
            }
            cards.addAll(matched);
            if (!spec.anonymous()) {
                sections.add(new Section(id, spec.title(), List.of(),
                        resolveLandingTemplate(base, spec.landingTemplate()), matched,
                        spec.landingPage()));
            }
        }
        if (tocAfterSpec != null && tocCardIndex == null) {
            tocCardIndex = cards.size();   // marker after the last spec
        }
        return new Plan(cards, sections, warnings, tocCardIndex);
    }

    /**
     * Warn about files an {@code include} pattern matched that still won't
     * become cards.
     *
     * <p>Since a pattern now overrides the readme heuristic, what's left is the
     * {@code .yaml} a book never opted into with a {@code cardSchema:} — asked
     * for by name, and silently absent otherwise. Files that are nothing like a
     * card ({@code pom.xml}, images) aren't reported: a {@code **} pattern
     * matches the whole tree, and that warning would be noise nobody reads.
     */
    private static List<String> nearMissWarnings(
            Path base, List<SectionSpec> specs, boolean acceptYamlCards) {
        List<Path> nearMisses;
        try (Stream<Path> stream = Files.walk(base)) {
            nearMisses = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHidden(base, p))
                    .filter(p -> !CardFiles.isCard(p, acceptYamlCards, true))
                    .filter(p -> isNearMiss(p, acceptYamlCards))
                    .map(p -> p.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        if (nearMisses.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        for (SectionSpec spec : specs) {
            List<GlobSet> excludes = GlobSet.of(spec.excludes());
            List<String> hits = new ArrayList<>();
            for (String pattern : spec.includes()) {
                GlobSet include = GlobSet.of(pattern);
                for (Path miss : nearMisses) {
                    Path rel = base.relativize(miss);
                    if (include.matches(rel) && excludes.stream().noneMatch(x -> x.matches(rel))) {
                        hits.add(rel.toString());
                    }
                }
            }
            if (!hits.isEmpty()) {
                List<String> shown = hits.size() > 5 ? hits.subList(0, 5) : hits;
                out.add(describe(idOf(spec)) + " matched " + hits.size()
                        + (hits.size() == 1 ? " file that is not a card: " : " files that are not cards: ")
                        + shown + (hits.size() > shown.size()
                                ? " and " + (hits.size() - shown.size()) + " more" : "")
                        + " — a .yaml card needs the book root to declare a cardSchema:. "
                        + "Declare one, rename the file, or narrow the pattern.");
            }
        }
        return out;
    }

    /** A file close enough to a card that a pattern matching it is probably a mistake. */
    private static boolean isNearMiss(Path p, boolean acceptYamlCards) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !acceptYamlCards && (name.endsWith(".yaml") || name.endsWith(".yml"))
                && !name.equals("paperband.yaml") && !name.equals("paperband.yml");
    }

    /** True when this pattern would have claimed {@code file} but for the readme rule. */
    private static boolean skippedReadme(String pattern, Path relative) {
        return "readme.md".equals(relative.getFileName().toString().toLowerCase(Locale.ROOT))
                && !namesAFile(pattern);
    }

    // ---- internals ----

    /**
     * Every file under {@code base} that could be a card, in walk order.
     * Collected once and reused by every pattern — a book plan matches the
     * same tree many times over, and walking it per pattern would be the
     * whole cost of the plan.
     */
    private static List<Path> candidateCards(Path base, boolean acceptYamlCards) {
        try (Stream<Path> stream = Files.walk(base)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHidden(base, p))
                    // Readmes included: for a planned book the pattern decides,
                    // and one naming README.md means it. See CardFiles.isCard.
                    .filter(p -> CardFiles.isCard(p, acceptYamlCards, true))
                    .map(p -> p.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException e) {
            throw new ConfigParseException("Failed to walk book root: " + base, e);
        }
    }

    /** True when any path component below the root starts with a dot. */
    private static boolean isHidden(Path base, Path file) {
        Path rel = base.relativize(file);
        for (Path name : rel) {
            if (name.toString().startsWith(".")) return true;
        }
        return false;
    }

    /**
     * The files this spec claims, in emission order: include patterns in
     * declared order, matches within one pattern sorted, already-claimed
     * files skipped. Claims are recorded into {@code claimed} as they're made,
     * so a later pattern in the same spec can't re-emit a file either.
     */
    private static List<Path> match(Path base, SectionSpec spec, List<Path> candidates,
                                    Set<Path> claimed, boolean acceptYamlCards) {
        List<GlobSet> excludes = GlobSet.of(spec.excludes());
        List<FrontmatterSort.SortKey> sort = FrontmatterSort.parse(
                spec.sort().isEmpty() ? null : spec.sort());

        List<Path> out = new ArrayList<>();
        for (String pattern : spec.includes()) {
            GlobSet include = GlobSet.of(pattern);
            boolean namesAFile = namesAFile(pattern);
            List<Path> hits = new ArrayList<>();
            for (Path candidate : candidates) {
                if (claimed.contains(candidate)) continue;
                Path rel = base.relativize(candidate);
                if (!include.matches(rel)) continue;
                if (excludes.stream().anyMatch(x -> x.matches(rel))) continue;
                // A file the discovery rules skip -- README.md -- is claimed
                // only by a pattern that NAMES it. `scenarios/*/README.md` means
                // those files; `**` is a sweep, and there the readme rule earns
                // its keep: it's what stops a book swallowing every readme in
                // node_modules.
                if (!CardFiles.isCard(candidate, acceptYamlCards) && !namesAFile) continue;
                hits.add(candidate);
            }
            Comparator<Path> byRelativePath = Comparator.comparing(p -> base.relativize(p).toString());
            hits.sort(sort != null
                    ? FrontmatterSort.comparator(sort, acceptYamlCards).thenComparing(byRelativePath)
                    : byRelativePath);
            claimed.addAll(hits);
            out.addAll(hits);
        }
        return out;
    }

    /**
     * Does {@code pattern} end in a literal filename rather than a wildcard?
     *
     * <p>The difference between asking for a file and sweeping up whatever is
     * there — which is what decides whether the {@code README.md} rule applies
     * (see {@link CardFiles#isCard(Path, boolean, boolean)}).
     */
    private static boolean namesAFile(String pattern) {
        String trimmed = pattern.trim().replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        String last = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return !last.isEmpty()
                && last.indexOf('*') < 0
                && last.indexOf('?') < 0
                && last.indexOf('[') < 0
                && last.indexOf('{') < 0;
    }

    /**
     * One declared pattern, as the set of matchers it expands to.
     *
     * <p>Ant-style {@code **}{@code /} means "zero or more directories", but
     * {@link java.nio.file.FileSystem#getPathMatcher(String)} compiles it to a regex
     * that needs at least one — so plain {@code glob:docs/**}{@code /*.md}
     * would quietly miss {@code docs/overview.md}. Since these patterns are
     * written in a POM, where the Ant reading is the one every other plugin
     * uses, each whole-segment {@code **}{@code /} also expands to nothing and
     * a path matching any variant counts as a match.
     */
    private record GlobSet(List<PathMatcher> variants) {

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
                if (!isBlank(p)) out.add(of(p));
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
                throw new ConfigParseException("Invalid glob pattern in book plan: '" + declared
                        + "' (" + e.getMessage() + ")", e);
            }
        }
    }

    /** Explicit id, else a slug of the title, else null for an anonymous spec. */
    private static String idOf(SectionSpec spec) {
        if (!isBlank(spec.id())) return spec.id().trim();
        String slug = Section.slug(spec.title());
        if (slug == null && !spec.anonymous()) {
            throw new ConfigParseException(
                    "Section in book plan declares neither a usable 'title' nor an 'id'");
        }
        return slug;
    }

    private static String resolveLandingTemplate(Path base, String declared) {
        return isBlank(declared) ? null : NamedTemplates.resolveSectionTemplate(base, declared.trim());
    }

    private static String describe(String id) {
        return id == null ? "anonymous book-plan entry" : "book-plan section '" + id + "'";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
