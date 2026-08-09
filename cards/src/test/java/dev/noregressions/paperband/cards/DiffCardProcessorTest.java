package dev.noregressions.paperband.cards;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffCardProcessorTest {

    // ---- parseDiffCard ------------------------------------------------------

    @Test
    void parseDiffCard_standardBothSides() {
        String text = String.join("\n",
                "# // Custom serializer class",
                "@@removed",
                "old line 1",
                "old line 2",
                "@@added",
                "new line 1");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals("Custom serializer class", p.caption);
        assertEquals(List.of("old line 1", "old line 2"), p.removed);
        assertEquals(List.of("new line 1"), p.added);
    }

    @Test
    void parseDiffCard_additionsOnly() {
        String text = String.join("\n",
                "# // pom.xml — add the legacy module",
                "@@added",
                "<dependency>",
                "</dependency>");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals("pom.xml — add the legacy module", p.caption);
        assertTrue(p.removed.isEmpty());
        assertEquals(List.of("<dependency>", "</dependency>"), p.added);
    }

    @Test
    void parseDiffCard_noCaption() {
        String text = String.join("\n",
                "@@removed",
                "a",
                "@@added",
                "b");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals("", p.caption);
        assertEquals(List.of("a"), p.removed);
        assertEquals(List.of("b"), p.added);
    }

    @Test
    void parseDiffCard_multiLineCaptionWithBlankLine() {
        String text = String.join("\n",
                "# // first line",
                "",
                "# // second line",
                "@@removed",
                "x",
                "@@added",
                "y");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals("first line second line", p.caption);
    }

    @Test
    void parseDiffCard_trimsTrailingBlankLines() {
        String text = String.join("\n",
                "@@removed",
                "code",
                "",
                "",
                "@@added",
                "new",
                "");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals(List.of("code"), p.removed);
        assertEquals(List.of("new"), p.added);
    }

    @Test
    void parseDiffCard_blankLinesInsideCodeArePreserved() {
        // Trailing blanks get trimmed; interior blanks survive.
        String text = String.join("\n",
                "@@added",
                "line 1",
                "",
                "line 3");
        DiffCardProcessor.Parsed p = DiffCardProcessor.parseDiffCard(text);
        assertEquals(List.of("line 1", "", "line 3"), p.added);
    }

    // ---- extractDiffCardLanguage --------------------------------------------

    @Test
    void extractDiffCardLanguage_defaultIsJava() {
        assertEquals("java", DiffCardProcessor.extractDiffCardLanguage("language-diff-card"));
    }

    @Test
    void extractDiffCardLanguage_xmlSuffix() {
        assertEquals("xml", DiffCardProcessor.extractDiffCardLanguage("language-diff-card-xml"));
    }

    @Test
    void extractDiffCardLanguage_yamlSuffix() {
        assertEquals("yaml", DiffCardProcessor.extractDiffCardLanguage("language-diff-card-yaml"));
    }

    @Test
    void extractDiffCardLanguage_extraClassesIgnored() {
        // Multi-class element — only the language-diff-card-* token counts.
        assertEquals("xml",
                DiffCardProcessor.extractDiffCardLanguage("foo language-diff-card-xml bar"));
    }

    // ---- errorLineDecoration ------------------------------------------------

    @Test
    void errorLineDecoration_promptLine() {
        assertEquals("err-prompt", DiffCardProcessor.errorLineDecoration("$ mvn compile"));
    }

    @Test
    void errorLineDecoration_errorLine() {
        assertEquals("err-error",
                DiffCardProcessor.errorLineDecoration("[ERROR] /src/Foo.java:5: error: blah"));
    }

    @Test
    void errorLineDecoration_plainLine() {
        assertNull(DiffCardProcessor.errorLineDecoration("Caused by: NullPointerException"));
        assertNull(DiffCardProcessor.errorLineDecoration(""));
    }

    // ---- process (end-to-end on jsoup tree) ---------------------------------

    @Test
    void process_rewritesDiffCardToFigure() {
        Element body = parseFragment(""
                + "<pre><code class=\"language-diff-card\">"
                + "# // caption\n"
                + "@@removed\n"
                + "old\n"
                + "@@added\n"
                + "new\n"
                + "</code></pre>");

        DiffCardProcessor.process(body);

        Element figure = body.selectFirst("figure.diff-card");
        assertNotNull(figure, "should have a figure.diff-card");
        assertEquals("java", figure.attr("data-lang"));
        assertEquals("caption", figure.selectFirst("figcaption").text());

        Element removedHeader = figure.selectFirst(".diff-side-removed > header");
        assertEquals("Before", removedHeader.text());
        Element addedHeader = figure.selectFirst(".diff-side-added > header");
        assertEquals("After", addedHeader.text());

        Element removedCode = figure.selectFirst(".diff-side-removed pre > code");
        assertEquals("language-java", removedCode.className());
        assertEquals("old", removedCode.wholeText());

        Element addedCode = figure.selectFirst(".diff-side-added pre > code");
        assertEquals("new", addedCode.wholeText());
    }

    @Test
    void process_additionsOnlyEmitsSingleColumnWithAddedLabel() {
        Element body = parseFragment(""
                + "<pre><code class=\"language-diff-card-xml\">"
                + "# // pom.xml — add module\n"
                + "@@added\n"
                + "&lt;dependency/&gt;\n"
                + "</code></pre>");

        DiffCardProcessor.process(body);

        Element figure = body.selectFirst("figure.diff-card");
        assertTrue(figure.hasClass("diff-card-additions-only"));
        assertEquals("xml", figure.attr("data-lang"));
        assertNull(figure.selectFirst(".diff-side-removed"),
                "additions-only should have no removed side");
        assertEquals("Added", figure.selectFirst(".diff-side-added > header").text());
        assertEquals("language-xml",
                figure.selectFirst(".diff-side-added pre > code").className());
    }

    @Test
    void process_rewritesErrorOutput() {
        Element body = parseFragment(""
                + "<pre><code class=\"language-error-output\">"
                + "$ mvn compile\n"
                + "[ERROR] /src/Foo.java:5: error: blah\n"
                + "  at com.example.Foo.bar(Foo.java:5)\n"
                + "</code></pre>");

        DiffCardProcessor.process(body);

        Element pre = body.selectFirst("pre.error-output");
        assertNotNull(pre, "should have a pre.error-output");
        // language-error-output class should be gone (Prism autoloader avoidance).
        assertNull(body.selectFirst("code.language-error-output"));

        Element code = pre.selectFirst("code");
        // The two recognised line patterns get wrapped in spans; the third
        // line is plain text inside the <code>.
        assertEquals(1, code.select("span.err-prompt").size());
        assertEquals("$ mvn compile", code.selectFirst("span.err-prompt").text());
        assertEquals(1, code.select("span.err-error").size());
        assertEquals("[ERROR] /src/Foo.java:5: error: blah",
                code.selectFirst("span.err-error").text());
        assertTrue(code.wholeText().contains("at com.example.Foo.bar"));
    }

    @Test
    void process_realLanguageFencePassesThrough() {
        // language-java should be untouched — Prism handles it downstream.
        Element body = parseFragment(""
                + "<pre><code class=\"language-java\">"
                + "System.out.println(\"hi\");\n"
                + "</code></pre>");

        DiffCardProcessor.process(body);

        // Still a plain pre > code with the original class, no figure wrapper.
        assertNull(body.selectFirst("figure.diff-card"));
        Element code = body.selectFirst("pre > code");
        assertEquals("language-java", code.className());
    }

    @Test
    void process_unfencedCodeBlockUntouched() {
        Element body = parseFragment("<pre><code>plain text</code></pre>");
        DiffCardProcessor.process(body);
        assertNull(body.selectFirst("figure.diff-card"));
        assertEquals("plain text", body.selectFirst("pre > code").wholeText());
    }

    // ---- helpers ------------------------------------------------------------

    private static Element parseFragment(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        return doc.body();
    }
}
