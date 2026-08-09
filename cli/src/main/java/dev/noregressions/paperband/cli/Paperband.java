package dev.noregressions.paperband.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI entry point. Dispatches to subcommands via picocli; running with no
 * arguments prints usage. The subcommands together cover the full lifecycle:
 * {@code scan} for diagnosing a single card, {@code render} for one-shot
 * HTML to PDF, {@code build} for a full book PDF, {@code site} for the static
 * site, {@code structure} for a no-render text outline of the document
 * model, {@code pages} for the per-anchor page report, {@code renderers}
 * for inspecting the discovered render backends, and {@code themes} for
 * listing the theme bundles available to {@code --theme}.
 */
@Command(
        name = "paperband",
        mixinStandardHelpOptions = true,
        version = "paperband 0.1.0-SNAPSHOT",
        description = "Build static sites and PDFs from structured Markdown content.",
        subcommands = {
                BuildCommand.class,
                PagesCommand.class,
                PublishCommand.class,
                RenderCommand.class,
                RenderersCommand.class,
                ScanCommand.class,
                SiteCommand.class,
                StructureCommand.class,
                ThemesCommand.class
        })
public final class Paperband implements Runnable {

    public static void main(String[] args) {
        int exit = new CommandLine(new Paperband()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
