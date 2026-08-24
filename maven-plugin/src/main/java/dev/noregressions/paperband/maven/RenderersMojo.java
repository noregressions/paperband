package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.RendererRegistry;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Lists the HTML-to-PDF renderers this build can reach, and whether each is
 * actually usable on this machine.
 *
 * <pre>mvn paperband:renderers</pre>
 */
@Mojo(name = "renderers", requiresProject = false, threadSafe = true)
public class RenderersMojo extends AbstractPaperbandMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("renderers")) return;

        RendererRegistry registry = RendererRegistry.discover();
        if (registry.isEmpty()) {
            throw new MojoFailureException("No renderers found on the classpath.");
        }
        getLog().info(String.format("%-16s  %-9s  %s", "NAME", "AVAILABLE", "DESCRIPTION"));
        for (HtmlToPdfRenderer r : registry.all()) {
            getLog().info(String.format("%-16s  %-9s  %s",
                    r.name(), r.isAvailable() ? "yes" : "no", r.description()));
        }
    }
}
