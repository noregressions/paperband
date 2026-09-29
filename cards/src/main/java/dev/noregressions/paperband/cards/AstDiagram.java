package dev.noregressions.paperband.cards;

import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A card's commonmark tree as a PlantUML mind map, for
 * {@code ast:} in the frontmatter: what paperband built from the markdown,
 * drawn inside the card it describes.
 *
 * <p>The tree is taken after paperband's own passes, so it shows what they
 * made rather than the raw parse: {@link Sections.Section} nodes with the
 * heading structure, the attributes and directives each node carries, and
 * {@code {!step}} markers with the text they were replaced by.
 *
 * <pre>
 * ast: true      block nodes, each with an excerpt of its text
 * ast: inline    every node, down to text runs and code spans
 * </pre>
 *
 * <p>The tree is drawn by the PlantUML block renderer when the book has it,
 * and otherwise as nested HTML lists the base stylesheet draws as a tree --
 * plainer, but a picture of the tree either way, with nothing to install.
 */
final class AstDiagram {

    /** How much of the tree to draw. */
    enum Detail {
        BLOCKS, INLINE;

        /**
         * @return the detail {@code value} asks for, or null for none
         * @throws IllegalArgumentException for a value that isn't one of the options
         */
        static Detail of(Object value) {
            if (value == null || Boolean.FALSE.equals(value)) return null;
            if (Boolean.TRUE.equals(value)) return BLOCKS;
            String s = value.toString().strip().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "false", "no", "off", "" -> null;
                case "true", "yes", "on", "blocks" -> BLOCKS;
                case "inline" -> INLINE;
                default -> throw new IllegalArgumentException("ast: '" + value
                        + "' isn't an option. Use ast: true for block nodes, or ast: inline"
                        + " to include text runs, code spans and links.");
            };
        }
    }

    /** Longest excerpt of a node's text before it's cut with an ellipsis. */
    private static final int EXCERPT = 48;

    /**
     * One node of the drawing: its type, what distinguishes it (level,
     * language, attributes), a short excerpt of its text, and its children.
     * Built once, then written as PlantUML or as HTML.
     */
    record Item(String type, String detail, String excerpt, List<Item> children) {
    }

    private final AttributeSyntax syntax;
    private final Detail detail;

    private AstDiagram(AttributeSyntax syntax, Detail detail) {
        this.syntax = syntax;
        this.detail = detail;
    }

    /** The drawing of {@code document}'s tree. */
    static Item tree(Node document, AttributeSyntax syntax, Detail detail) {
        return new AstDiagram(syntax, detail).item(document);
    }

    /** {@code tree} as PlantUML source. */
    static String plantuml(Item tree) {
        // A mind map grows left to right: depth goes across and siblings go
        // down, so a long card makes a tall diagram that still fits a page
        // column. A work-breakdown tree put siblings side by side, and a card
        // of ordinary length came out several page-widths wide.
        StringBuilder out = new StringBuilder("@startmindmap\n");
        plantuml(tree, 1, out);
        return out.append("@endmindmap\n").toString();
    }

    private static void plantuml(Item item, int depth, StringBuilder out) {
        StringBuilder label = new StringBuilder(item.type());
        if (!item.detail().isEmpty()) label.append(' ').append(item.detail());
        if (item.excerpt() != null) label.append("  \"").append(item.excerpt()).append('"');
        out.append("*".repeat(depth)).append(' ').append(escape(label.toString())).append('\n');
        for (Item child : item.children()) plantuml(child, depth + 1, out);
    }

    /**
     * {@code tree} as nested lists, drawn as a tree by the base stylesheet's
     * {@code .paperband-ast-tree} rules: what a book without the PlantUML block
     * renderer gets instead of the diagram's source.
     */
    static String html(Item tree) {
        StringBuilder out = new StringBuilder("<div class=\"paperband-ast-tree\"><ul>");
        html(tree, out);
        return out.append("</ul></div>").toString();
    }

    private static void html(Item item, StringBuilder out) {
        out.append("<li><span class=\"ast-node\"><b>").append(esc(item.type())).append("</b>");
        if (!item.detail().isEmpty()) {
            out.append(" <span class=\"ast-detail\">").append(esc(item.detail())).append("</span>");
        }
        if (item.excerpt() != null) {
            out.append(" <q class=\"ast-text\">").append(esc(item.excerpt())).append("</q>");
        }
        out.append("</span>");
        if (!item.children().isEmpty()) {
            out.append("<ul>");
            for (Item child : item.children()) html(child, out);
            out.append("</ul>");
        }
        out.append("</li>");
    }

    private static String esc(String s) {
        return org.jsoup.nodes.Entities.escape(s);
    }

    private Item item(Node node) {
        List<Item> children = new ArrayList<>();
        // Below a block, inline content is summarised in the block's excerpt
        // unless the whole tree was asked for.
        if (detail == Detail.INLINE || hasBlockChildren(node)) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (detail == Detail.BLOCKS && !isBlock(child)) continue;
                children.add(item(child));
            }
        }
        return new Item(type(node), detailOf(node), excerpt(node), List.copyOf(children));
    }

    private String detailOf(Node node) {
        StringBuilder sb = new StringBuilder();
        if (node instanceof Heading h) {
            sb.append(" h").append(h.getLevel());
            // The consumed title stays in the tree, outside any section.
            if (h.getLevel() == 1 && !(h.getParent() instanceof Sections.Section s && s.getFirstChild() == h)) {
                sb.append(" (title)");
            }
        }
        if (node instanceof Sections.Section s) sb.append(" h").append(s.level());
        if (node instanceof FencedCodeBlock f && f.getInfo() != null && !f.getInfo().isBlank()) {
            sb.append(' ').append(f.getInfo().strip());
        }
        if (node instanceof OrderedList ol && ol.getMarkerStartNumber() != null
                && ol.getMarkerStartNumber() != 1) {
            sb.append(" start=").append(ol.getMarkerStartNumber());
        }
        if (node instanceof Link l) sb.append(' ').append(l.getDestination());
        if (node instanceof Image i) sb.append(' ').append(i.getDestination());
        if (node instanceof Directives.Marker m) {
            sb.append(" {!").append(m.name()).append("} = \"").append(m.text()).append('"');
        }
        // A section's attributes and directives live on its heading.
        Node owner = node instanceof Sections.Section s ? s.heading() : node;
        String attrs = attributes(owner);
        if (!attrs.isEmpty() && !(node instanceof Heading && node.getParent() instanceof Sections.Section)) {
            sb.append(' ').append(attrs);
        }
        return sb.toString().strip();
    }

    /** {@code .class id=x key=value !directive=value}, in that order. */
    private String attributes(Node node) {
        Map<String, String> attrs = syntax.attributesOf(node);
        StringBuilder sb = new StringBuilder();
        String classes = attrs.get("class");
        if (classes != null) for (String c : classes.split("\\s+")) sb.append(" .").append(c);
        if (attrs.containsKey("id")) sb.append(" id=").append(attrs.get("id"));
        attrs.forEach((k, v) -> {
            if (k.equals("class") || k.equals("id")) return;
            sb.append(' ').append(k);
            if (!v.isEmpty()) sb.append('=').append(v);
        });
        return sb.toString().strip();
    }

    /** A short run of the node's own text: literals for inline nodes, a summary for blocks. */
    private String excerpt(Node node) {
        String text;
        if (node instanceof Text t) text = t.getLiteral();
        else if (node instanceof Code c) text = c.getLiteral();
        else if (node instanceof HtmlInline h) text = h.getLiteral();
        else if (node instanceof HtmlBlock h) text = h.getLiteral();
        else if (node instanceof FencedCodeBlock f) text = f.getLiteral();
        else if (detail == Detail.BLOCKS && isBlock(node) && !hasBlockChildren(node)) text = textOf(node);
        else return null;
        text = text.replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) return null;
        return text.length() <= EXCERPT ? text : text.substring(0, EXCERPT - 1) + "…";
    }

    private static String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                sb.append(t.getLiteral());
            }

            @Override
            public void visit(Code c) {
                sb.append(c.getLiteral());
            }

            @Override
            public void visit(org.commonmark.node.SoftLineBreak b) {
                sb.append(' ');
            }

            @Override
            public void visit(CustomNode n) {
                if (n instanceof Directives.Marker m) sb.append(m.text());
                visitChildren(n);
            }
        });
        return sb.toString();
    }

    private static String type(Node node) {
        if (node instanceof Sections.Section) return "Section";
        if (node instanceof Directives.Marker) return "Marker";
        return node.getClass().getSimpleName();
    }

    private static boolean isBlock(Node node) {
        return node instanceof org.commonmark.node.Block || node instanceof TableHead
                || node instanceof TableBody || node instanceof TableRow || node instanceof TableCell;
    }

    private static boolean hasBlockChildren(Node node) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (isBlock(c)) return true;
        }
        return false;
    }

    /**
     * Keep a label literal: PlantUML reads creole markup ({@code **bold**},
     * {@code //italic//}, {@code <b>}) in node text, and card text is full of
     * those characters -- URLs alone are {@code //}. A {@code ~} before one of
     * the markup characters prints it as-is; before any other character it
     * prints the tilde too, so only these are escaped. A literal {@code ~} is
     * {@code <U+007E>}, because {@code ~~} starts a wavy underline.
     */
    private static String escape(String label) {
        StringBuilder sb = new StringBuilder(label.length() + 8);
        for (char c : label.toCharArray()) {
            if (c == '~') {
                sb.append("<U+007E>");
                continue;
            }
            if ("*/_-\"<[]#".indexOf(c) >= 0) sb.append('~');
            sb.append(c);
        }
        return sb.toString();
    }
}
