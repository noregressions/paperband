package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.MarkdownPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a build does: which markdown files are parsed into the AST ({@code sources}), which of
 * them are written out and in what order ({@code sequence}), with which layouts, and the
 * settings around that. Paths are relative to the manifest's directory.
 * <p>
 * A manifest usually comes from a YAML file ({@link #load}), but the same settings can come from
 * anywhere that produces the same structure ({@link #fromSettings}): the command line and the
 * Maven plugin build them with {@link dev.noregressions.paperband.config.BuildConfig}. Either way they're checked
 * by the same rules.
 *
 * <pre>
 * title: Paperband Guide
 * output: build/site.html          # default: site.html
 * stylesheets: [styles/site.css]   # inlined into the output
 * templates: templates             # these three default to the same names
 * layouts: layouts
 * data: data                       # or a list of data files/directories
 * vars: { product: Paperband }       # top-level template variables
 * env: [CI]                        # environment variables exposed as env.NAME
 * index: index.yml                 # index terms found by rule (IndexSpec); markers work without it
 * copy: [command, bash]            # in web output, code blocks in these languages get a Copy button
 * browser: chrome                  # for pdf: an installed browser's channel (chrome, msedge) or executable;
 *                                  #   default: the Chromium dev.noregressions.paperband:chromium downloads
 *
 * sources: [docs/**&#47;*.md]       # parsed, and visible to templates through `site`
 * hidden: [docs/partials/*.md]     # sources deliberately left out of the sequence
 * unused: warn                     # warn | error | ignore, for sources neither in sequence nor hidden
 *
 * defaults:
 *   page: { size: A4, margin: 15mm }
 *   layout: default
 *
 * sequence:
 *   - layout: contents             # a generated page: a layout with no source file
 *   - file: docs/intro.md
 *   - files: docs/chapters/*.md
 *     where: { draft: false }
 *     sort: page.chapter
 *     layout: chapter              # overrides the files' own layout: front matter
 * </pre>
 *
 * Instead of {@code sources} and {@code sequence}, {@code files: [a.md, b.md]} names exact files
 * that are both the sources and the sequence, in that order, each with its own layout.
 * <p>
 * One build can make several outputs from the same sources (templating runs once; layout and
 * rendering run per output). Without {@code outputs}, there is one web output named
 * {@value #DEFAULT_OUTPUT_NAME}, written to {@code output}:
 *
 * <pre>
 * outputs:
 *   - name: handbook                # letters, digits, -; unique
 *     format: paged-html            # web | paged-html | pdf
 *     file: build/handbook.html
 *     layout: tutorial-full         # pages from source files use this, unless their entry names one
 *     layouts: { hero: hero-print } # or: replace layouts by name, on every page
 *     page: { size: A4, margin: 15mm }
 *     sequence: [...]               # optional: this output's own pages; default: the manifest's
 *     prefer: short                 # the variant shown of each variants group (a layout's prefer wins)
 *     single: true                  # web only: everything in one HTML file instead of a page per file
 *     nav: side                     # web only: the contents beside every page (default), a bar of
 *                                   #   dropdowns under the header (top), or none
 *     contents: front/contents.md   # web, with nav: side or top: the page shown as the navigation instead of on its own,
 *                                   #   by file or layout name; default: a page whose layout is contents
 * </pre>
 */
public final class Manifest {

    public static final String DEFAULT_LAYOUT = "default";
    public static final String DEFAULT_OUTPUT = "site.html";

    public enum Unused { WARN, ERROR, IGNORE }

    public sealed interface Entry permits FileEntry, FilesEntry, GeneratedEntry {
        String context();
    }

    /** One named source file. */
    public record FileEntry(String context, Path file, String layout) implements Entry {
    }

    /** Every source matching a pattern, optionally filtered and sorted. */
    public record FilesEntry(String context, String pattern, Where where, SortSpec sort, String layout) implements Entry {
    }

    /** A page with no source file, filled entirely by its layout. */
    public record GeneratedEntry(String context, String layout) implements Entry {
    }

    public static final String DEFAULT_OUTPUT_NAME = "site";

    /** The output formats. */
    public enum Format {
        /** A web site: one HTML file per page, with shared CSS and links between them ({@link WebSite}). */
        WEB,
        /** Fixed-size pages in HTML, for print and for measuring in a browser. */
        PAGED_HTML,
        /** PDF, printed in Chromium. */
        PDF;

        public String id() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /**
     * One thing a build writes.
     *
     * @param layout   the layout for pages from source files whose sequence entry names none, or null
     * @param layouts  layout names replaced on every page of this output (empty for none)
     * @param page     this output's default page, or null for the manifest's
     * @param sequence this output's own pages, or null for the manifest's sequence
     * @param single   for web: one HTML file holding every page, instead of a site with a file per page
     * @param nav      for web: how the site is navigated; null for other formats
     */
    public record Output(String context, String name, Format format, Path file, String layout,
                         Map<String, String> layouts, PageSpec page, List<Entry> sequence, dev.noregressions.paperband.Variants.Variant prefer,
                         boolean single, WebSite.Options nav) {
    }

    private final Path file;
    private final Path dir;
    private final String name;
    private final String title;
    private final Path output;
    private final List<Path> stylesheets;
    private final Path templates;
    private final Path layouts;
    private final boolean layoutsGiven;
    private final List<Path> data;
    private final boolean dataGiven;
    private final Map<String, String> vars;
    private final List<String> env;
    private final List<String> sourcePatterns;
    private final List<Path> explicitSources;
    private final List<String> hidden;
    private final Unused unused;
    private final PageSpec defaultPage;
    private final String defaultLayout;
    private final List<Entry> sequence;
    private final List<Output> outputs;
    private final String browser;
    private final Path index;
    private final List<String> copy;

    private Manifest(Path file, Path dir, String name, String title, Path output, List<Path> stylesheets,
                     Path templates, Path layouts, boolean layoutsGiven, List<Path> data, boolean dataGiven,
                     Map<String, String> vars, List<String> env, List<String> sourcePatterns, List<Path> explicitSources,
                     List<String> hidden, Unused unused, PageSpec defaultPage, String defaultLayout, List<Entry> sequence,
                     List<Output> outputs, String browser, Path index, List<String> copy) {
        this.file = file;
        this.dir = dir;
        this.name = name;
        this.title = title;
        this.output = output;
        this.stylesheets = stylesheets;
        this.templates = templates;
        this.layouts = layouts;
        this.layoutsGiven = layoutsGiven;
        this.data = data;
        this.dataGiven = dataGiven;
        this.vars = vars;
        this.env = env;
        this.sourcePatterns = sourcePatterns;
        this.explicitSources = explicitSources;
        this.hidden = hidden;
        this.unused = unused;
        this.defaultPage = defaultPage;
        this.defaultLayout = defaultLayout;
        this.sequence = sequence;
        this.outputs = outputs;
        this.browser = browser;
        this.index = index;
        this.copy = copy;
    }

    /** Reads a manifest file; its directory is what relative paths in it are relative to. */
    public static Manifest load(Path file) throws IOException {
        return fromSettings(readYaml(file), file.toAbsolutePath().getParent(), file.getFileName().toString(), file);
    }

    /** A manifest file's settings, parsed but not yet checked. */
    public static Object readYaml(Path file) throws IOException {
        try {
            return FrontMatter.parseYaml(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ConfigException(file.getFileName() + ": invalid YAML: " + e.getMessage(), e);
        }
    }

    /**
     * A manifest from settings in the manifest file's structure (maps, lists, strings), however
     * they were produced.
     *
     * @param dir  what relative paths are relative to
     * @param name how to refer to the settings in messages, e.g. {@code manifest.yml} or {@code pom.xml}
     * @param file the manifest file they came from, or null
     */
    public static Manifest fromSettings(Object settings, Path dir, String name, Path file) throws ConfigException {
        YamlSpec spec = YamlSpec.root(settings == null ? Map.of() : settings, name);

        String title = spec.string("title", "Site");
        boolean outputGiven = spec.has("output");
        Path output = Path.of(spec.string("output", DEFAULT_OUTPUT));
        List<Path> stylesheets = spec.strings("stylesheets").stream().map(Path::of).toList();
        Path templates = Path.of(spec.string("templates", "templates"));
        boolean layoutsGiven = spec.has("layouts");
        Path layouts = Path.of(spec.string("layouts", "layouts"));
        boolean dataGiven = spec.has("data");
        List<Path> data = (dataGiven ? spec.strings("data") : List.of("data")).stream().map(Path::of).toList();
        Map<String, String> vars = vars(spec);
        List<String> env = spec.strings("env");
        String browser = spec.string("browser", null);
        String index = spec.string("index", null);
        List<String> copy = spec.strings("copy");
        for (String language : copy) {
            if (!language.matches("[A-Za-z0-9_+-]+")) {
                throw spec.error("copy", "\"" + language + "\" isn't a code fence language, such as bash or command");
            }
        }
        if (browser != null && browser.isBlank()) {
            throw spec.error("browser", "expected a browser channel (e.g. chrome) or the path to a browser executable");
        }

        List<String> explicit = spec.strings("files");
        List<String> sources = spec.strings("sources");
        boolean hasSequence = spec.has("sequence");
        if (!explicit.isEmpty() && (!sources.isEmpty() || hasSequence)) {
            throw new ConfigException(name + ": use either files, or sources and sequence, not both");
        }
        if (explicit.isEmpty() && sources.isEmpty()) {
            throw new ConfigException(name + ": missing required key \"sources\" (or \"files\")");
        }
        List<String> hidden = spec.strings("hidden");
        Unused unused = SortSpec.choice(Unused.class, spec.string("unused", "warn"), spec.child("unused"));

        PageSpec page = PageSpec.A4;
        String defaultLayout = DEFAULT_LAYOUT;
        YamlSpec defaults = spec.map("defaults");
        if (defaults != null) {
            YamlSpec p = defaults.map("page");
            if (p != null) {
                page = PageSpec.parse(p, PageSpec.A4);
            }
            defaultLayout = defaults.string("layout", DEFAULT_LAYOUT);
            defaults.finish();
        }

        List<Entry> sequence = new ArrayList<>();
        List<Path> explicitSources = null;
        if (!explicit.isEmpty()) {
            explicitSources = explicit.stream().map(f -> Path.of(f).normalize()).toList();
            for (int i = 0; i < explicitSources.size(); i++) {
                sequence.add(new FileEntry(spec.child("files") + "[" + i + "]", explicitSources.get(i), null));
            }
        } else {
            List<YamlSpec> entries = spec.maps("sequence");
            if (entries.isEmpty()) {
                throw new ConfigException(name + ": missing required key \"sequence\"");
            }
            for (YamlSpec e : entries) {
                sequence.add(entry(e));
            }
        }
        List<Output> outputs = new ArrayList<>();
        List<YamlSpec> outputSpecs = spec.maps("outputs");
        if (!outputSpecs.isEmpty() && outputGiven) {
            throw new ConfigException(name + ": use either output or outputs (each output names its own file)");
        }
        for (YamlSpec o : outputSpecs) {
            Output out = output(o, page);
            if (outputs.stream().anyMatch(other -> other.name().equals(out.name()))) {
                throw new ConfigException(o.context() + ": another output is already named \"" + out.name() + "\"");
            }
            outputs.add(out);
        }
        if (outputs.isEmpty()) {
            outputs.add(new Output(name + ": output", DEFAULT_OUTPUT_NAME, Format.WEB, output, null, Map.of(), null, null, null,
                    false, WebSite.Options.DEFAULT));
        }
        spec.finish();
        return new Manifest(file, dir, name, title, output, stylesheets, templates, layouts, layoutsGiven, data,
                dataGiven, vars, env, sources, explicitSources, hidden, unused, page, defaultLayout, List.copyOf(sequence),
                List.copyOf(outputs), browser, index == null ? null : Path.of(index), List.copyOf(copy));
    }

    private static final java.util.regex.Pattern OUTPUT_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    private static Output output(YamlSpec o, PageSpec manifestPage) throws ConfigException {
        String outName = o.requiredString("name");
        if (!OUTPUT_NAME.matcher(outName).matches()) {
            throw o.error("name", "\"" + outName + "\" must start with a letter and use only letters, digits and -");
        }
        String formatName = o.requiredString("format");
        Format format = null;
        for (Format f : Format.values()) {
            if (f.id().equals(formatName)) {
                format = f;
            }
        }
        if (format == null) {
            throw o.error("format", "\"" + formatName + "\" isn't one of [web, paged-html, pdf]");
        }
        Path outFile = Path.of(o.requiredString("file"));
        String layout = o.string("layout", null);
        Map<String, String> renames = new LinkedHashMap<>();
        YamlSpec ls = o.map("layouts");
        if (ls != null) {
            for (String from : ls.keys()) {
                Object to = ls.raw(from);
                if (!(to instanceof String toName)) {
                    throw ls.error(from, "expected a layout name, got " + YamlSpec.describe(to));
                }
                renames.put(from, toName);
            }
        }
        if (layout != null && !renames.isEmpty()) {
            throw new ConfigException(o.context() + ": use either layout or layouts, not both");
        }
        YamlSpec pageSpec = o.map("page");
        PageSpec outPage = pageSpec == null ? null : PageSpec.parse(pageSpec, manifestPage);
        List<Entry> outSequence = null;
        if (o.has("sequence")) {
            outSequence = new ArrayList<>();
            for (YamlSpec e : o.maps("sequence")) {
                outSequence.add(entry(e));
            }
            if (outSequence.isEmpty()) {
                throw new ConfigException(o.context() + ": sequence is empty");
            }
            outSequence = List.copyOf(outSequence);
        }
        dev.noregressions.paperband.Variants.Variant prefer = Layout.prefer(o);
        boolean single = o.bool("single", false);
        if (single && format != Format.WEB) {
            throw o.error("single", "only a web output can be single; " + format.id() + " is already one file");
        }
        WebSite.Options nav = null;
        if (format == Format.WEB) {
            String style = o.string("nav", WebSite.Style.SIDE.id());
            WebSite.Style navStyle = java.util.Arrays.stream(WebSite.Style.values())
                    .filter(st -> st.id().equals(style)).findFirst()
                    .orElseThrow(() -> o.error("nav", "\"" + style + "\" isn't one of [side, top, none]"));
            String contents = o.string("contents", null);
            if (contents != null && navStyle == WebSite.Style.NONE) {
                throw o.error("contents", "the contents is shown as the navigation, and nav is none");
            }
            nav = new WebSite.Options(navStyle, contents);
        } else {
            for (String key : List.of("nav", "contents")) {
                if (o.has(key)) {
                    throw o.error(key, "only a web output has " + key + "; " + format.id() + " has pages, not navigation");
                }
            }
        }
        o.finish();
        return new Output(o.context(), outName, format, outFile, layout, Map.copyOf(renames), outPage, outSequence, prefer,
                single, nav);
    }

    private static Map<String, String> vars(YamlSpec spec) throws ConfigException {
        YamlSpec v = spec.map("vars");
        if (v == null) {
            return Map.of();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (String key : v.keys()) {
            if (MarkdownPipeline.RESERVED.contains(key)) {
                throw v.error(key, "\"" + key + "\" is reserved; reserved names are "
                        + MarkdownPipeline.RESERVED.stream().sorted().toList());
            }
            Object value = v.raw(key);
            if (value instanceof Map || value instanceof List || value == null) {
                throw v.error(key, "expected a single value, got " + YamlSpec.describe(value));
            }
            vars.put(key, value.toString());
        }
        return Collections.unmodifiableMap(vars);
    }

    private static Entry entry(YamlSpec e) throws ConfigException {
        String layout = e.string("layout", null);
        String single = e.string("file", null);
        String pattern = e.string("files", null);
        YamlSpec whereSpec = e.map("where");
        Object sortValue = e.raw("sort");
        e.finish();

        if (single != null && pattern != null) {
            throw new ConfigException(e.context() + ": use either file or files, not both");
        }
        if (pattern == null && (whereSpec != null || sortValue != null)) {
            throw new ConfigException(e.context() + ": where and sort only apply to files: patterns");
        }
        if (single != null) {
            return new FileEntry(e.context(), Path.of(single).normalize(), layout);
        }
        if (pattern != null) {
            Where where = whereSpec == null ? null : Where.parse(whereSpec);
            SortSpec sort = sortValue == null ? SortSpec.BY_PATH : SortSpec.parse(sortValue, e.child("sort"));
            return new FilesEntry(e.context(), pattern, where, sort, layout);
        }
        if (layout == null) {
            throw new ConfigException(e.context() + ": expected file, files, or a layout for a generated page");
        }
        return new GeneratedEntry(e.context(), layout);
    }

    /**
     * The manifest used when files are given on the command line instead: those files are both
     * the sources and the sequence, in order, each using its own {@code layout:} front matter.
     */
    public static Manifest implicit(List<Path> files) throws ConfigException {
        return implicit(Path.of(""), files);
    }

    /** Like {@link #implicit(List)}, with {@code files} relative to {@code dir}. */
    public static Manifest implicit(Path dir, List<Path> files) throws ConfigException {
        return fromSettings(Map.of("files", files.stream().map(Path::toString).toList()), dir, "command line", null);
    }

    /** Source files, relative to {@link #dir()}, in pattern order then path order, without duplicates. */
    public List<Path> resolveSources() throws IOException {
        if (explicitSources != null) {
            return explicitSources;
        }
        List<Path> all = Globs.files(dir);
        Set<Path> out = new LinkedHashSet<>();
        for (String pattern : sourcePatterns) {
            Predicate<Path> m = Globs.matcher(pattern);
            List<Path> matched = all.stream().filter(m).toList();
            if (matched.isEmpty()) {
                throw new ConfigException(name + ": sources pattern \"" + pattern + "\" matches no files in " + dir);
            }
            out.addAll(matched);
        }
        return List.copyOf(out);
    }

    /** The index terms file ({@link IndexSpec}), relative to {@link #dir()}; null if there's none. */
    public Path index() {
        return index;
    }

    /** Fence languages whose code blocks get a copy button in web output ({@code copy: [command]}). */
    public List<String> copy() {
        return copy;
    }

    /** How the settings are referred to in messages. */
    public String name() {
        return name;
    }

    /** The manifest file, or null when the settings didn't come from one. */
    public Path file() {
        return file;
    }

    public Path dir() {
        return dir;
    }

    /** For pdf: the browser channel or executable to use, or null for the renderer's own. */
    public String browser() {
        return browser;
    }

    public String title() {
        return title;
    }

    /** Resolved against {@link #dir()}. */
    public Path output() {
        return dir.resolve(output);
    }

    public List<Path> stylesheets() {
        return stylesheets.stream().map(dir::resolve).toList();
    }

    public Path templates() {
        return dir.resolve(templates);
    }

    public Path layouts() {
        return dir.resolve(layouts);
    }

    /** True if {@code layouts} was set, so a missing directory is an error rather than "no layouts". */
    public boolean layoutsGiven() {
        return layoutsGiven;
    }

    /** Data files and directories, resolved against {@link #dir()}. */
    public List<Path> data() {
        return data.stream().map(dir::resolve).toList();
    }

    /** True if {@code data} was set, so missing paths are an error rather than "no data". */
    public boolean dataGiven() {
        return dataGiven;
    }

    public Map<String, String> vars() {
        return vars;
    }

    /** Names of the environment variables to expose as {@code env}. */
    public List<String> env() {
        return env;
    }

    public List<String> hidden() {
        return hidden;
    }

    public Unused unused() {
        return unused;
    }

    public PageSpec defaultPage() {
        return defaultPage;
    }

    public String defaultLayout() {
        return defaultLayout;
    }

    public List<Entry> sequence() {
        return sequence;
    }

    /** What the build writes; at least one. Files resolve against {@link #dir()}. */
    public List<Output> outputs() {
        return outputs;
    }
}
