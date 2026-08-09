package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.RendererRegistry;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Lists the renderer implementations discovered on the classpath.
 *
 * <pre>pagewright renderers</pre>
 */
@Command(
        name = "renderers",
        mixinStandardHelpOptions = true,
        description = "List the HTML to PDF renderers available on this build.")
public final class RenderersCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        RendererRegistry registry = RendererRegistry.discover();
        if (registry.isEmpty()) {
            System.out.println("No renderers found on the classpath.");
            return 1;
        }
        System.out.printf("%-16s  %-9s  %s%n", "NAME", "AVAILABLE", "DESCRIPTION");
        for (HtmlToPdfRenderer r : registry.all()) {
            System.out.printf("%-16s  %-9s  %s%n",
                    r.name(),
                    r.isAvailable() ? "yes" : "no",
                    r.description());
        }
        return 0;
    }
}
