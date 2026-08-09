package dev.noregressions.paperband.pebble;

import io.pebbletemplates.pebble.attributes.AttributeResolver;
import io.pebbletemplates.pebble.attributes.ResolvedAttribute;
import io.pebbletemplates.pebble.error.AttributeNotFoundException;
import io.pebbletemplates.pebble.node.ArgumentsNode;
import io.pebbletemplates.pebble.template.EvaluationContextImpl;

import java.util.Map;

/**
 * Pebble {@link AttributeResolver} that runs ahead of the default resolver
 * chain and handles all {@link Map} attribute lookups itself:
 *
 * <ul>
 *   <li>{@link LenientMap} — returns the stored value or {@code null} when
 *       the key is absent. Used for intentionally sparse maps
 *       (per-card {@code frontmatter}, book-level {@code vars}) so patterns
 *       like {@code {% if card.frontmatter.effort %}} or
 *       {@code {% if vars.optional %}} keep working.</li>
 *   <li>Any other {@code Map} — throws {@link AttributeNotFoundException}
 *       when the key is absent, with the template filename and line number
 *       attached. This catches structural-map typos like
 *       {@code {{ card.tytle }}} or {@code {{ block.headign }}} at build
 *       time even when Pebble's own {@code strictVariables} flag is off.
 *       {@code AttributeNotFoundException} is the specific exception type
 *       that Pebble's {@code DefaultFilter} and {@code DefinedTest}
 *       special-case — so {@code | default(...)} and {@code is defined}
 *       guards still work over our throw.</li>
 *   <li>Non-{@code Map} instances — returns {@code null} to defer to the
 *       default resolver chain (e.g. POJO property access).</li>
 * </ul>
 *
 * <p>The throw carries {@code filename:lineNumber} so callers can splice it
 * directly into their own error message.
 */
final class LenientMapAttributeResolver implements AttributeResolver {

    @Override
    public ResolvedAttribute resolve(
            Object instance,
            Object attributeNameValue,
            Object[] argumentValues,
            ArgumentsNode args,
            EvaluationContextImpl context,
            String filename,
            int lineNumber) {

        if (!(instance instanceof Map<?, ?> map)) {
            return null; // defer to default chain (POJOs, arrays, etc.)
        }
        if (argumentValues != null) {
            // method-call syntax (e.g. {{ map.get('x') }}) — let MethodResolver handle.
            return null;
        }

        // Lookup: try the raw object key first, then the stringified form.
        if (map.containsKey(attributeNameValue)) {
            return new ResolvedAttribute(map.get(attributeNameValue));
        }
        String key = String.valueOf(attributeNameValue);
        if (map.containsKey(key)) {
            return new ResolvedAttribute(map.get(key));
        }

        // Key missing.
        if (instance instanceof LenientMap<?, ?>) {
            return new ResolvedAttribute(null); // lenient — null is fine
        }

        // Strict — throw AttributeNotFoundException so DefaultFilter /
        // DefinedTest can still catch it, but a bare {{ ... }} reference fails.
        throw new AttributeNotFoundException(
                null,
                "Attribute [" + key + "] of [" + map.getClass().getName()
                        + "] does not exist. Available keys: " + map.keySet(),
                key,
                lineNumber,
                filename);
    }
}
