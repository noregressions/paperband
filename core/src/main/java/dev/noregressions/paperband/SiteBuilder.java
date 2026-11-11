package dev.noregressions.paperband;

import dev.noregressions.paperband.browser.Browser;
import dev.noregressions.paperband.browser.BrowserProvider;
import dev.noregressions.paperband.fit.Fitter;
import dev.noregressions.paperband.fit.Measurement;
import dev.noregressions.paperband.fit.Problem;
import dev.noregressions.paperband.layout.ConfigException;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.Layouts;
import dev.noregressions.paperband.layout.Manifest;
import dev.noregressions.paperband.layout.Page;
import dev.noregressions.paperband.layout.PageSpec;
import dev.noregressions.paperband.layout.ResolvedSequence;
import dev.noregressions.paperband.layout.RunReport;
import dev.noregressions.paperband.layout.SequenceResolver;
import dev.noregressions.paperband.layout.SiteHtmlRenderer;
import dev.noregressions.paperband.layout.Theme;
import dev.noregressions.paperband.layout.WebSite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole build, from a {@link Manifest} to the finished web page:
 *
 * <ol>
 *   <li>resolve the sources and read their front matter;</li>
 *   <li>load the layouts and resolve the sequence, checking everything before templating;</li>
 *   <li>run the {@link MarkdownPipeline} (pre pass, draft AST, post pass, final AST);</li>
 *   <li>for each output: lay the final AST out into pages ({@link LayoutEngine}) and render them
 *       ({@link SiteHtmlRenderer}, flowing for web, fixed-size for paged-html and pdf), and split a
 *       web output into a page per file ({@link WebSite}) unless it's single;</li>
 *   <li>load each paged output (paged-html, pdf) in a browser (one per build, from the
 *       {@link BrowserProvider} on the classpath: {@code dev.noregressions.paperband:chromium}), measure every box,
 *       and try the layouts' remedies until it fits or nothing is left ({@link Fitter}); print the
 *       pdf outputs from those same pages.
 *       Without a browser, paged-html isn't measured, with a warning.</li>
 * </ol>
 * The build doesn't fail for problems with the fit: each output carries them, and
 * {@link dev.noregressions.paperband.config.Runner} writes the fit report and fails instead of writing outputs.
 */
public final class SiteBuilder {


    /**
     * @param manifest the manifest built from
     * @param sequence the manifest's sequence, resolved
     * @param result   the draft and final ASTs
     * @param outputs  each output, laid out and rendered
     * @param report   what the build warned about
     */
    public record Build(Manifest manifest, ResolvedSequence sequence, MarkdownPipeline.Result result,
                        List<Output> outputs, RunReport report) {

        /** The first output's pages (the only output, without {@code outputs:}). */
        public List<Page> pages() {
            return outputs.getFirst().pages();
        }

        /** The first output's document. */
        public String html() {
            return outputs.getFirst().html();
        }
    }

    /**
     * One output, laid out and rendered.
     *
     * @param file  where it is to be written (resolved against the manifest's directory)
     * @param pages its pages
     * @param html  the rendered document (for pdf, the paged HTML that was printed)
     * @param pdf   the printed PDF, for a pdf output; else null
     * @param measurement what the browser measured, for a measured paged output; else null
     * @param problems    what doesn't fit, from the measurement, with the remedies tried
     * @param remedies    what remedies did to make it fit, page by page
     * @param site        for a web output that isn't single, its files ({@link WebSite}) by path relative to
     *                    {@code file}'s directory; else null
     */
    public record Output(String name, Manifest.Format format, Path file, ResolvedSequence sequence, List<Page> pages,
                         String html, byte[] pdf, Measurement measurement, List<Problem> problems, List<String> remedies,
                         Map<Path, String> site) {
    }

    private SiteBuilder() {
    }

    /** The manifest's browser setting, a path resolved against its directory; null for the renderer's own. */
    private static String browser(Manifest manifest) {
        String b = manifest.browser();
        return b != null && (b.contains("/") || b.contains("\\")) ? manifest.dir().resolve(b).toString() : b;
    }

    /** Builds with the system clock. */
    public static Build build(Manifest manifest) throws IOException {
        return build(manifest, Clock.systemDefaultZone());
    }

    /** @param clock the source of {@code build.date} */
    public static Build build(Manifest manifest, Clock clock) throws IOException {
        RunReport report = new RunReport();

        List<Path> sources = manifest.resolveSources();
        List<MarkdownPipeline.Source> inputs = new ArrayList<>();
        Map<Path, Map<String, Object>> frontMatter = new LinkedHashMap<>();
        for (Path p : sources) {
            Path file = manifest.dir().resolve(p);
            if (!Files.isRegularFile(file)) {
                throw new ConfigException(manifest.name() + ": source " + p + " isn't a file");
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            inputs.add(new MarkdownPipeline.Source(p, text));
            frontMatter.put(p.normalize(), FrontMatter.split(p, text).data());
        }

        Path layoutsDir = manifest.layouts();
        if (manifest.layoutsGiven() && !Files.isDirectory(layoutsDir)) {
            throw new ConfigException(manifest.name() + ": layouts directory not found: " + layoutsDir);
        }
        Theme theme = manifest.theme() == null ? Theme.DEFAULT : Theme.resolve(manifest.theme(), manifest.dir(), manifest.name());
        // The theme's layouts first: the project's own replace them by name.
        Layouts layouts = Layouts.load(theme.layouts() == null ? List.of(layoutsDir) : List.of(theme.layouts(), layoutsDir),
                        manifest.defaultPage())
                .withInline(frontMatter, manifest.dir(), manifest.defaultPage());
        ResolvedSequence sequence = SequenceResolver.resolve(manifest, sources, frontMatter, layouts.names(), report);
        // Every output is worked out and checked before any templating runs.
        Map<Manifest.Output, ResolvedSequence> sequences = new LinkedHashMap<>();
        BrowserProvider browsers = BrowserProvider.find().orElse(null);
        for (Manifest.Output output : manifest.outputs()) {
            if (output.format() == Manifest.Format.PDF && browsers == null) {
                throw new ConfigException(output.context() + ": format pdf needs dev.noregressions.paperband:chromium on the classpath"
                        + " (in a Maven build, add it as a dependency of paperband-maven-plugin)");
            }
            if (output.format() == Manifest.Format.PAGED_HTML && browsers == null) {
                report.warn(output.context() + ": output " + output.name() + " isn't measured, so nothing checks that"
                        + " it fits: dev.noregressions.paperband:chromium isn't on the classpath");
            }
            ResolvedSequence outSequence = SequenceResolver.forOutput(manifest, output, sequence, sources, frontMatter,
                    layouts.names(), report);
            LayoutEngine.validate(outSequence, layouts);
            sequences.put(output, outSequence);
        }

        List<Path> dataRoots = manifest.dataGiven() ? manifest.data()
                : manifest.data().stream().filter(Files::isDirectory).toList();
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : manifest.env()) {
            String value = System.getenv(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        Map<String, Object> data = DataLoader.load(dataRoots);
        MarkdownPipeline.Config config = new MarkdownPipeline.Config(manifest.templates(), manifest.vars(), data, env,
                clock).withAutoIndex(dev.noregressions.paperband.layout.IndexSpec.load(manifest));
        MarkdownPipeline.Result result = new MarkdownPipeline(config).run(inputs, sequence.files());

        List<SiteHtmlRenderer.Stylesheet> css = new ArrayList<>();
        for (Path s : manifest.stylesheets()) {
            if (!Files.isRegularFile(s)) {
                throw new ConfigException(manifest.name() + ": stylesheet not found: " + s);
            }
            css.add(new SiteHtmlRenderer.Stylesheet(manifest.dir().relativize(s.toAbsolutePath()).toString(),
                    Files.readString(s, StandardCharsets.UTF_8)));
        }
        List<Output> outputs = new ArrayList<>();
        Browser browser = null;
        try {
            for (var e : sequences.entrySet()) {
                Manifest.Output output = e.getKey();
                PageSpec page = output.page() != null ? output.page() : manifest.defaultPage();
                List<Page> pages = LayoutEngine.build(e.getValue(), result.ast(), result.texts(), layouts, page,
                        manifest.defaultLayout(), output.name(), output.prefer(), output.format() != Manifest.Format.WEB,
                        Map.of(), data, report);
                SiteHtmlRenderer.Mode mode = output.format() == Manifest.Format.WEB
                        ? SiteHtmlRenderer.Mode.WEB : SiteHtmlRenderer.Mode.PAGED;
                String html = SiteHtmlRenderer.render(manifest.title(), css, pages, mode, null, manifest.copy(), theme);
                Path file = manifest.dir().resolve(output.file());
                byte[] pdf = null;
                Measurement measurement = null;
                List<Problem> problems = List.of();
                List<String> remedies = List.of();
                if (mode == SiteHtmlRenderer.Mode.PAGED && browsers != null) {
                    if (browser == null) {
                        browser = browsers.open(browser(manifest));
                    }
                    List<Page> first = pages;
                    ResolvedSequence outSequence = e.getValue();
                    Fitter.Layouter layouter = new Fitter.Layouter() {
                        @Override
                        public List<Page> layOut(Map<Integer, String> layoutFor) throws IOException {
                            // Laid out again with fallback layouts: its warnings were already reported.
                            return layoutFor.isEmpty() ? first : LayoutEngine.build(outSequence, result.ast(),
                                    result.texts(), layouts, page, manifest.defaultLayout(), output.name(),
                                    output.prefer(), true, layoutFor, data, new RunReport());
                        }

                        @Override
                        public Page continuation(Page from, String layout, String into, String id) throws IOException {
                            return LayoutEngine.continuation(from, layout, into, id, outSequence, result.ast(),
                                    result.texts(), layouts, page, manifest.defaultLayout(), output.name(),
                                    output.prefer(), data);
                        }
                    };
                    Fitter.Result fit = Fitter.fit(layouter,
                            (fitPages, fitCss) -> SiteHtmlRenderer.render(manifest.title(), css, fitPages, mode, fitCss,
                                    List.of(), theme),
                            browser, file.toAbsolutePath().getParent(), output.format() == Manifest.Format.PDF);
                    pages = fit.pages();
                    html = fit.html();
                    pdf = fit.rendering().pdf();
                    measurement = fit.rendering().measurement();
                    problems = fit.problems();
                    remedies = fit.remedies();
                }
                List<String> broken = brokenLinks(html);
                if (!broken.isEmpty()) {
                    report.warn(output.name() + ": " + broken.size() + " link(s) to an id that isn't in the document: #"
                            + String.join(", #", broken.subList(0, Math.min(5, broken.size())))
                            + (broken.size() > 5 ? ", ..." : ""));
                }
                if (output.nav() != null && output.nav().style() != WebSite.Style.NONE && output.nav().contents() != null && pages.stream()
                        .noneMatch(p -> output.nav().isContents(p.source() == null ? null
                                : p.source().toString().replace('\\', '/'), p.layout().name()))) {
                    report.warn(output.name() + ": contents " + output.nav().contents() + " isn't a page's file or layout,"
                            + " so the navigation lists every page");
                }
                // A site of one page is just that page, standing alone.
                Map<Path, String> site = output.format() == Manifest.Format.WEB && !output.single() && pages.size() > 1
                        ? WebSite.split(html, manifest.title(), file.getFileName().toString(), output.nav(), theme) : null;
                outputs.add(new Output(output.name(), output.format(), file, e.getValue(), pages, html, pdf, measurement,
                        problems, remedies, site));
            }
        } finally {
            if (browser != null) {
                browser.close();
            }
        }
        return new Build(manifest, sequence, result, List.copyOf(outputs), report);
    }

    /** The targets of in-document links ({@code href="#..."}) that no element has as its id, each once. */
    static List<String> brokenLinks(String html) {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        java.util.Set<String> ids = new java.util.HashSet<>(doc.select("[id]").eachAttr("id"));
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (org.jsoup.nodes.Element a : doc.select("a[href^=#]")) {
            String id = java.net.URLDecoder.decode(a.attr("href").substring(1), StandardCharsets.UTF_8);
            if (!id.isEmpty() && !ids.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
