package dev.noregressions.paperband;

import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Links to another page by its front matter {@code id}, so a link doesn't depend on where the
 * file is or what it's called:
 *
 * <pre>
 * See [the jdeps chapter](#jdeps).      the page whose front matter says id: jdeps
 * See Chapter [#](#jdeps).              # as the text: the page's number, its chapter (or appendix)
 * See [](#jdeps).                       no text: the page's title
 * </pre>
 *
 * An id that's also a heading or {@code id=} in the linking file is a link within that file, as
 * usual. In an output that leaves the page out, the link's text stays but it isn't a link.
 */
final class PageLinks {

    /** The front matter key a page link names. */
    static final String ID = "id";

    private static final Map<Node, Map<String, MarkdownFile>> BY_ROOT = Collections.synchronizedMap(new WeakHashMap<>());

    private PageLinks() {
    }

    /** The page {@code link} names by id, or null if it isn't a page link. */
    static MarkdownFile target(Link link) {
        String dest = link.getDestination();
        if (dest == null || !dest.startsWith("#") || dest.length() < 2) {
            return null;
        }
        String id = dest.substring(1);
        MarkdownFile own = fileOf(link);
        if (own == null || own.anchors().contains(id)) {
            return null;
        }
        Node root = own;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return BY_ROOT.computeIfAbsent(root, PageLinks::index).get(id);
    }

    /** The text a page link shows: its own, unless it's {@code #} (the number) or empty (the title); null for its own. */
    static String label(Link link, MarkdownFile target) {
        Node first = link.getFirstChild();
        if (first == null) {
            return title(target);
        }
        if (first instanceof Text t && first.getNext() == null && t.getLiteral().equals("#")) {
            Object n = target.getFrontMatter().get("chapter");
            if (n == null) {
                n = target.getFrontMatter().get("appendix");
            }
            return n != null ? n.toString() : title(target);
        }
        return null;
    }

    private static String title(MarkdownFile f) {
        Object t = f.getFrontMatter().get("title");
        return t != null ? t.toString() : f.getPath().getFileName().toString();
    }

    private static Map<String, MarkdownFile> index(Node root) {
        Map<String, MarkdownFile> out = new LinkedHashMap<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof MarkdownFile f && f.getFrontMatter().get(ID) != null) {
                out.putIfAbsent(f.getFrontMatter().get(ID).toString(), f);
            }
        }
        return out;
    }

    private static MarkdownFile fileOf(Node node) {
        for (Node p = node; p != null; p = p.getParent()) {
            if (p instanceof MarkdownFile f) {
                return f;
            }
        }
        return null;
    }
}
