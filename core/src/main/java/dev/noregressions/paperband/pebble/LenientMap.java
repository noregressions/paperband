package dev.noregressions.paperband.pebble;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marker {@link LinkedHashMap} subclass signalling to {@link LenientMapAttributeResolver}
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
 * <p>This class adds no behaviour beyond {@link LinkedHashMap}; the resolver
 * dispatches on {@code instanceof LenientMap}. Linked rather than plain
 * hashed so iteration keeps the wrapped map's order: a block's attributes are
 * written onto its section in the order the author gave them.
 *
 * <p>Not final: a model map that carries more than its entries -- a node's,
 * which keeps the record it was read from -- is a subclass, and stays lenient.
 */
public class LenientMap<K, V> extends LinkedHashMap<K, V> {

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
