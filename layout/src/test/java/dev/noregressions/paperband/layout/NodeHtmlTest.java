package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Node;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Writing a block's nodes back gives the HTML the block was read from. */
class NodeHtmlTest {

    /** The repository's own books: every card they hold is a case. */
    private static final List<Path> BOOKS = List.of(
            Path.of("../guide/src/main/paperband/content"),
            Path.of("../examples/kitchen-sink/src/main/paperband/content"));

    @Test
    void every_block_of_the_repositorys_books_writes_back_as_it_was_read() throws IOException {
        List<String> mismatches = new ArrayList<>();
        int blocks = 0;
        for (Path book : BOOKS) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(book)) {
                files = walk.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().startsWith("_"))
                        .sorted().toList();
            }
            for (Path f : files) {
                Card card = new CardLoader().parse(f, Files.readString(f));
                blocks += check(card.blocks(), f, mismatches);
            }
        }
        assertTrue(blocks > 200, "the books should be most of the test: " + blocks + " blocks");
        assertTrue(mismatches.isEmpty(), mismatches.size() + " of " + blocks + " blocks differ:\n"
                + String.join("\n\n", mismatches.subList(0, Math.min(5, mismatches.size()))));
    }

    private static int check(List<Block> blocks, Path source, List<String> mismatches) {
        int n = 0;
        for (Block b : blocks) {
            n++;
            String written = NodeHtml.write(b.nodes());
            if (!written.equals(b.html())) {
                mismatches.add(source + " / " + b.heading() + "\n--- read\n" + b.html() + "\n--- written\n" + written);
            }
            n += check(b.children(), source, mismatches);
        }
        return n;
    }

    @Test
    void a_drawing_is_written_from_its_own_html() {
        String html = "<figure class=\"diagram\"><svg viewBox=\"0 0 1 1\"><text x=\"0\">A</text></svg></figure>";
        List<Node> nodes = ContentNodes.of(html);
        assertEquals(Jsoup(html), NodeHtml.write(nodes));
    }

    @Test
    void a_node_built_without_an_attribute_order_writes_id_class_attributes_directives() {
        Node n = new Node("fence", "pre", "x", Set.of("command"), Map.of("title", "t"), Map.of("step", "1"),
                "", "", List.of(), Map.of());
        assertEquals("<pre id=\"x\" class=\"command\" title=\"t\" data-paperband-step=\"1\"></pre>",
                NodeHtml.write(List.of(n)));
    }

    private static String Jsoup(String html) {
        return org.jsoup.Jsoup.parseBodyFragment(html).body().child(0).outerHtml();
    }
}
