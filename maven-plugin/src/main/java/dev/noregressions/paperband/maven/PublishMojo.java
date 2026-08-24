package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.PublicationLoader;
import dev.noregressions.paperband.model.Publication;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds every edition declared in the book's {@code publication:} block — the
 * same content cut several ways (a full guide, a mini guide, a client edition)
 * without a POM execution per cut.
 *
 * <p>Everything describing an artefact lives in the yaml: its theme, size,
 * output path, card selection, vars and page contract. The POM contributes only
 * session settings — which renderer, where to drop debug HTML, and one-off
 * {@code <set>} overrides.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;editions&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;publish&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;bookDirectory&gt;${project.basedir}/guide&lt;/bookDirectory&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 *
 * <p>Editions build sequentially, and a failure doesn't stop the ones after it:
 * a broken edition shouldn't hide whether the others still build. The goal
 * fails at the end, naming every edition that did.
 */
@Mojo(name = "publish", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class PublishMojo extends AbstractPaperbandMojo {

    /** Book root — the directory whose {@code paperband.yaml} carries the {@code publication:} block. */
    @Parameter(property = "paperband.bookDirectory", required = true)
    private java.io.File bookDirectory;

    /** Build only these edition ids. Omit for every declared edition. */
    @Parameter(property = "paperband.editions")
    private List<String> editions;

    /** Also write each edition's rendered HTML into this directory (debug). */
    @Parameter(property = "paperband.emitHtmlDirectory")
    private java.io.File emitHtmlDirectory;

    /**
     * Override one publication setting for this run: a dotted path into the
     * publication block, editions addressed by id — e.g.
     * {@code defaults.theme=carded}, {@code editions.mini.size=A4},
     * {@code editions.mini.vars.audience=manager}. Values parse as YAML
     * scalars; later entries win; an unknown path is an error.
     *
     * <p>For experiments and CI one-offs — anything typed twice belongs in the
     * yaml.
     */
    @Parameter(property = "paperband.set")
    private List<String> set;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("publish")) return;

        Path bookDir = resolve(bookDirectory);
        Path yaml = bookDir.resolve("paperband.yaml");
        Publication publication;
        try {
            publication = PublicationLoader.load(yaml, set == null ? List.of() : set).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to read " + yaml + ": " + e.getMessage(), e);
        }
        if (publication == null || publication.editions().isEmpty()) {
            throw new MojoExecutionException("No publication: block with editions found in " + yaml);
        }

        List<Publication.Edition> wanted = new ArrayList<>();
        if (editions == null || editions.isEmpty()) {
            wanted.addAll(publication.editions());
        } else {
            for (String id : editions) {
                Publication.Edition e = publication.edition(id);
                if (e == null) {
                    throw new MojoExecutionException("Unknown edition '" + id + "'. Declared: "
                            + publication.editions().stream().map(Publication.Edition::id).toList());
                }
                wanted.add(e);
            }
        }

        List<Map<String, Object>> series = seriesModel(publication);
        List<String> failures = new ArrayList<>();
        for (Publication.Edition e : wanted) {
            Publication.Resolved r = e.resolve(publication.defaults());
            getLog().info("── edition " + r.id() + " → " + r.output());
            try {
                buildEdition(bookDir, r, series);
            } catch (Exception ex) {
                getLog().error("edition " + r.id() + " failed: " + ex.getMessage());
                failures.add(r.id());
            }
        }

        if (!failures.isEmpty()) {
            throw new MojoFailureException("Editions failed: " + failures);
        }
        getLog().info("Published " + wanted.size() + (wanted.size() == 1 ? " edition" : " editions"));
    }

    /**
     * Series metadata: every declared edition in declaration order, exposed to
     * templates as {@code edition.series} so a guide can name its siblings —
     * a mini-guide intro listing the whole series, say — without that list
     * being hand-maintained anywhere.
     */
    private static List<Map<String, Object>> seriesModel(Publication publication) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (Publication.Edition e : publication.editions()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", e.id());
            s.put("title", e.title());
            s.put("classes", e.classes());
            Object strapline = e.vars().get("strapline");
            if (strapline != null) s.put("strapline", strapline);
            series.add(s);
        }
        return series;
    }

    private void buildEdition(Path bookDir, Publication.Resolved r, List<Map<String, Object>> series)
            throws Exception {
        BookBuild build = new BookBuild(getLog());
        build.input = bookDir;

        // Edition outputs are declared in the yaml, relative to the module
        // basedir the way any other configured path is.
        Path out = Path.of(r.output());
        build.output = out.isAbsolute() ? out : basedir().resolve(out);
        Path parent = build.output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        build.rendererName = renderer;
        build.target = target;
        build.pageSize = r.size() == null ? pageSize : r.size().toLowerCase(Locale.ROOT);
        build.margins = resolveMargins();
        build.marginsLabel = margins;
        build.themeName = r.theme() != null ? r.theme() : themeName;
        build.themeDir = r.themeDir() != null ? bookDir.resolve(r.themeDir()).normalize() : themeDirPath();
        build.includeProviderConfig = includeProviderConfig();

        if (emitHtmlDirectory != null) {
            Path dir = resolve(emitHtmlDirectory);
            Files.createDirectories(dir);
            build.emitHtml = dir.resolve(r.id() + ".html");
        }

        if (!r.select().isEmpty()) {
            build.selectClauses = r.select().fields();
            build.selectCards = r.select().cards();
            build.selectWhere = r.select().where();
        }
        if (!r.vars().isEmpty()) build.editionVars = r.vars();

        // Page contracts are a publication concern, versioned with the yaml.
        if (Boolean.TRUE.equals(r.pages().report())) build.reportPages = true;
        if (r.pages().maxPerCard() != null) build.maxPagesPerCard = r.pages().maxPerCard();

        Map<String, Object> editionModel = new LinkedHashMap<>();
        editionModel.put("id", r.id());
        editionModel.put("classes", r.classes());
        editionModel.put("title", r.title());
        editionModel.put("vars", r.vars());
        editionModel.put("series", series);
        build.editionModel = editionModel;

        build.run();
    }
}
