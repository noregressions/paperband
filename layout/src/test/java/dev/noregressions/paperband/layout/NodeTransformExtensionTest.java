package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code drop}, {@code addClass} and {@code | html}: a block's content changed
 * as data and written back, the card itself untouched.
 */
class NodeTransformExtensionTest {

    /** Content as loading leaves it: a ```command and ```console still plain code blocks. */
    private static final String CONTENT = "<p>Why it matters.</p>"
            + "<p class=\"instructions\">Build it with <em>Maven</em>.</p>"
            + "<pre><code class=\"language-command\">mvn package\n</code></pre>"
            + "<pre><code class=\"language-console\">$ mvn package\nBUILD SUCCESS\n</code></pre>"
            + "<ul><li>one</li><li>two <code>x</code></li></ul>";

    private static Card card() {
        Block step = new Block(Block.Kind.HEADING_SECTION, null, Set.of("build"), "Step 1 Build", 2, CONTENT,
                List.of(), Map.of(), Map.of("step", "1"), ContentNodes.of(CONTENT));
        return new Card("c", Path.of("c.md"), new Frontmatter(Map.of()), "C", List.of(step));
    }

    private static String render(Path book, String body) throws IOException {
        Path layouts = Files.createDirectories(book.resolve("layouts"));
        Files.writeString(layouts.resolve("t.html"), "{% set b = card.blocks[0] %}" + body);
        BookConfig config = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new LayoutEngine(book).render(card(), new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4"),
                "t");
    }

    @Test
    void html_of_the_untouched_nodes_is_block_html(@TempDir Path book) throws IOException {
        String html = render(book, "[{{ b | html | raw }}]=[{{ b.html | raw }}]");
        int eq = html.indexOf("]=[");
        String written = html.substring(html.indexOf('[') + 1, eq);
        String original = html.substring(eq + 3, html.lastIndexOf(']'));
        assertEquals(original, written);
    }

    @Test
    void html_writes_a_fence_through_its_block_template(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | find('pre.command') | first | html | raw }}");
        assertTrue(html.contains("<pre class=\"command\"><code class=\"language-bash\">mvn package"), html);
    }

    @Test
    void drop_leaves_out_what_matches_and_what_is_in_it(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | drop('pre.console, li:has(code)') | html | raw }}");
        assertFalse(html.contains("BUILD SUCCESS"), html);
        assertFalse(html.contains("two"), html);
        assertTrue(html.contains("<li>one</li>"), html);
        assertTrue(html.contains("<pre class=\"command\">"), html);
    }

    @Test
    void a_changed_parent_has_its_text_and_html_written_again(@TempDir Path book) throws IOException {
        String html = render(book, "{% set list = b | drop('li:first-child') | find('ul') | first %}"
                + "[{{ list.text }}][{{ list.html | raw }}]");
        assertTrue(html.contains("[two x][<ul>"), html);
        assertTrue(html.contains("<li>two <code>x</code></li>"), html);
        assertFalse(html.contains("one"), html);
    }

    @Test
    void add_class_adds_a_class_the_selector_and_writer_both_see(@TempDir Path book) throws IOException {
        String html = render(book, "{% set t = b | addClass('p.instructions', 'lead') %}"
                + "[{{ t | find('p.lead') | length }}]{{ t | find('p.lead') | html | raw }}");
        assertTrue(html.contains("[1]<p class=\"instructions lead\">Build it with <em>Maven</em>.</p>"), html);
    }

    @Test
    void a_class_added_to_a_fence_reaches_its_block_template(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | addClass('pre.command', 'wide') | find('pre.command') | html | raw }}");
        assertTrue(html.contains("<pre class=\"command wide\">"), html);
    }

    @Test
    void transforms_chain_and_the_card_is_unchanged(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | drop('p:not(.instructions)') | addClass('p', 'lead') | drop('ul')"
                + " | html | raw }}|{{ b.html | raw }}");
        String changed = html.substring(0, html.indexOf('|'));
        assertTrue(changed.startsWith("<p class=\"instructions lead\">"), changed);
        assertFalse(changed.contains("Why it matters"), changed);
        assertFalse(changed.contains("<ul>"), changed);
        assertTrue(html.substring(html.indexOf('|')).contains("Why it matters."), "block.html is as it was");
    }

    @Test
    void blank_puts_an_empty_div_where_each_match_was(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | blank('pre.console, li', 'space') | html | raw }}");
        assertFalse(html.contains("BUILD SUCCESS"), html);
        assertTrue(html.contains("<div class=\"space\"></div>"), html);
        assertTrue(html.contains("<ul>"), "only the matches are blanked: " + html);
        assertEquals(3, html.split("class=\"space\"", -1).length - 1, html);
    }

    @Test
    void a_class_name_that_isnt_one_fails(@TempDir Path book) {
        Exception e = assertThrows(Exception.class,
                () -> render(book, "{{ b | addClass('p', 'x\" onclick=\"y') }}"));
        assertTrue(messages(e).contains("class name of letters"), messages(e));
    }

    @Test
    void a_query_entry_is_not_a_node(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> render(book, "{{ card | query('p') | html }}"));
        assertTrue(messages(e).contains("e.node or e.block"), messages(e));
    }

    @Test
    void a_query_entrys_node_and_block_can_be_changed(@TempDir Path book) throws IOException {
        String html = render(book, "{% for e in card | query('block.build') %}"
                + "{{ e.block | drop('pre.console') | html | raw }}{% endfor %}");
        assertTrue(html.contains("<pre class=\"command\">"), html);
        assertFalse(html.contains("BUILD SUCCESS"), html);
    }

    @Test
    void replace_puts_an_empty_block_with_attributes_where_each_match_was(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | replace('pre.console', '{.output-space lines=3}') | html | raw }}");
        assertFalse(html.contains("BUILD SUCCESS"), html);
        assertTrue(html.contains("<div class=\"output-space\" lines=\"3\"></div><ul>"), html);
    }

    @Test
    void insert_before_and_after_add_a_sibling(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | insertBefore('p.instructions', '{.before}')"
                + " | insertAfter('p.instructions', '{.after}') | html | raw }}");
        assertTrue(html.contains("<p>Why it matters.</p><div class=\"before\"></div><p class=\"instructions\">"), html);
        assertTrue(html.contains("<em>Maven</em>.</p><div class=\"after\"></div><pre"), html);
    }

    @Test
    void prepend_and_append_add_a_first_and_last_child(@TempDir Path book) throws IOException {
        String html = tight(render(book, "{{ b | prepend('ul', '{.first}') | append('ul', '{.last}')"
                + " | find('ul') | html | raw }}"));
        assertTrue(html.startsWith("<ul><div class=\"first\"></div><li>one</li>"), html);
        assertTrue(html.endsWith("<div class=\"last\"></div></ul>"), html);
    }

    @Test
    void wrap_puts_a_match_inside_a_new_block(@TempDir Path book) throws IOException {
        String html = tight(render(book, "{{ b | wrap('ul', '{.aside}') | html | raw }}"));
        assertTrue(html.contains("<div class=\"aside\"><ul><li>one</li>"), html);
        assertTrue(html.contains("</ul></div>"), html);
    }

    @Test
    void remove_class_and_set_change_what_a_match_carries(@TempDir Path book) throws IOException {
        String html = render(book, "{{ b | removeClass('p.instructions', 'instructions') | set('ul', '{data-kind=list}')"
                + " | html | raw }}");
        assertTrue(html.contains("<p>Build it with <em>Maven</em>.</p>"), html);
        assertTrue(html.contains("<ul data-kind=\"list\">"), html);
    }

    @Test
    void keep_leaves_out_everything_that_isnt_a_match_in_one_or_around_one(@TempDir Path book) throws IOException {
        String html = tight(render(book, "{{ b | keep('p.instructions, li:first-child') | html | raw }}"));
        assertTrue(html.contains("<p class=\"instructions\">Build it with <em>Maven</em>.</p>"), "its insides stay: " + html);
        assertTrue(html.contains("<ul><li>one</li></ul>"), "the list around a match stays, its other items go: " + html);
        assertFalse(html.contains("Why it matters"), html);
        assertFalse(html.contains("<pre"), html);
    }

    @Test
    void a_filter_never_matches_what_it_added_but_the_next_one_does(@TempDir Path book) throws IOException {
        String html = render(book, "{% set t = b | insertAfter('li, .li', '{.li}') %}"
                + "[{{ t | find('.li') | length }}][{{ t | insertAfter('.li', '{.z}') | find('.z') | length }}]");
        assertTrue(html.contains("[2][2]"), html);
    }

    @Test
    void a_block_cant_carry_what_a_card_cant(@TempDir Path book) {
        for (String spec : List.of("{style=\"color:red\"}", "{onclick=x}", "{href=javascript:x}", "{.x\"y}")) {
            Exception e = assertThrows(Exception.class,
                    () -> render(book, "{{ b | replace('p', '" + spec + "') }}"), spec);
            assertTrue(messages(e).contains("replace: "), messages(e));
        }
    }

    @Test
    void set_takes_attributes_only(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> render(book, "{{ b | set('p', '{.lead}') }}"));
        assertTrue(messages(e).contains("use addClass for a class"), messages(e));
    }

    /** {@code html} without the whitespace a rewritten list is printed with between its tags. */
    private static String tight(String html) {
        return html.replaceAll(">\\s+<", "><");
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getMessage()).append('\n');
        return sb.toString();
    }
}
