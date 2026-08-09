package dev.noregressions.paperband.pebble;

import io.pebbletemplates.pebble.attributes.AttributeResolver;
import io.pebbletemplates.pebble.extension.AbstractExtension;

import java.util.List;

/**
 * Pebble extension that contributes {@link LenientMapAttributeResolver} to
 * the engine's attribute-resolver chain. Kept narrow on purpose: no filters,
 * tests, functions, or globals — only the resolver.
 */
public final class LenientMapExtension extends AbstractExtension {

    @Override
    public List<AttributeResolver> getAttributeResolver() {
        return List.of(new LenientMapAttributeResolver());
    }
}
