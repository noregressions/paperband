package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Node;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.List;
import java.util.Set;

/**
 * Writes {@link Node}s back to HTML: a block's content, from its data.
 *
 * <p>Each node becomes a jsoup element again -- its tag, its attributes in the
 * order it carried them, its children -- and jsoup prints it. That's the same
 * printer that wrote {@code block.html} in the first place, so an unchanged
 * tree prints the same bytes, and a layout that writes a block from its nodes
 * hands a theme exactly the HTML it had before.
 *
 * <p>A drawing ({@code svg}, {@code math}) is read without its insides, so it
 * is written from its own {@code html}.
 */
final class NodeHtml {

    /** How a directive is written as an attribute: {@code !step} is {@code data-paperband-step}. */
    static final String DIRECTIVE_PREFIX = "data-paperband-";

    /** Read as one element without children; see ContentNodes. */
    private static final Set<String> OPAQUE_TAGS = Set.of("svg", "math");

    private NodeHtml() {
    }

    /**
     * A block's content, as {@code block.html} holds it: each top-level node's
     * outer HTML, one after another.
     */
    static String write(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        for (Node n : nodes) {
            out.append(n.tag() == null ? n.text() : element(n).outerHtml());
        }
        return out.toString();
    }

    /** One node as a jsoup node, children and all. */
    static org.jsoup.nodes.Node toJsoup(Node n) {
        return n.tag() == null ? TextNode.createFromEncoded(n.html()) : element(n);
    }

    private static Element element(Node n) {
        if (OPAQUE_TAGS.contains(n.tag())) {
            Element drawn = Jsoup.parseBodyFragment(n.html()).body().firstElementChild();
            if (drawn != null) return drawn.clone();
        }
        Element el = new Element(n.tag());
        List<String> order = n.attributeOrder();
        if (order.isEmpty()) {
            if (n.id() != null) el.attr("id", n.id());
            if (!n.classes().isEmpty()) el.attr("class", String.join(" ", n.classes()));
            n.attributes().forEach(el::attr);
            n.directives().forEach((k, v) -> el.attr(DIRECTIVE_PREFIX + k, v));
        } else {
            for (String key : order) {
                el.attr(key, value(n, key));
            }
        }
        for (Node c : n.children()) {
            el.appendChild(toJsoup(c));
        }
        return el;
    }

    /** The value {@code key} had, from whichever of the node's maps holds it. */
    private static String value(Node n, String key) {
        if (key.equals("id")) return n.id() == null ? "" : n.id();
        if (key.equals("class")) return String.join(" ", n.classes());
        if (key.startsWith(DIRECTIVE_PREFIX)) {
            String v = n.directives().get(key.substring(DIRECTIVE_PREFIX.length()));
            if (v != null) return v;
        }
        String v = n.attributes().get(key);
        return v == null ? "" : v;
    }
}
