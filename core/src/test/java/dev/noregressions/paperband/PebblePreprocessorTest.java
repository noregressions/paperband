package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PebblePreprocessorTest {

    @TempDir
    Path templates;

    private PebblePreprocessor pebble() {
        return new PebblePreprocessor(templates, Map.of("product", "Paperband"));
    }

    @Test
    void substitutesGlobalsAndFrontMatter() throws IOException {
        String out = pebble().process(Path.of("a.md"), """
                ---
                title: Hello
                tags:
                  - x
                  - y
                ---
                # {{ page.title }} from {{ product }}
                {% for t in page.tags %}{{ t }};{% endfor %}
                """);

        assertTrue(out.contains("# Hello from Paperband"), out);
        assertTrue(out.contains("x;y;"), out);
        assertTrue(out.startsWith("---\ntitle: Hello"), "front matter is passed through: " + out);
    }

    @Test
    void codeBlocksAreNotTemplated() throws IOException {
        String md = """
                # {{ product }}

                ```bash
                grep -oE "\\[[a-z-]+\\]" | sort
                [[ -f x ]] && echo {{ not.a.value }}
                ```

                ~~~
                {% raw %}
                ~~~

                - In a list:

                  ```
                  {{ also.left }}
                  ```

                Inline `{{ product }}` is templated.
                """;
        String pre = pebble().process(Path.of("a.md"), md);
        assertTrue(pre.contains("# Paperband"), pre);
        assertTrue(pre.contains("grep -oE \"\\[[a-z-]+\\]\" | sort\n[[ -f x ]] && echo {{ not.a.value }}\n```"), pre);
        assertTrue(pre.contains("~~~\n{% raw %}\n~~~"), pre);
        assertTrue(pre.contains("  ```\n  {{ also.left }}\n  ```"), "a block in a list item too: " + pre);
        assertTrue(pre.contains("Inline `Paperband` is templated."), pre);
        assertEquals(md.split("\n", -1).length, pre.split("\n", -1).length, "every line stays where it was");

        String post = new PebblePreprocessor(PebblePreprocessor.Pass.POST, templates, Map.of()).process(Path.of("a.md"), pre);
        assertEquals(pre, post, "nothing for the post pass either");
    }

    @Test
    void aCodeBlockCanAskToBeTemplated() throws IOException {
        String out = pebble().process(Path.of("a.md"), """
                ```bash {! template}
                mvn -Dproduct={{ product }}
                ```
                {% for i in [1, 2] %}
                ```
                {{ i }}
                ```
                {% endfor %}
                """);
        assertTrue(out.contains("```bash {! template}\nmvn -Dproduct=Paperband\n```"), out);
        assertEquals(2, out.split("\\{\\{ i }}", -1).length - 1, "a block in a loop appears each time, untouched: " + out);
    }

    @Test
    void doesNotHtmlEscape() throws IOException {
        String out = pebble().process(Path.of("a.md"), "{{ v }}");
        assertEquals("", out);
        out = new PebblePreprocessor(templates, Map.of("v", "<b>&</b>")).process(Path.of("a.md"), "{{ v }}");
        assertEquals("<b>&</b>", out);
    }

    @Test
    void resolvesIncludesAgainstTemplatesDir() throws IOException {
        Files.writeString(templates.resolve("part.md"), "included {{ file }}");
        String out = pebble().process(Path.of("docs/a.md"), "start {% include \"part.md\" %} end");
        assertEquals("start included docs/a.md end", out);
    }

    @Test
    void extendsLayoutAndKeepsFrontMatter() throws IOException {
        Files.writeString(templates.resolve("layout.md"), """
                # {{ page.title }}
                {% block content %}default{% endblock %}
                ---
                {% block footer %}{{ product }}{% endblock %}
                """);
        String out = pebble().process(Path.of("a.md"), """
                ---
                title: Child
                ---
                {% extends "layout.md" %}
                {% block content %}Child body{% endblock %}
                """);

        assertEquals("""
                ---
                title: Child
                ---
                # Child
                Child body
                ---
                Paperband
                """, out);
    }

    @Test
    void removesTagOnlyLinesButKeepsOtherNewlines() throws IOException {
        String out = pebble().process(Path.of("a.md"), """
                {# a comment
                   over two lines #}
                {% for n in range(1, 2) %}
                  {% if n > 0 %}
                - item {{ n }}
                  {% endif %}
                {% endfor %}
                text {% if true %}inline{% endif %}
                after
                """);
        assertEquals("- item 1\n- item 2\ntext inline\nafter\n", out);
    }

    @Test
    void frontMatterIsNotTemplated() throws IOException {
        String out = pebble().process(Path.of("a.md"), "---\ntitle: \"{{ product }}\"\n---\n{{ product }}");
        assertEquals("---\ntitle: \"{{ product }}\"\n---\nPaperband", out);
    }

    @Test
    void reportsTemplateErrorsWithFileName() {
        IOException e = assertThrows(IOException.class,
                () -> pebble().process(Path.of("bad.md"), "{% if %}"));
        assertTrue(e.getMessage().contains("bad.md"), e.getMessage());
    }

    private PebblePreprocessor post() {
        return new PebblePreprocessor(PebblePreprocessor.Pass.POST, templates, Map.of("product", "Paperband"));
    }

    @Test
    void postPassRemovesTagOnlyLinesButKeepsOtherNewlines() throws IOException {
        String out = post().process(Path.of("a.md"), """
                [# a comment
                   over two lines #]
                [% for n in range(1, 2) %]
                  [% if n > 0 %]
                - item [[ n ]]
                  [% endif %]
                [% endfor %]
                text [% if true %]inline[% endif %]
                after
                """);
        assertEquals("- item 1\n- item 2\ntext inline\nafter\n", out);
    }

    @Test
    void postPassLeavesPrePassSyntaxAlone() throws IOException {
        String out = post().process(Path.of("a.md"), """
                {% if x %}
                {{ product }} [[ product ]]
                {% endif %}
                """);
        assertEquals("{% if x %}\n{{ product }} Paperband\n{% endif %}\n", out);
    }

    @Test
    void postPassExtendsAndIncludesWithItsOwnSyntax() throws IOException {
        Files.writeString(templates.resolve("post-layout.md"), """
                # [[ page.title ]]
                [% block content %]default[% endblock %]
                [% include "post-footer.md" %]
                """);
        Files.writeString(templates.resolve("post-footer.md"), """
                [% if true %]
                footer for [[ file ]]
                [% endif %]
                """);
        String out = post().process(Path.of("a.md"), """
                ---
                title: Child
                ---
                [% extends "post-layout.md" %]
                [% block content %]Child body[% endblock %]
                """);

        assertEquals("""
                ---
                title: Child
                ---
                # Child
                Child body
                footer for a.md
                """, out);
    }

    @Test
    void postPassDoesNotTemplateFrontMatter() throws IOException {
        String out = post().process(Path.of("a.md"), "---\ntitle: \"[[ product ]]\"\n---\n[[ page.title ]]");
        assertEquals("---\ntitle: \"[[ product ]]\"\n---\n[[ product ]]", out);
    }

    @Test
    void postPassErrorsNameThePass() {
        IOException e = assertThrows(IOException.class,
                () -> post().process(Path.of("bad.md"), "[% if %]"));
        assertTrue(e.getMessage().contains("post-pass error in bad.md"), e.getMessage());
    }

    @Test
    void combinedAstSeesProcessedContent() throws IOException {
        CombinedAst ast = new CombinedAst(pebble());
        ast.add(Path.of("a.md"), "---\ntitle: T\n---\n# {{ page.title }}\n");
        assertTrue(AstTextRenderer.render(ast.getRoot()).contains("Text \"T\""));
    }
}
