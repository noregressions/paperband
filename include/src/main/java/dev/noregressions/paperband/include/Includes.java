package dev.noregressions.paperband.include;

import dev.noregressions.paperband.cards.MarkdownPreprocessor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One-stop builder that assembles the default include subsystem:
 * <ul>
 *   <li>Built-in providers: {@link FileContentProvider}.</li>
 *   <li>Built-in processors: {@link CodeProcessor}, {@link MarkdownProcessor},
 *       {@link HtmlProcessor}, {@link TextProcessor}.</li>
 * </ul>
 *
 * <p>The CLI uses {@link #defaultPreprocessor(Path, Map, Map)} to wire a
 * working preprocessor into {@code CardLoader}. Tests and other callers can
 * mix in additional providers via
 * {@link #buildPreprocessor(List, List, Path, Map, Map)}.
 *
 * <p>The returned preprocessor also evaluates {@code vars}/conditionals (see
 * {@link PebbleIncludePreprocessor}), so — unlike before {@code vars} moved
 * into the same Pebble pass as fragment resolution — construct a fresh
 * instance per card rather than reusing one across a whole book, since
 * {@code vars} varies per card via the folder-yaml axis cascade.
 */
public final class Includes {

    private Includes() {}

    /** Construct the bundled set of providers. Stable order; {@code file} first. */
    public static List<ContentProvider> defaultProviders() {
        return List.of(new FileContentProvider());
    }

    /** Construct the bundled set of processors. */
    public static List<FragmentProcessor> defaultProcessors() {
        return List.of(
                new CodeProcessor(),
                new MarkdownProcessor(),
                new HtmlProcessor(),
                new TextProcessor());
    }

    /**
     * Build a preprocessor with the bundled providers and processors.
     *
     * @param bookRoot         book root for path resolution; null for single-card builds
     * @param providerConfigs  per-provider config blocks from {@code pagewright.yaml}; may be empty
     * @param vars             resolved {@code vars} map for the card being processed
     */
    public static MarkdownPreprocessor defaultPreprocessor(
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs,
            Map<String, Object> vars) {
        return buildPreprocessor(defaultProviders(), defaultProcessors(), bookRoot, providerConfigs, vars);
    }

    /** Build a preprocessor with custom provider and processor lists. */
    public static MarkdownPreprocessor buildPreprocessor(
            List<ContentProvider> providers,
            List<FragmentProcessor> processors,
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs,
            Map<String, Object> vars) {
        return new PebbleIncludePreprocessor(
                PebbleIncludePreprocessor.indexProviders(providers),
                PebbleIncludePreprocessor.indexProcessors(processors),
                bookRoot,
                providerConfigs == null ? Map.of() : providerConfigs,
                vars);
    }
}
