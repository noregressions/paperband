package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YamlHighlighterTest {

    private static String hl(String s) {
        return YamlHighlighter.highlight(s);
    }

    @Test
    void keysValuesAndComments() {
        assertEquals("<span class=\"tok-key\">description</span>: An example's page <span class=\"tok-yaml-comment\"># note</span>\n",
                hl("description: An example's page # note\n"), "an apostrophe mid-value isn't a string");
        assertEquals("<span class=\"tok-yaml-comment\"># whole line</span>\n", hl("# whole line\n"));
        assertEquals("  - { <span class=\"tok-key\">id</span>: title, <span class=\"tok-key\">select</span>: "
                + "<span class=\"tok-string\">&quot;section.h1 &gt; p&quot;</span> }", hl("  - { id: title, select: \"section.h1 > p\" }"));
    }

    @Test
    void stringsAndHashesInsideThem() {
        assertEquals("<span class=\"tok-key\">areas</span>: [<span class=\"tok-string\">&quot;a # b&quot;</span>, "
                + "<span class=\"tok-string\">'it''s'</span>]", hl("areas: [\"a # b\", 'it''s']"));
        assertEquals("<span class=\"tok-key\">select</span>: a#b", hl("select: a#b"), "# without a space before isn't a comment");
    }

    @Test
    void blockScalarsAreText() {
        assertEquals("<span class=\"tok-key\">css</span>: |\n"
                + "<span class=\"tok-string\">  h1 { color: #333; margin: 0 }</span>\n"
                + "\n"
                + "<span class=\"tok-string\">  p { a: b }</span>\n"
                + "<span class=\"tok-key\">boxes</span>: []\n",
                hl("css: |\n  h1 { color: #333; margin: 0 }\n\n  p { a: b }\nboxes: []\n"));
        assertEquals("  <span class=\"tok-key\">text</span>: &gt;-\n<span class=\"tok-string\">    folded # not a comment</span>\n"
                + "  <span class=\"tok-key\">next</span>: 1",
                hl("  text: >-\n    folded # not a comment\n  next: 1"));
    }

    @Test
    void listItemsAndNestedKeys() {
        assertEquals("<span class=\"tok-key\">boxes</span>:\n  - <span class=\"tok-key\">id</span>: x\n    "
                + "<span class=\"tok-key\">each</span>: <span class=\"tok-string\">&quot;.step&quot;</span>\n",
                hl("boxes:\n  - id: x\n    each: \".step\"\n"));
    }
}
