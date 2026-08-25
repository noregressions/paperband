package dev.noregressions.paperband.include;

/**
 * Resolves an include reference to a {@link Fragment}.
 *
 * <p>One implementation per content source. Built-in: {@code file}, {@code card}.
 * Future modules may ship {@code git}, {@code http}, {@code gist}, etc.
 *
 * <p>Providers are looked up by {@link #name()} via a simple registry; for the
 * first cut they're constructed and registered explicitly by the host
 * application (the Maven plugin). A {@code ServiceLoader}-driven registry can
 * follow once we have more than one provider in flight.
 *
 * <h2>Reference format</h2>
 * <p>The reference string is opaque to the include system: providers parse it
 * however they like. The file provider accepts {@code path[:tag|:start:end]};
 * a git provider would likely accept {@code ref:path[:tag]}; an http provider
 * accepts a URL.
 *
 * <h2>Attributes</h2>
 * <p>Directive attributes (everything after the reference but before
 * {@code as <type>}) are passed to both the provider and the processor via
 * {@link IncludeContext#attributes()}. Each picks out the keys it cares about
 * and ignores the rest. Provider-specific keys (e.g. {@code marker_start},
 * {@code marker_end}, {@code dedent=false}) belong here.
 */
public interface ContentProvider {

    /**
     * Provider identifier matching the scheme used in directives.
     *
     * <p>For {@code {{#include git://samples:path}}} the name is {@code git}.
     * For the short-form {@code {{#include path}}} the default name is
     * {@code file}.
     * @return the provider name
     */
    String name();

    /**
     * Fetch the fragment identified by {@code reference}, using {@code ctx} for
     * any environmental information needed (source file location, attributes,
     * config).
     *
     * @param reference the content reference
     * @param ctx the include context
     * @return the fetched fragment
     * @throws ContentResolutionException if the reference can't be resolved
     *                                    (file missing, anchor missing,
     *                                    network failure, etc.)
     */
    Fragment fetch(String reference, IncludeContext ctx) throws ContentResolutionException;

    /**
     * Validate that {@code reference} would resolve, without producing the
     * fragment. Used by the build-time validation pass to surface broken
     * includes up front.
     *
     * <p>Default implementation calls {@link #fetch} and discards the result;
     * providers can override with cheaper checks (e.g. {@code Files.exists})
     * where possible.
     *
     * @param reference the content reference
     * @param ctx the include context
     * @throws ContentResolutionException if the reference cannot be validated
     */
    default void validate(String reference, IncludeContext ctx) throws ContentResolutionException {
        fetch(reference, ctx);
    }
}
