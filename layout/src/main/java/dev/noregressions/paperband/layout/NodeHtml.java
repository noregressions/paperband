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
 *
 * <p>A {@link Replacement} can write some nodes differently -- a fence through
 * its block template -- and everything around them is written as it was.
 */
final class NodeHtml {

    /** How a directive is written as an attribute: {@code !step} is {@code data-paperband-step}. */
    static final String DIRECTIVE_PREFIX = "data-paperband-";

    /** Read as one element without children; see ContentNodes. */
    private static final Set<String> OPAQUE_TAGS = Set.of("svg", "math");

    private NodeHtml() {
    }

    /** HTML to write in place of a node, or null to write the node itself. */
    @FunctionalInterface
    interface Replacement {
        String html(Node n);
    }

    private static final Replacement NONE = n -> null;

    /**
     * A block's content, as {@code block.html} holds it: each top-level node's
     * outer HTML, one after another.
     */
    static String write(List<Node> nodes) {
        return write(nodes, NONE);
    }

    /** {@link #write(List)}, with {@code replace} deciding what some nodes become. */
    static String write(List<Node> nodes, Replacement replace) {
        StringBuilder out = new StringBuilder();
        for (Node n : nodes) {
            String replacement = n.tag() == null ? null : replace.html(n);
            if (replacement != null) {
                // Read the way a card's own content is: each element's outer
                // HTML, and any text between them that isn't just a line break.
                for (org.jsoup.nodes.Node r : Jsoup.parseBodyFragment(replacement).body().childNodes()) {
                    if (r instanceof Element e) out.append(e.outerHtml());
                    else if (r instanceof TextNode t && !t.isBlank()) out.append(t.text());
                }
            } else {
                out.append(n.tag() == null ? n.text() : element(n, replace).outerHtml());
            }
        }
        return out.toString();
    }

    /** Whether {@code replace} writes any of {@code nodes}, at any depth, differently. */
    static boolean replacesAny(List<Node> nodes, Replacement replace) {
        for (Node n : nodes) {
            if (n.tag() != null && replace.html(n) != null) return true;
            if (replacesAny(n.children(), replace)) return true;
        }
        return false;
    }

    private static Element element(Node n, Replacement replace) {
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
            if (c.tag() == null) {
                el.appendChild(TextNode.createFromEncoded(c.html()));
                continue;
            }
            String replacement = replace.html(c);
            if (replacement == null) {
                el.appendChild(element(c, replace));
            } else {
                // In place, as a pass that rewrites the DOM would have left it.
                for (org.jsoup.nodes.Node r : new java.util.ArrayList<>(
                        Jsoup.parseBodyFragment(replacement).body().childNodes())) {
                    el.appendChild(r);
                }
            }
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
