package dev.noregressions.paperband.cards;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {.x}} in the middle of text: a class from there to {@code {/x}}, or
 * to the end of the paragraph, heading, list item or cell it's in.
 *
 * <pre>
 * See {.warn}this part{/warn} first.   →  See &lt;span class="warn"&gt;this part&lt;/span&gt; first.
 * {.lead} The objective is to…          →  &lt;span class="lead"&gt;The objective is to…&lt;/span&gt;
 * </pre>
 *
 * <p>Runs after {@link AttributeSyntax}, so a group it places keeps its
 * meaning: one at the end of a block still classes the block (and a heading's,
 * its section), one on its own line still classes its neighbour, and one
 * touching a link or code span still classes that element. Every group left in
 * the text after that opens a span. A group can carry an id and attributes as
 * well as classes; spans nest, and {@code {/x}} closes the innermost open span
 * with class {@code x}.
 *
 * <p>A span stays inside the element it opened in, the way the element's own
 * markup does: {@code {/x}} has to close a span opened in the same run of
 * text, not one outside a link or emphasis around it. A close with nothing to
 * close, spans that overlap rather than nest, and a {@code !directive} in a
 * span's group all fail the build.
 */
final class Spans implements PostProcessor {

    private static final Pattern GROUP_OR_CLOSE = Pattern.compile("\\{/([A-Za-z][\\w-]*)\\}|\\{([^{}\\n]*)\\}");

    /** A run of inline content with the attributes its opening group gave it. */
    static final class Span extends CustomNode {
        private final Set<String> classes;

        Span(Set<String> classes) {
            this.classes = classes;
        }

        Set<String> classes() {
            return classes;
        }
    }

    /** Where a span starts, until the pass turns it into a {@link Span}. */
    private static final class Open extends CustomNode {
        final Map<String, String> attrs;
        final String source;

        Open(Map<String, String> attrs, String source) {
            this.attrs = attrs;
            this.source = source;
        }
    }

    /** Where a span with this class ends. */
    private static final class Close extends CustomNode {
        final String name;

        Close(String name) {
            this.name = name;
        }
    }

    private final AttributeSyntax syntax;

    Spans(AttributeSyntax syntax) {
        this.syntax = syntax;
    }

    @Override
    public Node process(Node document) {
        List<Text> texts = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                texts.add(text);
            }
        });
        Set<Node> parents = new LinkedHashSet<>();
        for (Text text : texts) {
            Node parent = text.getParent();     // before split unlinks it
            if (split(text)) parents.add(parent);
        }
        for (Node parent : parents) build(parent);
        return document;
    }

    /** Replace each group and close in {@code text} with a marker node. @return whether any was found */
    private boolean split(Text text) {
        String literal = text.getLiteral();
        Matcher m = GROUP_OR_CLOSE.matcher(literal);
        List<Node> pieces = new ArrayList<>();
        int from = 0;
        while (m.find()) {
            Node marker;
            if (m.group(1) != null) {
                marker = new Close(m.group(1));
            } else {
                Map<String, String> attrs = AttributeSyntax.parse(m.group(2));
                if (attrs == null) continue;        // prose in braces: stays text
                for (String key : attrs.keySet()) {
                    if (key.startsWith("!")) {
                        throw new IllegalArgumentException("'" + m.group() + "' opens a span, and a"
                                + " span can't carry " + key + ". Write {" + key + "} on its own, where"
                                + " its text should go.");
                    }
                }
                marker = new Open(attrs, m.group());
            }
            if (m.start() > from) pieces.add(new Text(literal.substring(from, m.start())));
            pieces.add(marker);
            from = m.end();
        }
        if (pieces.isEmpty()) return false;
        if (from < literal.length()) pieces.add(new Text(literal.substring(from)));
        for (Node piece : pieces) text.insertBefore(piece);
        text.unlink();
        return true;
    }

    /** Turn {@code parent}'s open and close markers into nested {@link Span}s. */
    private void build(Node parent) {
        Deque<Span> open = new ArrayDeque<>();
        Node node = parent.getFirstChild();
        while (node != null) {
            Node next = node.getNext();
            if (node instanceof Open o) {
                Set<String> classes = new LinkedHashSet<>();
                String c = o.attrs.get("class");
                if (c != null) for (String name : c.split("\\s+")) classes.add(name);
                Span span = new Span(classes);
                syntax.addAttributes(span, o.attrs);
                // "{.lead} The objective…": the space after a marker that starts
                // the element separates the marker from the text, it isn't text.
                if (o.getPrevious() == null && next instanceof Text t) {
                    t.setLiteral(t.getLiteral().stripLeading());
                }
                if (open.isEmpty()) o.insertBefore(span); else open.peek().appendChild(span);
                o.unlink();
                open.push(span);
            } else if (node instanceof Close c) {
                Span innermost = open.peek();
                if (innermost == null || !innermost.classes().contains(c.name)) {
                    boolean further = open.stream().anyMatch(s -> s.classes().contains(c.name));
                    throw new IllegalArgumentException(further
                            ? "{/" + c.name + "} closes {." + c.name + "} while a span inside it is still"
                                + " open. Spans nest: close the inner one first."
                            : "{/" + c.name + "} has no {." + c.name + "} to close before it in the same"
                                + " run of text. A span closes inside the paragraph, heading, link or"
                                + " emphasis it opened in.");
                }
                open.pop();
                c.unlink();
            } else if (!open.isEmpty()) {
                open.peek().appendChild(node);
            }
            node = next;
        }
        // Whatever is still open runs to the end of the element: nothing to do,
        // everything after it has already moved inside.
    }

    /** Renders each {@link Span} as a {@code <span>} with its group's attributes. */
    static HtmlNodeRendererFactory renderer() {
        return SpanRenderer::new;
    }

    private static final class SpanRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;

        SpanRenderer(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Span.class);
        }

        @Override
        public void render(Node node) {
            HtmlWriter html = context.getWriter();
            html.tag("span", context.extendAttributes(node, "span", Map.of()));
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
            html.tag("/span");
        }
    }
}
