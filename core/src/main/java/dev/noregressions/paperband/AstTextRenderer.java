package dev.noregressions.paperband;

import org.commonmark.node.Node;

/** Renders an AST as an indented tree using box-drawing characters. */
public final class AstTextRenderer {

    private AstTextRenderer() {
    }

    public static String render(Node root) {
        StringBuilder sb = new StringBuilder();
        sb.append(label(root)).append('\n');
        renderChildren(root, "", sb);
        return sb.toString();
    }

    private static void renderChildren(Node parent, String prefix, StringBuilder sb) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            boolean last = child.getNext() == null;
            sb.append(prefix).append(last ? "└── " : "├── ").append(label(child)).append('\n');
            renderChildren(child, prefix + (last ? "    " : "│   "), sb);
        }
    }

    private static String label(Node node) {
        String detail = NodeInfo.detail(node);
        int line = NodeInfo.line(node);
        return NodeInfo.name(node)
                + (detail.isEmpty() ? "" : " " + detail)
                + (line > 0 ? "  (line " + line + ")" : "");
    }
}
