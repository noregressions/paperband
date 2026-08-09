package dev.noregressions.paperband.include;

import java.nio.file.Path;
import java.util.Map;

/**
 * Context passed to {@link ContentProvider}s and {@link FragmentProcessor}s when
 * resolving an include directive. Carries everything a provider needs to resolve
 * a reference without reaching into global state.
 *
 * @param sourceFile  absolute path to the markdown file containing the directive;
 *                    used by the file provider for relative-path resolution
 * @param bookRoot    absolute path to the book root (for {@code @/...} or yaml-config-relative
 *                    path resolution); may be null for single-card builds
 * @param attributes  raw attribute map parsed from the directive
 *                    (e.g. {@code marker_start=BEGIN}, {@code lang=python});
 *                    both providers and processors read from this same map
 * @param providerConfig  optional per-provider config block from {@code paperband.yaml},
 *                    or empty if no config is declared for that provider
 */
public record IncludeContext(
        Path sourceFile,
        Path bookRoot,
        Map<String, String> attributes,
        Map<String, Object> providerConfig) {

    public IncludeContext {
        if (attributes == null) attributes = Map.of();
        if (providerConfig == null) providerConfig = Map.of();
    }
}
