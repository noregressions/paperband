package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.RendererRegistry;

import org.apache.maven.plugin.MojoExecutionException;

/** Renderer lookup, shared by the goals that produce a PDF. */
final class Renderers {

    private Renderers() {}

    /**
     * The renderer registered under {@code name}.
     *
     * @throws MojoExecutionException when no such renderer is on the classpath,
     *         listing what is — a typo'd name should say so rather than fall
     *         back to something the build didn't ask for
     */
    static HtmlToPdfRenderer require(String name) throws MojoExecutionException {
        RendererRegistry registry = RendererRegistry.discover();
        return registry.get(name).orElseThrow(() -> new MojoExecutionException(
                "Unknown renderer: " + name + ". Available: "
                        + registry.all().stream().map(HtmlToPdfRenderer::name).toList()));
    }
}
