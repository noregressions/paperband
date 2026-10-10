package dev.noregressions.paperband.layout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Works out a manifest's sequence from the source files and their front matter. Everything is
 * checked here, before any templating runs: unknown files, patterns that match nothing, files
 * listed twice, unknown layouts, and (depending on {@code unused:}) sources left out.
 */
public final class SequenceResolver {

    private SequenceResolver() {
    }

    /**
     * @param sources     source files, relative to the manifest directory
     * @param frontMatter each source's front matter
     * @param layouts     the names of the layouts that exist
     */
    public static ResolvedSequence resolve(Manifest manifest, List<Path> sources,
                                           Map<Path, Map<String, Object>> frontMatter, Set<String> layouts,
                                           RunReport report) throws ConfigException {
        return resolve(manifest, manifest.sequence(), true, sources, frontMatter, layouts, report);
    }

    /**
     * One output's pages: its own sequence, or {@code main} (the manifest's, already resolved),
     * with the output's {@code layout} and {@code layouts} applied. Unused sources are only
     * reported for the manifest's sequence: an output may show a subset on purpose.
     */
    public static ResolvedSequence forOutput(Manifest manifest, Manifest.Output output, ResolvedSequence main,
                                             List<Path> sources, Map<Path, Map<String, Object>> frontMatter,
                                             Set<String> layouts, RunReport report) throws ConfigException {
        ResolvedSequence base = output.sequence() == null ? main
                : resolve(manifest, output.sequence(), false, sources, frontMatter, layouts, report);
        if (output.layout() == null && output.layouts().isEmpty()) {
            return base;
        }
        List<ResolvedSequence.Item> items = new ArrayList<>();
        for (ResolvedSequence.Item item : base.items()) {
            ResolvedSequence.Item changed = item;
            if (output.layout() != null && item.file() != null && !item.named()) {
                changed = changed.withLayout(output.layout());
            }
            String renamed = output.layouts().get(changed.layout());
            if (renamed != null) {
                changed = changed.withLayout(renamed);
            }
            checkLayout(manifest, output.context() + " (" + (item.file() != null ? item.file() : item.context()) + ")",
                    changed.layout(), layouts);
            items.add(changed);
        }
        return new ResolvedSequence(List.copyOf(items), base.unused());
    }

    private static ResolvedSequence resolve(Manifest manifest, List<Manifest.Entry> entries, boolean checkUnused,
                                            List<Path> sources, Map<Path, Map<String, Object>> frontMatter,
                                            Set<String> layouts, RunReport report) throws ConfigException {
        Set<Path> sourceSet = new LinkedHashSet<>();
        for (Path s : sources) {
            sourceSet.add(s.normalize());
        }
        // Hidden sources stay sources (templates can read them through site) but are never pages:
        // files: patterns skip them, and naming one with file: is an error.
        Map<Path, String> hidden = new LinkedHashMap<>();
        for (String pattern : manifest.hidden()) {
            List<Path> matched = sourceSet.stream().filter(Globs.matcher(pattern)).toList();
            if (matched.isEmpty()) {
                throw new ConfigException(manifest.name() + ": hidden pattern \"" + pattern + "\" matches none of the sources");
            }
            matched.forEach(p -> hidden.putIfAbsent(p, pattern));
        }

        List<ResolvedSequence.Item> items = new ArrayList<>();
        Map<Path, String> placed = new LinkedHashMap<>();

        for (Manifest.Entry entry : entries) {
            switch (entry) {
                case Manifest.FileEntry e -> {
                    if (!sourceSet.contains(e.file())) {
                        throw new ConfigException(e.context() + ": " + e.file()
                                + " isn't one of the sources");
                    }
                    if (hidden.containsKey(e.file())) {
                        throw new ConfigException(manifest.name() + ": " + e.file() + " is hidden (\"" + hidden.get(e.file())
                                + "\") but also in the sequence (" + e.context() + ")");
                    }
                    items.add(item(manifest, e.context(), e.file(), e.layout(), frontMatter, layouts, placed));
                }
                case Manifest.FilesEntry e -> {
                    Predicate<Path> m = Globs.matcher(e.pattern());
                    List<Path> matched = sourceSet.stream().filter(m).toList();
                    if (matched.isEmpty()) {
                        throw new ConfigException(e.context() + ": \"" + e.pattern()
                                + "\" matches none of the sources");
                    }
                    matched = matched.stream().filter(p -> !hidden.containsKey(p)).toList();
                    if (matched.isEmpty()) {
                        report.warn(e.context() + ": every source matching \"" + e.pattern() + "\" is hidden");
                    }
                    List<Path> kept = new ArrayList<>();
                    for (Path p : matched) {
                        if (e.where() == null || e.where().test(frontMatter.getOrDefault(p, Map.of()), p.toString())) {
                            kept.add(p);
                        }
                    }
                    if (kept.isEmpty() && !matched.isEmpty()) {
                        report.warn(e.context() + ": where filter excluded all "
                                + matched.size() + " file(s) matching \"" + e.pattern() + "\"");
                    }
                    for (Path p : e.sort().sort(kept, frontMatter)) {
                        items.add(item(manifest, e.context(), p, e.layout(), frontMatter, layouts, placed));
                    }
                }
                case Manifest.GeneratedEntry e -> {
                    checkLayout(manifest, e.context(), e.layout(), layouts);
                    items.add(new ResolvedSequence.Item(e.context(), null, e.layout()));
                }
            }
        }

        List<Path> unused = sourceSet.stream().filter(p -> !placed.containsKey(p) && !hidden.containsKey(p)).toList();
        if (!unused.isEmpty() && checkUnused) {
            String message = manifest.name() + ": " + unused.size() + " source(s) not in the sequence or hidden: " + unused;
            switch (manifest.unused()) {
                case ERROR -> throw new ConfigException(message);
                case WARN -> report.warn(message);
                case IGNORE -> {
                }
            }
        }
        return new ResolvedSequence(List.copyOf(items), unused);
    }

    private static ResolvedSequence.Item item(Manifest manifest, String context, Path file, String entryLayout,
                                              Map<Path, Map<String, Object>> frontMatter, Set<String> layouts,
                                              Map<Path, String> placed) throws ConfigException {
        String previous = placed.putIfAbsent(file, context);
        if (previous != null) {
            throw new ConfigException(manifest.name() + ": " + file + " is in the sequence twice: " + previous
                    + " and " + context);
        }
        String layout = entryLayout;
        if (layout == null) {
            Object own = frontMatter.getOrDefault(file, Map.of()).get("layout");
            layout = switch (own) {
                case null -> manifest.defaultLayout();
                case String name -> name;
                case Map<?, ?> definition -> Layouts.inlineName(file);
                default -> throw new ConfigException(file + ": front matter layout must be a layout name or a layout"
                        + " definition, got " + YamlSpec.describe(own));
            };
        }
        checkLayout(manifest, context + " (" + file + ")", layout, layouts);
        return new ResolvedSequence.Item(context, file, layout, entryLayout != null);
    }

    private static void checkLayout(Manifest manifest, String context, String layout, Set<String> layouts)
            throws ConfigException {
        if (!layouts.contains(layout)) {
            throw new ConfigException(context + ": unknown layout \"" + layout
                    + "\"; available: " + layouts.stream().sorted().toList());
        }
    }
}
