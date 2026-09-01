package dev.noregressions.paperband.block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * The {@link BlockRenderer}s a build can reach, indexed by the fence types
 * they claim.
 *
 * <p>Discovery is {@link java.util.ServiceLoader} over the classpath, so a
 * module joins by being a dependency and nothing more. Lookup is by fence tag
 * rather than by renderer name, because that is the question the card loader
 * asks: <em>this block says {@code ```plantuml} — is that anyone's?</em>
 *
 * <p>Two renderers claiming one tag is rejected at construction. The failure
 * mode it prevents is the bad one: whichever jar the classloader happened to
 * enumerate first silently wins, and the same book renders differently on two
 * machines.
 */
public final class BlockRendererRegistry {

    /** Fence tag -> the renderer that claims it. Case-sensitive, insertion-ordered. */
    private final Map<String, BlockRenderer> byType;

    /** Every renderer, in discovery order, whether or not it is available. */
    private final List<BlockRenderer> renderers;

    private BlockRendererRegistry(Map<String, BlockRenderer> byType, List<BlockRenderer> renderers) {
        this.byType = byType;
        this.renderers = renderers;
    }

    /** An empty registry - no modules, every fence type left to the templates. */
    public static BlockRendererRegistry empty() {
        return new BlockRendererRegistry(Map.of(), List.of());
    }

    /** Discover renderers using the current thread's context classloader. */
    public static BlockRendererRegistry discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    /** Discover renderers using the supplied classloader. */
    public static BlockRendererRegistry discover(ClassLoader loader) {
        List<BlockRenderer> found = new ArrayList<>();
        for (BlockRenderer r : ServiceLoader.load(BlockRenderer.class, loader)) {
            found.add(r);
        }
        return of(found);
    }

    /** Build a registry from an explicit list. The wiring tests use this. */
    public static BlockRendererRegistry of(BlockRenderer... renderers) {
        return of(List.of(renderers));
    }

    /**
     * Build a registry from an explicit list.
     *
     * @param renderers the renderers, in precedence-irrelevant order
     * @return the registry
     * @throws IllegalStateException when two renderers claim the same fence
     *         type, or one declares no types at all
     */
    public static BlockRendererRegistry of(List<BlockRenderer> renderers) {
        Map<String, BlockRenderer> byType = new LinkedHashMap<>();
        // Sorted so the message reads the same on every machine, however the
        // classloader happened to enumerate the jars.
        Map<String, List<String>> clashes = new TreeMap<>();
        for (BlockRenderer r : renderers) {
            if (r.types() == null || r.types().isEmpty()) {
                throw new IllegalStateException("Block renderer '" + r.name()
                        + "' (" + r.getClass().getName() + ") claims no fence types."
                        + " A renderer nothing can reach is a wiring mistake, not a no-op.");
            }
            for (String type : r.types()) {
                BlockRenderer prior = byType.putIfAbsent(type, r);
                if (prior != null && prior != r) {
                    clashes.computeIfAbsent(type, k -> new ArrayList<>(List.of(describe(prior))))
                            .add(describe(r));
                }
            }
        }
        if (!clashes.isEmpty()) {
            StringBuilder m = new StringBuilder("Two block renderers claim the same fence type."
                    + " Which one wins would depend on classpath order, so the build stops"
                    + " instead of rendering differently on different machines:");
            clashes.forEach((type, who) ->
                    m.append("\n  ```").append(type).append(" claimed by ").append(String.join(", ", who)));
            m.append("\n\nDrop one of the jars from the plugin's <dependencies>, or override the"
                    + " type in the book with layouts/blocks/<type>.html, which outranks both.");
            throw new IllegalStateException(m.toString());
        }
        return new BlockRendererRegistry(byType, List.copyOf(renderers));
    }

    private static String describe(BlockRenderer r) {
        return r.name() + " (" + r.getClass().getName() + ")";
    }

    /**
     * The renderer claiming {@code type} <em>and able to run here</em>.
     *
     * <p>An unavailable renderer is deliberately not returned: the block then
     * falls through to a template or to plain preformatted source, so a book
     * still builds on a machine missing the backend. {@code mvn
     * paperband:blocks} is where the absence is reported.
     *
     * @param type the fence's language tag
     * @return the renderer, or empty
     */
    public Optional<BlockRenderer> forType(String type) {
        BlockRenderer r = type == null ? null : byType.get(type);
        return r != null && r.isAvailable() ? Optional.of(r) : Optional.empty();
    }

    /** Every discovered renderer, available or not. */
    public List<BlockRenderer> all() {
        return renderers;
    }

    /** Every fence type claimed by an available renderer, in discovery order. */
    public List<String> types() {
        return byType.entrySet().stream()
                .filter(e -> e.getValue().isAvailable())
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean isEmpty() {
        return renderers.isEmpty();
    }

    public int size() {
        return renderers.size();
    }
}
