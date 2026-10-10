package dev.noregressions.paperband;

import org.commonmark.node.*;

import java.util.List;

/** Human-readable descriptions of AST nodes, shared by the text and HTML renderers. */
public final class NodeInfo {

    enum Category { ROOT, FILE, FRONT_MATTER, BLOCK, INLINE, TEXT }

    private static final int MAX_DETAIL = 80;

    private NodeInfo() {
    }

    /** Simple class name, qualified by the enclosing class for nested ones (e.g. {@code FrontMatterBlock.Entry}). */
    public static String name(Node node) {
        String name = node.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
    }

    static Category category(Node node) {
        return switch (node) {
            case Document d -> Category.ROOT;
            case MarkdownFile f -> Category.FILE;
            case FrontMatterBlock b -> Category.FRONT_MATTER;
            case FrontMatterBlock.Entry e -> Category.FRONT_MATTER;
            case Text t -> Category.TEXT;
            case Code c -> Category.TEXT;
            case Block b -> Category.BLOCK;
            default -> Category.INLINE;
        };
    }

    /** Short type-specific detail for a node plus any marker handles, or an empty string. */
    static String detail(Node node) {
        String d = baseDetail(node);
        Attributes a = MarkdownFile.attributesOf(node);
        return a == null ? d : (d.isEmpty() ? a.toString() : d + " " + a);
    }

    private static String baseDetail(Node node) {
        if (node instanceof MarkdownFile f) {
            return f.getPath().toString();
        }
        String d = switch (node) {
            case FrontMatterBlock.Entry e -> e.getKey() + ": " + FrontMatter.format(e.getValue());
            case Heading h -> "h" + h.getLevel();
            case Text t -> quote(t.getLiteral());
            case Code c -> quote(c.getLiteral());
            case FencedCodeBlock f -> (f.getInfo() == null || f.getInfo().isEmpty() ? "" : "[" + f.getInfo() + "] ")
                    + quote(f.getLiteral());
            case IndentedCodeBlock i -> quote(i.getLiteral());
            case HtmlBlock h -> quote(h.getLiteral());
            case HtmlInline h -> quote(h.getLiteral());
            case Link l -> l.getDestination() + (l.getTitle() != null ? " \"" + l.getTitle() + "\"" : "");
            case Image i -> i.getDestination() + (i.getTitle() != null ? " \"" + i.getTitle() + "\"" : "");
            case LinkReferenceDefinition r -> "[" + r.getLabel() + "] " + r.getDestination();
            case OrderedList o -> "start=" + o.getMarkerStartNumber() + (o.isTight() ? ", tight" : ", loose");
            case BulletList b -> "marker=" + b.getMarker() + (b.isTight() ? ", tight" : ", loose");
            default -> "";
        };
        return d.length() > MAX_DETAIL ? d.substring(0, MAX_DETAIL - 1) + "…" : d;
    }

    /** 1-based source line of the node within its file, or -1 if unknown. */
    public static int line(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        return spans.isEmpty() ? -1 : spans.getFirst().getLineIndex() + 1;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }
}
