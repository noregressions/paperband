package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceHighlighterTest {

    private static String hl(String s) {
        return SourceHighlighter.highlight(s);
    }

    @Test
    void frontMatterAndTemplateTokens() {
        assertEquals("<span class=\"tok-fm\">---\ntitle: &quot;A&quot;\n---\n</span>"
                        + "<span class=\"tok-heading\">#</span> <span class=\"tok-pre tok-print\">{{ page.title }}</span>\n",
                hl("---\ntitle: \"A\"\n---\n# {{ page.title }}\n"));
        assertEquals("<span class=\"tok-pre tok-tag\">{% for x in y %}</span>"
                + "<span class=\"tok-pre tok-comment\">{# note #}</span>"
                + "<span class=\"tok-post tok-print\">[[ x ]]</span>"
                + "<span class=\"tok-post tok-tag\">[% if a %]</span>"
                + "<span class=\"tok-post tok-comment\">[# n #]</span>",
                hl("{% for x in y %}{# note #}[[ x ]][% if a %][# n #]"));
    }

    @Test
    void multiLineCommentsAreOneToken() {
        assertEquals("<span class=\"tok-pre tok-comment\">{# one\ntwo #}</span>\nafter", hl("{# one\ntwo #}\nafter"));
    }

    @Test
    void markersDivsFencesAndHeadings() {
        assertEquals("<span class=\"tok-heading\">###</span> Step <span class=\"tok-marker\">{! step id=s title=&quot;a } b&quot;}</span>\n",
                hl("### Step {! step id=s title=\"a } b\"}\n"));
        assertEquals("<span class=\"tok-div\">:::</span> <span class=\"tok-marker\">{! tip}</span>\nx\n<span class=\"tok-div\">:::</span>",
                hl("::: {! tip}\nx\n:::"));
        assertEquals("<span class=\"tok-fence\">```bash</span> <span class=\"tok-marker\">{! command}</span>\nls\n<span class=\"tok-fence\">```</span>",
                hl("```bash {! command}\nls\n```"));
        assertEquals("Not #a heading and a#b", hl("Not #a heading and a#b"));
    }

    @Test
    void markersInsideCodeStayPlainButTemplatesDoNot() {
        assertEquals("Use `{! tip}` and `<span class=\"tok-pre tok-print\">{{ x }}</span>` then <span class=\"tok-marker\">{! real}</span>",
                hl("Use `{! tip}` and `{{ x }}` then {! real}"));
        assertEquals("<span class=\"tok-fence\">```</span>\n{! inside} <span class=\"tok-pre tok-print\">{{ v }}</span>\n"
                + "<span class=\"tok-fence\">```</span>\nafter <span class=\"tok-marker\">{! out}</span>",
                hl("```\n{! inside} {{ v }}\n```\nafter {! out}"));
        assertEquals("`` a ` {! b} ``", hl("`` a ` {! b} ``"), "double-backtick spans");
    }

    @Test
    void escapedMarkersAndHtmlStayPlain() {
        assertEquals("\\{! literal} &lt;b&gt;&amp;", hl("\\{! literal} <b>&"));
    }
}
