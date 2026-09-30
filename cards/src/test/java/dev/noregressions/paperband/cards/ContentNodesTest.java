package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Node;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A block's content as {@link Node}s: the same content as its HTML, as data. */
class ContentNodesTest {

    private static List<Block> blocks(String md) {
        return new CardLoader().parse(Path.of("t.md"), "---\ntitle: T\n---\n" + md).blocks();
    }

    private static List<String> types(List<Node> nodes) {
        return nodes.stream().map(Node::type).toList();
    }

    @Test
    void a_blocks_nodes_are_its_own_content_in_order_children_excluded() {
        List<Block> bs = blocks("""
                Before any heading.

                ## Install

                First.

                - one

                ### Nested

                Inside.
                """);
        assertEquals(List.of("paragraph"), types(bs.get(0).nodes()), "the intro");
        Block install = bs.get(1);
        assertEquals(List.of("paragraph", "list"), types(install.nodes()));
        assertEquals("First.", install.nodes().get(0).text());
        assertEquals("<p>First.</p>", install.nodes().get(0).html());
        assertEquals(List.of("paragraph"), types(install.children().get(0).nodes()),
                "a nested section's content is its own, as with html");
    }

    @Test
    void a_paragraph_keeps_its_classes_and_inline_content() {
        Node p = blocks("""
                ## {!step} Install

                Run `mvn` from [the root](https://example.test). {.instructions}
                """).get(0).nodes().get(0);
        assertEquals("paragraph", p.type());
        assertEquals(List.of("instructions"), List.copyOf(p.classes()));
        assertEquals("Run mvn from the root.", p.text());
        assertEquals(List.of("text", "code", "text", "link", "text"), types(p.children()));
        Node link = p.children().get(3);
        assertEquals("https://example.test", link.attributes().get("href"));
        assertEquals("the root", link.text());
        assertEquals(" from ", p.children().get(2).text(), "the space between words is kept");
    }

    @Test
    void a_templated_fence_stays_code_for_layout_to_write() {
        Node fence = blocks("""
                ## Check

                ```command
                java -version
                ```
                """).get(0).nodes().get(0);
        assertEquals("fence", fence.type());
        assertEquals("pre", fence.tag());
        assertTrue(fence.html().contains("language-command"), "its template is layout's: " + fence.html());
        assertEquals("command", fence.props().get("lang"));
        assertEquals("java -version", fence.props().get("code").strip());
        assertNull(fence.props().get("drawn"));
    }

    @Test
    void an_ordinary_fence_has_its_language_and_code() {
        Node fence = blocks("""
                ## Code

                ```java
                class A {}
                ```
                """).get(0).nodes().get(0);
        assertEquals("fence", fence.type());
        assertEquals("java", fence.props().get("lang"));
        assertEquals("class A {}\n", fence.props().get("code"));
    }

    @Test
    void a_fence_replaced_twice_is_still_the_fence() {
        Node fence = blocks("""
                ## Change

                ```diff-card
                @@removed
                old();
                @@added
                updated();
                ```
                """).get(0).nodes().get(0);
        assertEquals("figure", fence.tag(), fence.html());
        assertEquals("fence", fence.type());
        assertEquals("diff-card", fence.props().get("lang"));
        assertEquals("true", fence.props().get("drawn"), "a Java pass drew it; layout writes it as it is");
        assertTrue(fence.props().get("code").contains("@@added"), fence.props().get("code"));
        assertEquals(List.of("fence"), fence.children().stream()
                .flatMap(c -> c.children().stream()).filter(n -> n.type().equals("fence"))
                .map(Node::type).distinct().toList(), "its columns are fences too");
    }

    @Test
    void lists_and_tables_say_what_only_they_have() {
        List<Node> nodes = blocks("""
                ## Data

                - a
                - b

                1. first

                | h |
                |---|
                | v |
                """).get(0).nodes();
        assertEquals(List.of("list", "list", "table"), types(nodes));
        assertEquals("false", nodes.get(0).props().get("ordered"));
        assertEquals(List.of("item", "item"), types(nodes.get(0).children()));
        assertEquals("true", nodes.get(1).props().get("ordered"));
        List<Node> rows = nodes.get(2).children().stream().flatMap(section -> section.children().stream()).toList();
        assertEquals(List.of("row", "row"), types(rows));
        assertEquals(Map.of("header", "true"), rows.get(0).children().get(0).props());
        assertEquals(Map.of("header", "false"), rows.get(1).children().get(0).props());
    }

    @Test
    void a_directive_on_a_fence_is_a_directive_not_an_attribute() {
        Node fence = blocks("""
                ## Steps

                ```bash {!step .command}
                make
                ```
                """).get(0).nodes().get(0);
        assertEquals("1", fence.directives().get("step"), fence.html());
        assertFalse(fence.attributes().keySet().stream().anyMatch(k -> k.startsWith("data-paperband-")));
    }

    @Test
    void a_drawing_is_one_node_whose_text_keeps_its_labels_apart() {
        Node svg = ContentNodes.of("<svg><g><text>3. Tree</text><text>structure</text></g></svg>").get(0);
        assertEquals("element", svg.type());
        assertEquals(List.of(), svg.children(), "a drawing's insides aren't content");
        assertEquals("3. Tree structure", svg.text());
    }

    @Test
    void an_html_card_has_nodes_too() {
        Block b = new CardLoader().parse(Path.of("t.html"),
                "<h1>T</h1><h2>One</h2><p class=\"instructions\">Do it.</p>").blocks().get(0);
        assertEquals(List.of("paragraph"), types(b.nodes()));
        assertEquals("Do it.", b.nodes().get(0).text());
    }
}
