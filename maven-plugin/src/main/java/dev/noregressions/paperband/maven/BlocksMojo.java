package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.block.BlockRenderer;
import dev.noregressions.paperband.block.BlockRendererRegistry;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.List;

/**
 * Lists the fenced block types this build can render, and what renders each.
 *
 * <pre>mvn paperband:blocks</pre>
 *
 * <p>The question it answers is the one a missing diagram raises: a
 * {@code ```plantuml} fence that came out as a code block did so because
 * nothing claimed the type, and the only way to see that today is to reason
 * about the plugin's classpath. This prints it.
 *
 * <p>Bundled block templates are listed too, because "what types are there?"
 * and "what renders them?" are the same question to an author, and answering
 * only half of it would send them to the source for the rest.
 */
@Mojo(name = "blocks", requiresProject = false, threadSafe = true)
public class BlocksMojo extends AbstractPaperbandMojo {

    /**
     * The block types paperband itself ships, as templates under
     * {@code templates/blocks/} in the cards module.
     *
     * <p>A literal list rather than a classpath scan: a jar's directory
     * entries are not reliably enumerable, and a wrong answer here would be
     * worse than no answer. Keep it in step with that directory.
     */
    private static final List<String[]> BUNDLED = List.of(
            new String[] {"command",  "shell command, with a Command label"},
            new String[] {"output",   "program output, with an Output label"},
            new String[] {"console",  "an interactive session"},
            new String[] {"mermaid",  "Mermaid diagram, drawn by the browser at page load"});

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("blocks")) return;

        BlockRendererRegistry registry;
        try {
            registry = BlockRendererRegistry.discover();
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        getLog().info(String.format("%-14s  %-12s  %-9s  %s",
                "TYPE", "RENDERED BY", "AVAILABLE", "DESCRIPTION"));
        for (BlockRenderer r : registry.all()) {
            for (String type : r.types().stream().sorted().toList()) {
                getLog().info(String.format("%-14s  %-12s  %-9s  %s",
                        "```" + type, r.name(), r.isAvailable() ? "yes" : "no", r.description()));
            }
            if (!r.isAvailable() && r.unavailableReason() != null) {
                getLog().warn("  " + r.name() + " is installed but not usable here: "
                        + r.unavailableReason());
            }
        }
        for (String[] t : BUNDLED) {
            // A module claiming a bundled type wins over the template, so say
            // so rather than listing the same fence twice with two answers.
            String by = registry.forType(t[0]).map(BlockRenderer::name).orElse("template");
            if (!"template".equals(by)) continue;
            getLog().info(String.format("%-14s  %-12s  %-9s  %s",
                    "```" + t[0], "template", "yes", t[1]));
        }

        if (registry.isEmpty()) {
            getLog().info("");
            getLog().info("No block renderer modules on the classpath. Add one to the plugin's"
                    + " own <dependencies> (e.g. dev.noregressions.paperband:block-plantuml)"
                    + " to render ```plantuml and friends at build time.");
        }
        getLog().info("");
        getLog().info("A book's own layouts/blocks/<type>.html outranks every row above,"
                + " and any other ```type is an ordinary code block.");
    }
}
