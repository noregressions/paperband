package dev.noregressions.paperband.pebble;

import java.util.HashMap;
import java.util.Map;

/**
 * Marker {@link HashMap} subclass signalling to {@link LenientMapAttributeResolver}
 * that missing-key lookups on this map should return {@code null} rather than
 * raising {@code AttributeNotFoundException}.
 *
 * <p>Some model maps are deliberately sparse — per-card {@code frontmatter}
 * and book-level {@code vars} — and templates (and the whole-body vars/
 * conditionals pass over card markdown) check them with
 * {@code {% if card.frontmatter.effort %}} / {@code {% if vars.optional %}}
 * style guards that assume null on absence. Wrapping those maps in
 * {@code LenientMap} preserves that pattern while {@link LenientMapAttributeResolver}
 * still catches typos on every other (non-lenient) map.
 *
 * <p>This class adds no behaviour beyond {@link HashMap}; the resolver
 * dispatches on {@code instanceof LenientMap}.
 */
public final class LenientMap<K, V> extends HashMap<K, V> {

    public LenientMap() {
        super();
    }

    public LenientMap(Map<? extends K, ? extends V> m) {
        super(m == null ? Map.of() : m);
    }

    /**
     * Convenience: wrap {@code m} in a {@code LenientMap}. Returns an empty
     * lenient map when {@code m} is null so templates can dereference safely.
     */
    public static <K, V> LenientMap<K, V> of(Map<? extends K, ? extends V> m) {
        return new LenientMap<>(m);
    }
}
