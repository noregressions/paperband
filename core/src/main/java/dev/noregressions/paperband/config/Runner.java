package dev.noregressions.paperband.config;

import dev.noregressions.paperband.AstHtmlRenderer;
import dev.noregressions.paperband.Fonts;
import dev.noregressions.paperband.fit.FitException;
import dev.noregressions.paperband.fit.FitPage;
import dev.noregressions.paperband.fit.Problem;
import dev.noregressions.paperband.SiteBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs a {@link BuildConfig}: builds every output and writes them, and any debug pages asked for. */
public final class Runner {

    /**
     * @param build what was built
     * @param site  where the first output was written
     * @param files where every output was written, in order
     * @param ast   where the AST debug page was written, or null
     * @param draft where the draft AST debug page was written, or null
     */
    public record Outcome(SiteBuilder.Build build, Path site, List<Path> files, Path ast, Path draft) {

        public List<String> warnings() {
            return build.report().warnings();
        }
    }

    private Runner() {
    }

    public static Outcome run(BuildConfig config) throws IOException {
        SiteBuilder.Build build = SiteBuilder.build(config.manifest(), config.clock());
        // Only when every output fits is anything written; otherwise the fit reports say why not.
        List<String> failures = new java.util.ArrayList<>();
        List<Problem> problems = new java.util.ArrayList<>();
        for (SiteBuilder.Output output : build.outputs()) {
            if (!output.problems().isEmpty()) {
                Path report = fitReport(output.file());
                write(report, FitPage.render(output.html(), output.name(), output.problems()));
                Fonts.copyTo(report.toAbsolutePath().getParent());
                failures.add(FitException.describe(output.name(), output.problems(), report));
                problems.addAll(output.problems());
            }
        }
        if (!failures.isEmpty()) {
            throw new FitException(String.join("\n", failures), problems);
        }
        List<Path> files = new java.util.ArrayList<>();
        for (SiteBuilder.Output output : build.outputs()) {
            switch (output.format()) {
                case WEB -> {
                    if (output.site() == null) {
                        write(output.file(), output.html());
                    } else {
                        Path root = output.file().toAbsolutePath().getParent();
                        for (var f : output.site().entrySet()) {
                            write(root.resolve(f.getKey()), f.getValue());
                        }
                    }
                }
                case PAGED_HTML -> {
                    write(output.file(), output.html());
                    Fonts.copyTo(output.file().toAbsolutePath().getParent()); // the page loads them from beside it
                }
                case PDF -> {
                    Files.createDirectories(output.file().toAbsolutePath().getParent());
                    Files.write(output.file(), output.pdf());
                }
            }
            files.add(output.file());
            Files.deleteIfExists(fitReport(output.file())); // from an earlier build that didn't fit
        }
        if (config.astOutput() != null) {
            write(config.astOutput(), AstHtmlRenderer.render(build.result().ast()));
        }
        if (config.draftOutput() != null) {
            write(config.draftOutput(), AstHtmlRenderer.render(build.result().draft(), AstHtmlRenderer.DRAFT_TITLE));
        }
        return new Outcome(build, files.getFirst(), List.copyOf(files), config.astOutput(), config.draftOutput());
    }

    /** Where an output's fit report goes: beside it, {@code handbook.pdf} → {@code handbook.fit.html}. */
    public static Path fitReport(Path output) {
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return output.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".fit.html");
    }

    private static void write(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
