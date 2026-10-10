package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownPipelineTest {

    @TempDir
    Path templates;

    private MarkdownPipeline.Result run(String a, String b) throws IOException {
        return new MarkdownPipeline(templates, Map.of("v", "V")).run(List.of(
                new MarkdownPipeline.Source(Path.of("a.md"), a),
                new MarkdownPipeline.Source(Path.of("b.md"), b)));
    }

    private static String text(CombinedAst ast, int file) {
        return Site.text(ast.getFiles().get(file));
    }

    @Test
    void postPassReadsListGeneratedByAnotherFilesPrePass() throws IOException {
        MarkdownPipeline.Result r = run("""
                ## Names
                {% for n in ["ann", "bob"] %}
                - {{ n }}
                {% endfor %}
                """, """
                [% for n in site.file("a.md").section("names").list.items %]
                - [[ n | upper ]]
                [% endfor %]
                """);

        Site.ListView list = new Site(r.ast()).file("b.md").getList();
        assertEquals(List.of("ANN", "BOB"), list.getItems().stream().map(Site.Item::getText).toList());
    }

    @Test
    void eachPassIgnoresTheOthersSyntax() throws IOException {
        MarkdownPipeline.Result r = run("pre {{ v }} post [[ v ]]\n", "x\n");
        assertEquals("pre V post [[ v ]]", text(r.draft(), 0));
        assertEquals("pre V post V", text(r.ast(), 0));
    }

    @Test
    void prePassOutputIsNotReEvaluated() throws IOException {
        MarkdownPipeline.Result r = run("{% verbatim %}{{ v }}{% endverbatim %} and <%= jsp %>\n", "x\n");
        assertEquals("{{ v }} and <%= jsp %>", text(r.ast(), 0));
    }

    @Test
    void postPassSeesTheDraftNotOtherFilesPostPassOutput() throws IOException {
        MarkdownPipeline.Result r = run("""
                ## Names
                [% for n in ["ann", "bob"] %]
                - [[ n ]]
                [% endfor %]
                """, """
                Items in a.md: [[ site.file("a.md").section("names").list.items | join(", ") ]]
                """);

        // b.md sees a.md's draft: the unexpanded loop body as a one-item list (commonmark also
        // folds the following "[% endfor %]" line into that item as a continuation line)...
        assertTrue(text(r.ast(), 1).startsWith("Items in a.md: [[ n ]]"), text(r.ast(), 1));
        // ...while the final AST has the expanded list.
        assertEquals(List.of("ann", "bob"), new Site(r.ast()).file("a.md").getList().getItems().stream()
                .map(Site.Item::getText).toList());
    }

    @Test
    void frontMatterSurvivesBothPassesUnchanged() throws IOException {
        String fm = "---\ntitle: \"{{ v }} [[ v ]]\"\n---\n";
        MarkdownPipeline.Result r = run(fm + "{{ page.title }}\n", "x\n");
        assertEquals("{{ v }} [[ v ]]", r.ast().getFiles().getFirst().getFrontMatter().get("title"));
    }

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneId.of("UTC"));

    private String finalText(MarkdownPipeline.Config config, String... markdown) throws IOException {
        List<MarkdownPipeline.Source> sources = new java.util.ArrayList<>();
        for (int i = 0; i < markdown.length; i++) {
            sources.add(new MarkdownPipeline.Source(Path.of("f" + i + ".md"), markdown[i]));
        }
        return text(new MarkdownPipeline(config.withTemplates(templates)).run(sources).ast(), 0);
    }

    @Test
    void prePassSeesEveryFilesFrontMatter() throws IOException {
        String out = finalText(MarkdownPipeline.Config.defaults(), """
                {% for f in site.files %}{{ f.page.title }} ({{ f.path }}); {% endfor %}
                {{ site.file("f1.md").page.tags | join("+") }}
                """, "---\ntitle: Second\ntags: [x, y]\n---\n", "---\ntitle: Third\n---\n");
        assertEquals("(f0.md); Second (f1.md); Third (f2.md); x+y", out);
    }

    @Test
    void prePassCannotReadContentYet() {
        IOException e = assertThrows(IOException.class, () -> finalText(MarkdownPipeline.Config.defaults(),
                "{{ site.file(\"f1.md\").section(\"x\") }}", "## X\n"));
        assertTrue(e.getMessage().contains("pre-pass error in f0.md"), e.getMessage());
        assertTrue(e.getMessage().contains(Site.NO_CONTENT_YET), e.getMessage());
    }

    @Test
    void frontMatterIsTypedInTemplates() throws IOException {
        String out = finalText(MarkdownPipeline.Config.defaults(), """
                ---
                chapter: 3
                draft: false
                published: 2026-10-03
                author: {name: Steve}
                ---
                next={{ page.chapter + 1 }} {% if page.draft %}draft{% else %}final{% endif %}
                {{ page.published | date("d MMM yyyy") }} by {{ page.author.name }}
                [[ page.chapter * 2 ]]
                """);
        assertEquals("next=4 final 3 Oct 2026 by Steve 6", out);
    }

    @Test
    void dataEnvAndBuildInBothPasses() throws IOException {
        MarkdownPipeline.Config config = MarkdownPipeline.Config.defaults()
                .withData(Map.of("site", Map.of("name", "Paperband", "langs", List.of("java", "md"))))
                .withEnv(Map.of("STAGE", "test"))
                .withClock(FIXED);
        String out = finalText(config, """
                {{ data.site.name }} {{ data.site.langs | join(",") }} {{ env.STAGE }} {{ build.files }}
                [[ data.site.name ]] [[ env.STAGE ]] [[ build.date | date("yyyy-MM-dd HH:mm") ]]
                missing:{{ env.HOME }}
                """, "x\n");
        assertEquals("Paperband java,md test 2 Paperband test 2026-10-03 12:00 missing:", out);
    }

    @Test
    void varsCannotUseReservedNames() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MarkdownPipeline.Config.defaults().withVars(Map.of("data", "x", "ok", "y")));
        assertTrue(e.getMessage().startsWith("reserved variable name(s) [data]"), e.getMessage());
    }

    @Test
    void resultKeepsEachFilesTextAtEveryStage() throws IOException {
        MarkdownPipeline.Result r = run("---\nt: T\n---\n{{ v }} [[ v ]]\n", "plain\n");
        MarkdownPipeline.Texts a = r.texts().get(Path.of("a.md"));
        assertEquals("---\nt: T\n---\n{{ v }} [[ v ]]\n", a.at(MarkdownPipeline.Stage.ORIGINAL));
        assertEquals("---\nt: T\n---\nV [[ v ]]\n", a.at(MarkdownPipeline.Stage.PRE));
        assertEquals("---\nt: T\n---\nV V\n", a.at(MarkdownPipeline.Stage.FINAL));
        assertEquals(List.of(Path.of("a.md"), Path.of("b.md")), List.copyOf(r.texts().keySet()));
    }

    @Test
    void postPassErrorsNameTheFileAndPass() {
        IOException e = assertThrows(IOException.class,
                () -> run("x\n", "[[ site.file(\"missing.md\").page ]]\n"));
        assertTrue(e.getMessage().contains("post-pass error in b.md"), e.getMessage());
        assertTrue(e.getMessage().contains("no file matches \"missing.md\"; files are [a.md, b.md]"), e.getMessage());
    }
}
