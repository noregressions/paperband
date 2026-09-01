package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.block.BlockRenderer;
import dev.noregressions.paperband.block.BlockRendererRegistry;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.util.List;

/**
 * Block-renderer discovery, shared by every goal that loads cards.
 *
 * <p>Discovery is once per goal and the result is handed to
 * {@code BlockTemplates}, so a module gets one instance for the whole book
 * rather than one per card.
 *
 * <p>The log line matters more than it looks. A book whose diagrams came out
 * as code blocks has exactly one interesting question — <em>was the jar on the
 * classpath?</em> — and a build that says which renderers it found answers it
 * without anyone having to reach for {@code -X}.
 */
final class BlockRenderers {

    private BlockRenderers() {}

    /**
     * The block renderers on this build's classpath.
     *
     * @param log where the summary line goes; may be null
     * @return the registry, empty when no module is installed
     * @throws MojoExecutionException when two modules claim the same fence
     *         type, or one is registered wrongly — a classpath the build
     *         cannot interpret unambiguously stops it
     */
    static BlockRendererRegistry discover(Log log) throws MojoExecutionException {
        BlockRendererRegistry registry;
        try {
            registry = BlockRendererRegistry.discover();
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        if (log != null && !registry.isEmpty()) {
            for (BlockRenderer r : registry.all()) {
                List<String> types = r.types().stream().sorted().toList();
                log.info("Block renderer: " + r.name() + " -> ```" + String.join(", ```", types)
                        + (r.isAvailable() ? "" : "  (UNAVAILABLE: " + r.unavailableReason() + ")"));
            }
        }
        return registry;
    }
}
