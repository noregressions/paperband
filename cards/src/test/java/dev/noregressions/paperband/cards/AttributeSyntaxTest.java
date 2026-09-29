package dev.noregressions.paperband.cards;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code {.class id=x key=value}} syntax, rendered straight through
 * commonmark-java so each placement rule is checked on its own HTML.
 */
class AttributeSyntaxTest {

    private static String html(String markdown) {
        AttributeSyntax syntax = new AttributeSyntax();
        List<Extension> ext = List.of(TablesExtension.create());
        var doc = Parser.builder().extensions(ext).postProcessor(syntax).build().parse(markdown);
        return HtmlRenderer.builder().extensions(ext)
                .attributeProviderFactory(c -> syntax.attributeProvider())
                .build().render(doc).trim();
    }

    @Nested
    @DisplayName("End of a block")
    class Trailing {

        @Test
        void heading_takes_class_id_and_attribute() {
            assertEquals("<h2 class=\"a b\" id=\"x\" k=\"v\">Heading</h2>",
                    html("## Heading {.a .b id=x k=v}"));
        }

        @Test
        void paragraph_takes_class_and_loses_the_space_before_it() {
            assertEquals("<p class=\"x\">para text</p>", html("para text {.x}"));
        }

        @Test
        void group_touching_the_text_still_goes_to_the_block() {
            assertEquals("<p class=\"x\">text</p>", html("text{.x}"));
        }

        @Test
        void blockquote_group_lands_on_its_paragraph() {
            assertEquals("<blockquote>\n<p class=\"warning\">Watch out</p>\n</blockquote>",
                    html("> Watch out {.warning}"));
        }

        @Test
        void list_item_group_lands_on_the_li() {
            assertEquals("<ul>\n<li class=\"x\">item</li>\n<li>two</li>\n</ul>",
                    html("- item {.x}\n- two"));
        }

        @Test
        void table_cell_group_lands_on_the_cell_and_alignment_survives() {
            String out = html("| a | b |\n|---:|---|\n| 1 {.x} | 2 |");
            assertTrue(out.contains("<td align=\"right\" class=\"x\">1</td>"), out);
        }

        @Test
        void multi_line_paragraph_uses_its_last_line() {
            assertEquals("<p class=\"x\">line one\nline two</p>", html("line one\nline two {.x}"));
        }

        @Test
        void quoted_values_keep_their_spaces() {
            assertEquals("<p class=\"c\" id=\"x\" k=\"v w\" j=\"u\">text</p>",
                    html("text {id=x .c k=\"v w\" j='u'}"));
        }
    }

    @Nested
    @DisplayName("After an inline element")
    class Inline {

        @Test
        void link() {
            assertEquals("<p>see <a href=\"y\" id=\"lnk\">x</a> after</p>", html("see [x](y){id=lnk} after"));
        }

        @Test
        void image() {
            assertEquals("<p><img src=\"i.png\" alt=\"alt\" class=\"wide\" /></p>", html("![alt](i.png){.wide}"));
        }

        @Test
        void code_span() {
            assertEquals("<p>use <code class=\"x\">code</code> here</p>", html("use `code`{.x} here"));
        }

        @Test
        void strong_at_the_end_of_a_paragraph_is_not_the_paragraph() {
            assertEquals("<p>text <strong class=\"x\">bold</strong></p>", html("text **bold**{.x}"));
        }
    }

    @Nested
    @DisplayName("A line of its own")
    class OwnLine {

        @Test
        void after_a_closing_fence_lands_on_the_pre() {
            assertEquals("<pre class=\"fs--1\"><code class=\"language-bash\">x\n</code></pre>",
                    html("```bash\nx\n```\n{.fs--1}"));
        }

        @Test
        void under_a_heading_merges_with_its_own_group() {
            assertEquals("<h2 class=\"a b\">H</h2>", html("## H {.a}\n{.b}"));
        }

        @Test
        void under_a_setext_heading_and_a_rule() {
            assertEquals("<h1 class=\"y\">Heading</h1>", html("Heading\n=======\n{.y}"));
            assertEquals("<hr class=\"rule\" />", html("* * *\n{.rule}"));
        }

        @Test
        void as_the_first_line_of_a_paragraph_lands_on_that_paragraph() {
            assertEquals("<p class=\"fs--1\">next para</p>", html("{.fs--1}\nnext para"));
        }

        @Test
        void with_nothing_before_it_stays_visible() {
            assertEquals("<p>{.lonely}</p>", html("{.lonely}"));
        }
    }

    @Nested
    @DisplayName("Fence info line")
    class Fence {

        @Test
        void group_goes_on_pre_and_language_stays_on_code() {
            assertEquals("<pre class=\"command\" id=\"c\"><code class=\"language-bash\">x\n</code></pre>",
                    html("```bash {.command id=c}\nx\n```"));
        }

        @Test
        void group_without_a_language() {
            assertEquals("<pre class=\"x\"><code>nolang\n</code></pre>", html("```{.x}\nnolang\n```"));
        }
    }

    @Nested
    @DisplayName("Not attributes")
    class NotAttributes {

        @Test
        void bare_words_in_braces_are_prose() {
            assertEquals("<p>{not attrs here}</p>", html("{not attrs here}"));
            assertEquals("<p>the set {CascadeType.SAVE, CascadeType.DELETE} is</p>",
                    html("the set {CascadeType.SAVE, CascadeType.DELETE} is"));
            assertEquals("<p>prose { vars.x }</p>", html("prose { vars.x }"));
        }

        @Test
        void dollar_brace_placeholders_are_prose() {
            assertEquals("<p>${home}/content</p>", html("${home}/content"));
        }

        @Test
        void a_group_mid_text_is_left_alone() {
            assertEquals("<p>text {.x} more text</p>", html("text {.x} more text"));
        }

        @Test
        void a_group_inside_code_is_never_read() {
            assertEquals("<p>Inline <code>{.x}</code> in code.</p>", html("Inline `{.x}` in code."));
        }
    }

    @Nested
    @DisplayName("Parsing a group")
    class Parse {

        @Test
        void keeps_source_order_with_class_first() {
            assertEquals(List.of("class", "id", "k"),
                    List.copyOf(AttributeSyntax.parse(" id=i .a k=v .b ").keySet()));
            assertEquals("a b", AttributeSyntax.parse("id=i .a k=v .b").get("class"));
        }

        @Test
        void class_equals_value_adds_a_class() {
            assertEquals(Map.of("class", "a b"), AttributeSyntax.parse(".a class=b"));
        }

        @Test
        void dotted_name_with_value_fails_with_the_right_spelling() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AttributeSyntax.parse(".step=1"));
            assertTrue(e.getMessage().contains("{step=1}"), e.getMessage());
            assertTrue(e.getMessage().contains("{.step step=1}"), e.getMessage());
        }

        @Test
        void a_hash_id_fails_with_the_id_spelling() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AttributeSyntax.parse(".x #y"));
            assertTrue(e.getMessage().contains("{id=y}"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> AttributeSyntax.parse("#y"));
        }

        @Test
        void anything_else_is_not_a_group() {
            assertNull(AttributeSyntax.parse(""));
            assertNull(AttributeSyntax.parse("step"));
            assertNull(AttributeSyntax.parse(".a,b"));
            assertNull(AttributeSyntax.parse(".a step"));
        }
    }
}
