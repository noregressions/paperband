package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import dev.noregressions.paperband.layout.ConfigException;
import dev.noregressions.paperband.layout.Manifest;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Paperband site. Configure it with a manifest file ({@code <manifest>}), with the
 * settings below, or both: settings given here override the manifest's. The settings are the
 * manifest's, checked by the same rules, so a build behaves the same from here, from a manifest
 * on the command line, or from both.
 *
 * <pre>
 * &lt;plugin&gt;
 *   &lt;groupId&gt;dev.noregressions.paperband&lt;/groupId&gt;
 *   &lt;artifactId&gt;paperband-maven-plugin&lt;/artifactId&gt;
 *   &lt;version&gt;...&lt;/version&gt;
 *   &lt;executions&gt;&lt;execution&gt;&lt;goals&gt;&lt;goal&gt;build&lt;/goal&gt;&lt;/goals&gt;&lt;/execution&gt;&lt;/executions&gt;
 *   &lt;configuration&gt;
 *     &lt;title&gt;Guide&lt;/title&gt;
 *     &lt;sources&gt;&lt;source&gt;docs/**&#47;*.md&lt;/source&gt;&lt;/sources&gt;
 *     &lt;sequence&gt;
 *       &lt;entry&gt;&lt;layout&gt;contents&lt;/layout&gt;&lt;/entry&gt;
 *       &lt;entry&gt;&lt;files&gt;docs/*.md&lt;/files&gt;&lt;sort&gt;page.chapter&lt;/sort&gt;&lt;/entry&gt;
 *     &lt;/sequence&gt;
 *     &lt;vars&gt;&lt;product&gt;Paperband&lt;/product&gt;&lt;/vars&gt;
 *   &lt;/configuration&gt;
 * &lt;/plugin&gt;
 * </pre>
 *
 * The site goes to {@code output}; without one (here or in the manifest) it goes to
 * {@code target/paperband/site.html}. A web site is a page per file: the first page at that file,
 * the others beside it, with the contents beside every page. Several outputs (web, paged-html,
 * pdf) go in {@code <outputs>} instead, each with its own file; a web output's {@code <nav>} puts
 * the contents beside every page ({@code side}, the default), in a bar of dropdowns under the
 * header ({@code top}), or nowhere ({@code none}):
 *
 * <pre>
 * &lt;outputs&gt;
 *   &lt;output&gt;
 *     &lt;name&gt;site&lt;/name&gt;
 *     &lt;format&gt;web&lt;/format&gt;
 *     &lt;file&gt;target/site/index.html&lt;/file&gt;
 *     &lt;nav&gt;top&lt;/nav&gt;
 *     &lt;contents&gt;docs/contents.md&lt;/contents&gt;
 *   &lt;/output&gt;
 * &lt;/outputs&gt;
 * </pre>
 *
 * Paths are relative to the project directory.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class BuildMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    File basedir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    File buildDirectory;

    /** A manifest file to start from; the settings below override its. */
    @Parameter(property = "paperband.manifest")
    File manifest;

    /** The site's title. */
    @Parameter
    String title;

    /** Where to write the site. Default: the manifest's output, else {@code target/paperband/site.html}. */
    @Parameter(property = "paperband.output")
    File output;

    /** Several outputs instead of one (see {@link Output}); each names its own format and file. */
    @Parameter
    List<Output> outputs;

    /** Source patterns, e.g. {@code docs/**&#47;*.md}. Use with {@code sequence}. */
    @Parameter
    List<String> sources;

    /** Exact files that are both the sources and the sequence, in order (instead of sources and sequence). */
    @Parameter
    List<File> files;

    /** The pages to write, in order (see {@link Entry}). */
    @Parameter
    List<Entry> sequence;

    /** Source patterns deliberately left out of the sequence. */
    @Parameter
    List<String> hidden;

    /** What to do about sources neither in the sequence nor hidden: warn, error or ignore. */
    @Parameter
    String unused;

    /** Stylesheets inlined into the site. */
    @Parameter
    List<File> stylesheets;

    /** Directory for Pebble {@code extends} and {@code include} templates. */
    @Parameter
    File templates;

    /** Directory of layout files. */
    @Parameter
    File layouts;

    /** YAML/JSON data files or directories, available to templates as {@code data}. */
    @Parameter
    List<File> data;

    /** Template variables, e.g. {@code <vars><product>Paperband</product></vars>}. */
    @Parameter
    Map<String, String> vars;

    /** Names of environment variables to expose to templates as {@code env}. */
    @Parameter
    List<String> env;

    /** The layout for files without their own. */
    @Parameter
    String defaultLayout;

    /** The default page, as YAML, e.g. {@code { size: A4, margin: 15mm }}. */
    @Parameter
    String defaultPage;

    /** Also write the final AST as an HTML debug page here. */
    @Parameter(property = "paperband.astOutput")
    File astOutput;

    /** Also write the draft AST (after the pre pass) as an HTML debug page here. */
    @Parameter(property = "paperband.draftOutput")
    File draftOutput;

    /**
     * Fixes {@code build.date} for reproducible output: an ISO-8601 instant
     * ({@code 2026-10-03T12:00:00Z}) or seconds since 1970, as Maven's reproducible builds use.
     * Defaults to {@code project.build.outputTimestamp}; unset means the current time.
     */
    @Parameter(property = "paperband.buildDate", defaultValue = "${project.build.outputTimestamp}")
    String buildDate;

    /**
     * For pdf outputs: an installed browser's channel ({@code chrome}, {@code msedge}) or its
     * executable. Default: the Chromium that dev.noregressions.paperband:chromium downloads. Pdf needs dev.noregressions.paperband:chromium
     * as a dependency of this plugin.
     */
    @Parameter(property = "paperband.browser")
    String browser;

    /** Fail the build if there are any warnings. */
    @Parameter(property = "paperband.failOnWarnings", defaultValue = "false")
    boolean failOnWarnings;

    /** Skip the build. */
    @Parameter(property = "paperband.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Paperband: skipped");
            return;
        }
        Runner.Outcome outcome;
        try {
            outcome = Runner.run(config());
        } catch (ConfigException e) {
            throw new MojoFailureException("Paperband: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Paperband: " + e.getMessage(), e);
        }
        for (String warning : outcome.warnings()) {
            getLog().warn("Paperband: " + warning);
        }
        for (dev.noregressions.paperband.SiteBuilder.Output output : outcome.build().outputs()) {
            getLog().info("Paperband: " + (output.name().equals(Manifest.DEFAULT_OUTPUT_NAME) ? "site" : "output " + output.name())
                    + " (" + output.pages().size() + " page(s)) written to " + output.file());
            for (String remedy : output.remedies()) {
                getLog().info("Paperband:   to fit, " + remedy);
            }
        }
        if (failOnWarnings && !outcome.warnings().isEmpty()) {
            throw new MojoFailureException("Paperband: " + outcome.warnings().size() + " warning(s), and failOnWarnings is set");
        }
    }

    /** This plugin's configuration as a {@link BuildConfig}. */
    BuildConfig config() throws IOException {
        Path base = basedir.toPath();
        BuildConfig.Builder b = BuildConfig.builder(base).origin("pom.xml");
        if (manifest != null) {
            b.manifest(manifest.toPath());
        }
        if (title != null) {
            b.title(title);
        }
        if (browser != null) {
            b.browser(browser);
        }
        if (outputs != null && !outputs.isEmpty()) {
            b.set("outputs", outputMaps());
        } else if (output != null) {
            b.output(output.toPath());
        } else if (manifest == null || !(Manifest.readYaml(manifest.toPath()) instanceof Map<?, ?> m
                && (m.containsKey("output") || m.containsKey("outputs")))) {
            b.output(buildDirectory.toPath().resolve("paperband/site.html"));
        }
        if (sources != null && !sources.isEmpty()) {
            b.sources(sources);
        }
        if (files != null && !files.isEmpty()) {
            b.files(paths(files));
        }
        if (sequence != null && !sequence.isEmpty()) {
            b.sequence(entries());
        }
        if (hidden != null && !hidden.isEmpty()) {
            b.hidden(hidden);
        }
        if (unused != null) {
            b.unused(unused);
        }
        if (stylesheets != null && !stylesheets.isEmpty()) {
            b.stylesheets(paths(stylesheets));
        }
        if (templates != null) {
            b.templates(templates.toPath());
        }
        if (layouts != null) {
            b.layouts(layouts.toPath());
        }
        if (data != null && !data.isEmpty()) {
            b.data(paths(data));
        }
        if (vars != null && !vars.isEmpty()) {
            b.vars(vars);
        }
        if (env != null && !env.isEmpty()) {
            b.env(env);
        }
        if (defaultLayout != null) {
            b.defaultLayout(defaultLayout);
        }
        if (defaultPage != null) {
            b.defaultPage(yaml(defaultPage, "defaultPage"));
        }
        if (astOutput != null) {
            b.astOutput(astOutput.toPath());
        }
        if (draftOutput != null) {
            b.draftOutput(draftOutput.toPath());
        }
        if (buildDate != null && !buildDate.isBlank()) {
            b.clock(Clock.fixed(instant(buildDate), ZoneOffset.UTC));
        }
        return b.build();
    }

    private List<Map<String, Object>> outputMaps() throws ConfigException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            Output o = outputs.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            putIfSet(m, "name", o.name);
            putIfSet(m, "format", o.format);
            putIfSet(m, "file", o.file == null ? null : o.file.toPath());
            putIfSet(m, "layout", o.layout);
            putIfSet(m, "prefer", o.prefer);
            putIfSet(m, "single", o.single);
            putIfSet(m, "nav", o.nav);
            putIfSet(m, "contents", o.contents);
            if (o.layouts != null && !o.layouts.isEmpty()) {
                m.put("layouts", new LinkedHashMap<>(o.layouts));
            }
            if (o.page != null) {
                m.put("page", yaml(o.page, "outputs[" + i + "].page"));
            }
            if (o.sequence != null && !o.sequence.isEmpty()) {
                m.put("sequence", entries(o.sequence, "outputs[" + i + "].sequence"));
            }
            out.add(m);
        }
        return out;
    }

    private static void putIfSet(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private List<Map<String, Object>> entries() throws ConfigException {
        return entries(sequence, "sequence");
    }

    private static List<Map<String, Object>> entries(List<Entry> sequence, String where) throws ConfigException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            Entry e = sequence.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            if (e.file != null) {
                m.put("file", e.file);
            }
            if (e.files != null) {
                m.put("files", e.files);
            }
            if (e.layout != null) {
                m.put("layout", e.layout);
            }
            if (e.where != null) {
                m.put("where", yaml(e.where, where + "[" + i + "].where"));
            }
            if (e.sort != null) {
                m.put("sort", yaml(e.sort, where + "[" + i + "].sort"));
            }
            out.add(m);
        }
        return out;
    }

    /** YAML text from the XML, parsed; it's checked later along with everything else. */
    private static Object yaml(String text, String where) throws ConfigException {
        try {
            return FrontMatter.parseYaml(text);
        } catch (RuntimeException e) {
            throw new ConfigException("pom.xml: " + where + ": invalid YAML: " + e.getMessage(), e);
        }
    }

    private static Instant instant(String text) throws ConfigException {
        try {
            return text.strip().matches("\\d+") ? Instant.ofEpochSecond(Long.parseLong(text.strip())) : Instant.parse(text.strip());
        } catch (RuntimeException e) {
            throw new ConfigException("pom.xml: buildDate: \"" + text + "\" isn't an ISO-8601 instant"
                    + " (e.g. 2026-10-03T12:00:00Z) or seconds since 1970");
        }
    }

    private static List<Path> paths(List<File> files) {
        return files.stream().map(File::toPath).toList();
    }
}
