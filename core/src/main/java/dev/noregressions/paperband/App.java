package dev.noregressions.paperband;

import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.layout.Manifest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a site from markdown files with {@link SiteBuilder}: either the files named on the
 * command line, in order, or whatever a {@code --manifest} says. Writes the laid-out web page
 * ({@code site.html}, or the manifest's {@code output}), prints the final AST as a text tree,
 * and writes a debug page of the AST ({@code ast.html}).
 *
 * <pre>
 * usage: App [options] file.md [file.md ...]
 *        App [options] --manifest manifest.yml
 * </pre>
 */
public class App {

    static final String DEFAULT_OUTPUT = "ast.html";

    /**
     * Parsed command line. {@code output} is the AST debug page's path, or {@code "-"} for stdout.
     * Nullable: {@code templates}, {@code draft}, {@code manifest}, {@code site}, {@code layouts}.
     * Either {@code files} or {@code manifest} is set, not both.
     */
    record Options(String output, List<Path> files, Map<String, String> vars, Path templates, Path draft,
                   List<Path> data, List<String> env, Path manifest, Path site, Path layouts, String browser) {
    }

    /** Bad arguments, or {@code --help} when {@link #getMessage()} is null. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        try {
            run(parse(args), System.out, System.err);
        } catch (UsageException e) {
            if (e.getMessage() != null) {
                System.err.println("error: " + e.getMessage());
            }
            printUsage(System.err);
            System.exit(e.getMessage() == null ? 0 : 2);
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static Options parse(String[] args) throws UsageException {
        String output = DEFAULT_OUTPUT;
        List<Path> files = new ArrayList<>();
        Map<String, String> vars = new LinkedHashMap<>();
        Path templates = null;
        Path draft = null;
        List<Path> data = new ArrayList<>();
        List<String> env = new ArrayList<>();
        Path manifest = null;
        Path site = null;
        Path layouts = null;
        String browser = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-o", "--output" -> output = value(args, ++i, arg);
                case "--var" -> {
                    String v = value(args, ++i, arg);
                    int eq = v.indexOf('=');
                    if (eq < 1) {
                        throw new UsageException("--var expects name=value, got: " + v);
                    }
                    String name = v.substring(0, eq);
                    if (MarkdownPipeline.RESERVED.contains(name)) {
                        throw new UsageException("--var name \"" + name + "\" is reserved; reserved names are "
                                + MarkdownPipeline.RESERVED.stream().sorted().toList());
                    }
                    vars.put(name, v.substring(eq + 1));
                }
                case "--templates" -> templates = Path.of(value(args, ++i, arg));
                case "--draft" -> draft = Path.of(value(args, ++i, arg));
                case "--data" -> data.add(Path.of(value(args, ++i, arg)));
                case "--env" -> env.add(value(args, ++i, arg));
                case "--manifest" -> manifest = Path.of(value(args, ++i, arg));
                case "--site" -> site = Path.of(value(args, ++i, arg));
                case "--layouts" -> layouts = Path.of(value(args, ++i, arg));
                case "--browser" -> browser = value(args, ++i, arg);
                case "-h", "--help" -> throw new UsageException(null);
                default -> files.add(Path.of(arg));
            }
        }
        if (manifest != null && !files.isEmpty()) {
            throw new UsageException("give either markdown files or --manifest, not both");
        }
        if (manifest == null && files.isEmpty()) {
            throw new UsageException("no markdown files given (or use --manifest)");
        }
        for (Path f : manifest != null ? List.of(manifest) : files) {
            if (!Files.isRegularFile(f)) {
                throw new UsageException("not a file: " + f);
            }
        }
        return new Options(output, files, vars, templates, draft, data, env, manifest, site, layouts, browser);
    }

    private static String value(String[] args, int i, String option) throws UsageException {
        if (i >= args.length) {
            throw new UsageException("missing value for " + option);
        }
        return args[i];
    }

    /** The command line as a {@link BuildConfig}: a manifest (or the files given), with any flags on top. */
    static BuildConfig config(Options options) throws IOException {
        BuildConfig.Builder b = BuildConfig.builder(Path.of("")).origin("command line");
        if (options.manifest() != null) {
            b.manifest(options.manifest());
        } else {
            b.files(options.files());
        }
        if (options.templates() != null) {
            b.templates(options.templates());
        }
        if (options.layouts() != null) {
            b.layouts(options.layouts());
        }
        if (!options.data().isEmpty()) {
            b.data(options.data());
        }
        if (!options.vars().isEmpty()) {
            b.vars(options.vars());
        }
        if (!options.env().isEmpty()) {
            b.env(options.env());
        }
        if (options.site() != null) {
            b.output(options.site());
        }
        if (options.browser() != null) {
            b.browser(options.browser());
        }
        if (!options.output().equals("-")) {
            b.astOutput(Path.of(options.output()));
        }
        return b.draftOutput(options.draft()).build();
    }

    static void run(Options options, PrintStream out, PrintStream err) throws IOException {
        Runner.Outcome outcome = Runner.run(config(options));
        MarkdownPipeline.Result result = outcome.build().result();
        if (outcome.ast() == null) {
            out.print(AstHtmlRenderer.render(result.ast()));
        } else {
            out.print(AstTextRenderer.render(result.ast().getRoot()));
            out.println();
            out.println("HTML rendering of the AST written to " + outcome.ast().toAbsolutePath());
            for (SiteBuilder.Output output : outcome.build().outputs()) {
                out.println((output.name().equals(dev.noregressions.paperband.layout.Manifest.DEFAULT_OUTPUT_NAME) ? "Site" : "Output " + output.name())
                        + " (" + output.pages().size() + " page(s)) written to " + output.file().toAbsolutePath());
                for (String remedy : output.remedies()) {
                    out.println("  to fit, " + remedy);
                }
            }
        }
        for (String warning : outcome.warnings()) {
            err.println("warning: " + warning);
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: App [options] file.md [file.md ...]");
        err.println("       App [options] --manifest manifest.yml");
        err.println("  --manifest    a manifest: sources, sequence, layouts and output (instead of files)");
        err.println("  --site        where to write the laid-out web page (default: the manifest's output, or "
                + Manifest.DEFAULT_OUTPUT + ")");
        err.println("  -o, --output  where to write the HTML AST debug page (default: "
                + DEFAULT_OUTPUT + "; '-' for stdout)");
        err.println("  --draft       also write the draft AST (after the pre pass, before the post pass) as HTML");
        err.println("  --var         a Pebble template variable, available as {{ name }} and [[ name ]]; repeatable");
        err.println("  --templates   directory for extends and include templates (default: "
                + PebblePreprocessor.DEFAULT_TEMPLATES + ")");
        err.println("  --layouts     directory of layout files (default: layouts)");
        err.println("  --browser     for pdf: an installed browser's channel (chrome, msedge) or executable;");
        err.println("                default: the Chromium dev.noregressions.paperband:chromium downloads (needs it on the classpath)");
        err.println("  --data        a YAML/JSON data file or directory, available as data.<name>; repeatable"
                + " (default: " + DataLoader.DEFAULT_DATA + "/ if it exists)");
        err.println("  --env         expose an environment variable as env.NAME; repeatable");
        err.println("Without --manifest, defaults are relative to the current directory; with one, to the manifest's.");
    }
}
