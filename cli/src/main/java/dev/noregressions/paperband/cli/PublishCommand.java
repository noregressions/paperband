package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.config.PublicationLoader;
import dev.noregressions.paperband.model.Publication;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Config-driven multi-edition build: reads the {@code publication:} block of
 * the book root {@code pagewright.yaml} and runs one book build per edition
 * (see DESIGN-publications.md). {@code build} stays the low-level primitive;
 * this command is the loop on top — it populates a {@link BuildCommand}'s
 * fields per edition and calls its book path directly.
 *
 * <p>CLI surface is deliberately session-only ({@code --renderer},
 * {@code --emit-html-dir}); everything describing the artefact lives in the
 * yaml. Editions build sequentially (decided: render time is not a
 * bottleneck at current book sizes); failures don't stop later editions, and
 * the exit code is the first nonzero result.
 */
@Command(
        name = "publish",
        mixinStandardHelpOptions = true,
        description = "Build the editions declared in the book's publication: block.")
public final class PublishCommand implements Callable<Integer> {

    @Option(
            names = {"-r", "--renderer"},
            description = "Renderer name (session setting — machine capability). Default: ${DEFAULT-VALUE}",
            defaultValue = "playwright")
    String rendererName;

    @Option(
            names = {"--emit-html-dir"},
            description = "Also write each edition's rendered HTML into this directory (debug).")
    Path emitHtmlDir;

    @Option(
            names = {"--set"},
            paramLabel = "<path=value>",
            description = "Override one publication setting for this run (dotted path into the "
                    + "publication block, editions addressed by id — e.g. defaults.theme=carded, "
                    + "editions.batch-guide.size=A4, editions.batch-guide.vars.audience=manager). "
                    + "Values parse as YAML scalars. Repeatable; later wins. Unknown paths are an "
                    + "error. For experiments and CI one-offs — anything typed twice belongs in the yaml.")
    List<String> setOverrides;

    @Parameters(index = "0", description = "Book root directory (contains pagewright.yaml with a publication: block).")
    Path bookDir;

    @Parameters(index = "1..*", description = "Edition ids to build. Omit for every declared edition.")
    List<String> editionIds;

    @Override
    public Integer call() throws Exception {
        Path yaml = bookDir.resolve("pagewright.yaml");
        Publication pub;
        try {
            pub = PublicationLoader.load(yaml,
                    setOverrides == null ? List.of() : setOverrides).orElse(null);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        if (pub == null || pub.editions().isEmpty()) {
            System.err.println("No publication: block with editions found in " + yaml);
            return 2;
        }

        List<Publication.Edition> wanted = new ArrayList<>();
        if (editionIds == null || editionIds.isEmpty()) {
            wanted.addAll(pub.editions());
        } else {
            for (String id : editionIds) {
                Publication.Edition e = pub.edition(id);
                if (e == null) {
                    System.err.println("Unknown edition '" + id + "'. Declared: "
                            + pub.editions().stream().map(Publication.Edition::id).toList());
                    return 2;
                }
                wanted.add(e);
            }
        }

        // Series metadata: every declared edition (declaration order), exposed
        // to templates as edition.series so a guide can list its siblings —
        // e.g. a mini-guide intro page naming the whole series — without the
        // list being hand-maintained anywhere.
        List<Map<String, Object>> series = new ArrayList<>();
        for (Publication.Edition e : pub.editions()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", e.id());
            s.put("title", e.title());
            s.put("classes", e.classes());
            Object strapline = e.vars().get("strapline");
            if (strapline != null) s.put("strapline", strapline);
            series.add(s);
        }

        int firstFailure = 0;
        for (Publication.Edition e : wanted) {
            Publication.Resolved r = e.resolve(pub.defaults());
            System.out.println("── edition " + r.id() + " → " + r.output());
            int code;
            try {
                code = buildEdition(r, series);
            } catch (Exception ex) {
                System.err.println("edition " + r.id() + " failed: " + ex.getMessage());
                code = 1;
            }
            if (code != 0) {
                System.err.println("edition " + r.id() + " exited " + code);
                if (firstFailure == 0) firstFailure = code;
            }
        }
        return firstFailure;
    }

    private int buildEdition(Publication.Resolved r, List<Map<String, Object>> series)
            throws Exception {
        BuildCommand build = new BuildCommand();

        build.input = bookDir;
        build.output = Path.of(r.output());   // cwd-relative, like build's output arg
        if (build.output.getParent() != null) Files.createDirectories(build.output.getParent());

        build.rendererName = rendererName;
        build.target = "pdf-a4";
        build.themeName = r.theme();
        if (r.themeDir() != null) build.themeDir = bookDir.resolve(r.themeDir()).normalize();
        if (emitHtmlDir != null) {
            Files.createDirectories(emitHtmlDir);
            build.emitHtml = emitHtmlDir.resolve(r.id() + ".html");
        }

        String size = r.size() == null ? "A4" : r.size().toUpperCase(Locale.ROOT);
        try {
            build.pageSize = RenderCommand.PageSizeOption.valueOf(size);
        } catch (IllegalArgumentException ex) {
            System.err.println("edition " + r.id() + ": unknown size '" + r.size() + "'");
            return 2;
        }

        if (!r.select().isEmpty()) {
            build.selectClauses = r.select().fields();
            build.selectCards = r.select().cards();
            build.selectWhere = r.select().where();
        }
        if (!r.vars().isEmpty()) build.editionVars = r.vars();

        // Page contracts (publication concern, versioned with the yaml).
        if (Boolean.TRUE.equals(r.pages().report())) build.reportPages = true;
        if (r.pages().maxPerCard() != null) build.maxPagesPerCard = r.pages().maxPerCard();

        Map<String, Object> editionModel = new LinkedHashMap<>();
        editionModel.put("id", r.id());
        editionModel.put("classes", r.classes());
        editionModel.put("title", r.title());
        editionModel.put("vars", r.vars());
        editionModel.put("series", series);
        build.editionModel = editionModel;

        return build.buildBook();
    }
}
