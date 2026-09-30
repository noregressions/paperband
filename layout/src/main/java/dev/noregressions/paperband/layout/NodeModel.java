package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Node;
import dev.noregressions.paperband.pebble.LenientMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link Node} as a template sees it, holding the node it was read from.
 *
 * <p>The map is what a template reads: lenient, since most keys belong to
 * some types only ({@code {% if node.lang %}}), with its props alongside the
 * rest ({@code node.code}, not {@code node.props.code}) and the yes/no ones as
 * booleans, so {@code {% if node.ordered %}} isn't fooled by {@code "false"}.
 *
 * <p>The map loses things a template doesn't need -- the attribute order, the
 * props as written -- so it can't be turned back into the node. The node
 * itself travels with it instead, so {@code | html} writes it back exactly and
 * {@code drop} and {@code addClass} rebuild records, not maps. So does the way
 * its block writes fences: a {@code ```command} it writes goes through its
 * block template, as it would in {@code block.html}.
 */
final class NodeModel extends LenientMap<String, Object> {

    private final transient Node node;
    private final transient NodeHtml.Replacement fences;

    private NodeModel(Node node, NodeHtml.Replacement fences) {
        this.node = node;
        this.fences = fences;
    }

    /** {@code n} and everything under it, each written with {@code fences}. */
    static NodeModel of(Node n, NodeHtml.Replacement fences) {
        NodeModel m = new NodeModel(n, fences);
        m.put("type", n.type());
        m.put("tag", n.tag());
        m.put("id", n.id());
        m.put("classes", new ArrayList<>(n.classes()));
        m.put("attributes", LenientMap.of(n.attributes()));
        m.put("directives", LenientMap.of(n.directives()));
        m.put("text", n.text());
        m.put("html", n.html());
        n.props().forEach((k, v) -> m.put(k, switch (k) {
            case "ordered", "header", "drawn" -> Boolean.valueOf(v);
            default -> v;
        }));
        List<Map<String, Object>> children = new ArrayList<>(n.children().size());
        for (Node c : n.children()) {
            children.add(of(c, fences));
        }
        m.put("children", children);
        return m;
    }

    /** The node this was read from. */
    Node node() {
        return node;
    }

    /** How this node's block writes a fence: through its block template, or null to leave it. */
    NodeHtml.Replacement fences() {
        return fences;
    }

    // Two models are the same only if they're the same object: a map's equality
    // would call two identical paragraphs in different cards one node.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
