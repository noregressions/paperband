package dev.noregressions.paperband.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Loads and looks up {@link HtmlToPdfRenderer} implementations.
 *
 * <p>Use {@link #discover()} to find every renderer registered via
 * {@link java.util.ServiceLoader} on the current thread's context classloader.
 *
 * <p>Lookup is case-sensitive on the renderer's {@link HtmlToPdfRenderer#name()}.
 */
public final class RendererRegistry {

    private final Map<String, HtmlToPdfRenderer> byName;

    private RendererRegistry(Map<String, HtmlToPdfRenderer> byName) {
        this.byName = byName;
    }

    /** Discover renderers using the current thread's context classloader. */
    public static RendererRegistry discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    /** Discover renderers using the supplied classloader. */
    public static RendererRegistry discover(ClassLoader loader) {
        Map<String, HtmlToPdfRenderer> map = new LinkedHashMap<>();
        for (HtmlToPdfRenderer renderer : ServiceLoader.load(HtmlToPdfRenderer.class, loader)) {
            map.put(renderer.name(), renderer);
        }
        return new RendererRegistry(map);
    }

    /** Construct a registry from an explicit list. Useful in tests. */
    public static RendererRegistry of(HtmlToPdfRenderer... renderers) {
        Map<String, HtmlToPdfRenderer> map = new LinkedHashMap<>();
        for (HtmlToPdfRenderer r : renderers) {
            map.put(r.name(), r);
        }
        return new RendererRegistry(map);
    }

    public Optional<HtmlToPdfRenderer> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<HtmlToPdfRenderer> all() {
        return List.copyOf(byName.values());
    }

    public List<HtmlToPdfRenderer> available() {
        return byName.values().stream().filter(HtmlToPdfRenderer::isAvailable).toList();
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public int size() {
        return byName.size();
    }
}
