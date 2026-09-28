package dev.noregressions.paperband.cards;

import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;
import org.commonmark.renderer.html.AttributeProvider;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paperband's attribute-list syntax — {@code {.class #id key=value}} — as a
 * commonmark-java post-processor plus the attribute provider that renders it.
 *
 * <p>CommonMark has no attribute syntax, so this is ours to define. It follows
 * the Pandoc spelling authors already write, with one deliberate difference
 * from the flexmark extension it replaces: <b>every token needs a sigil or an
 * {@code =}</b>. A bare word is not an attribute, so prose that happens to sit
 * in braces — {@code {CascadeType.SAVE, CascadeType.DELETE}}, an unrendered
 * {@code { vars.x }} — stays prose instead of vanishing into an attribute
 * nobody asked for.
 *
 * <h2>Where a group attaches</h2>
 * <ul>
 *   <li><b>End of a block</b> — {@code ## Heading {.x}}, {@code a paragraph {.x}},
 *       {@code - item {.x}}, a table cell: the block (for a list item's first
 *       paragraph, the {@code <li>}; tight lists render no {@code <p>} to carry it).</li>
 *   <li><b>Straight after an inline element</b>, no space —
 *       {@code [x](y){#id}}, {@code ![](i.png){.wide}}, {@code `code`{.x}},
 *       {@code **bold**{.x}}: that element.</li>
 *   <li><b>A line of its own</b> — the line after a closing fence
 *       ({@code {.fs--1}}), under a heading or a rule: the block before it. As
 *       the first line of a longer paragraph, that paragraph.</li>
 *   <li><b>A fence's info line</b> — {@code ```bash {.command}}: the
 *       {@code <pre>}; the language stays on the {@code <code>} for Prism.</li>
 * </ul>
 * A group anywhere else is left as text, visibly, rather than guessed at.
 *
 * <p>One instance serves one parse: the attributes are keyed by node identity
 * and read back by {@link #attributeProvider()} while that document renders.
 */
final class AttributeSyntax implements PostProcessor {

    /** A brace group with no nested braces. Tokens are checked separately. */
    private static final String GROUP = "\\{([^{}\\n]*)\\}";

    private static final Pattern TRAILING = Pattern.compile("(\\s*)" + GROUP + "\\s*$");
    private static final Pattern LEADING = Pattern.compile("^" + GROUP);
    private static final Pattern WHOLE = Pattern.compile("^\\s*" + GROUP + "\\s*$");
    private static final Pattern INFO = Pattern.compile("^(\\S*?)\\s*" + GROUP + "\\s*$");

    private static final String NAME = "[\\p{L}\\p{N}_][-\\p{L}\\p{N}_:.]*";
    /** Group numbers below follow this order; the mistake comes first so .key can't claim it. */
    private static final Pattern TOKEN = Pattern.compile(
            "([.#]" + NAME + ")=(\\S+)"                                // .key=value: a mistake
            + "|\\.([-\\p{L}\\p{N}_]+)"                                // .class
            + "|#([-\\p{L}\\p{N}_:.]+)"                                 // #id
            + "|(" + NAME + ")=(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"']+))"); // key=value

    private final Map<Node, Map<String, String>> attributes = new IdentityHashMap<>();

    @Override
    public Node process(Node document) {
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                afterInline(text);
            }

            @Override
            public void visit(FencedCodeBlock fence) {
                Matcher m = INFO.matcher(fence.getInfo() == null ? "" : fence.getInfo());
                if (m.find()) {
                    Map<String, String> parsed = parse(m.group(2));
                    if (parsed != null) {
                        add(fence, parsed);
                        fence.setInfo(m.group(1));
                    }
                }
            }
        });
        for (Node block : collect(document, Node.class)) {
            if (block instanceof Heading || block instanceof Paragraph || block instanceof TableCell) {
                trailing(block);
            }
        }
        // Own-line groups last, so a heading's own {.a} comes before the {.b}
        // on the line under it -- classes read in the order they were written.
        for (Paragraph p : collect(document, Paragraph.class)) ownLine(p);
        return document;
    }

    /** Renders what {@link #process} collected; attributes it doesn't know are left alone. */
    AttributeProvider attributeProvider() {
        return (node, tagName, attrs) -> {
            Map<String, String> mine = attributes.get(node);
            if (mine == null) return;
            // A fence renders <pre><code>; the group belongs on the <pre>, and
            // the <code> keeps language-x to itself.
            if (node instanceof FencedCodeBlock && !tagName.equals("pre")) return;
            for (Map.Entry<String, String> e : mine.entrySet()) {
                if (e.getKey().equals("class") && attrs.containsKey("class")) {
                    attrs.put("class", attrs.get("class") + " " + e.getValue());
                } else {
                    attrs.put(e.getKey(), e.getValue());
                }
            }
        };
    }

    // ----------- placement -----------

    /** {@code {.x}} as a paragraph's whole first line. */
    private void ownLine(Paragraph p) {
        if (!(p.getFirstChild() instanceof Text first)) return;
        Node next = first.getNext();
        if (next != null && !(next instanceof SoftLineBreak)) return;
        Matcher m = WHOLE.matcher(first.getLiteral());
        if (!m.find()) return;
        Map<String, String> parsed = parse(m.group(1));
        if (parsed == null) return;
        if (next == null) {
            // The paragraph is only the group: it describes the block above.
            Node previous = p.getPrevious();
            if (previous == null) return;       // nothing to attach to: stays visible
            add(previous, parsed);
            p.unlink();
        } else {
            next.unlink();
            first.unlink();
            add(p, parsed);
        }
    }

    /** {@code [x](y){#id}} — a group touching the inline element before it. */
    private void afterInline(Text text) {
        Node previous = text.getPrevious();
        if (!(previous instanceof Link || previous instanceof Image || previous instanceof Code
                || previous instanceof Emphasis || previous instanceof StrongEmphasis)) {
            return;
        }
        Matcher m = LEADING.matcher(text.getLiteral());
        if (!m.find()) return;
        Map<String, String> parsed = parse(m.group(1));
        if (parsed == null) return;
        add(previous, parsed);
        String rest = text.getLiteral().substring(m.end());
        if (rest.isEmpty()) text.unlink(); else text.setLiteral(rest);
    }

    /** {@code ## Heading {.x}} — a group closing a block's inline content. */
    private void trailing(Node block) {
        if (!(block.getLastChild() instanceof Text last)) return;
        Matcher m = TRAILING.matcher(last.getLiteral());
        if (!m.find()) return;
        Map<String, String> parsed = parse(m.group(2));
        if (parsed == null) return;
        String rest = last.getLiteral().substring(0, m.start()).stripTrailing();
        // A paragraph that is nothing but the group describes a neighbour, not
        // itself -- ownLine's case, not this one.
        if (rest.isEmpty() && block instanceof Paragraph && last.getPrevious() == null) return;
        if (rest.isEmpty()) last.unlink(); else last.setLiteral(rest);
        Node target = block;
        if (block instanceof Paragraph && block.getParent() instanceof ListItem item
                && item.getFirstChild() == block) {
            target = item;
        }
        add(target, parsed);
    }

    // ----------- the group itself -----------

    /**
     * The attributes in a group's inner text, in source order, or null when it
     * isn't an attribute group at all (a bare word, stray punctuation) and
     * should stay as text.
     *
     * @throws IllegalArgumentException for {@code .name=value}, which is
     *         almost certainly {@code name=value} or {@code .name name=value}
     */
    static Map<String, String> parse(String inner) {
        String s = inner.strip();
        if (s.isEmpty()) return null;
        List<String> classes = new ArrayList<>();
        Map<String, String> out = new LinkedHashMap<>();
        Matcher t = TOKEN.matcher(s);
        int pos = 0;
        while (pos < s.length()) {
            if (Character.isWhitespace(s.charAt(pos))) {
                pos++;
                continue;
            }
            if (!t.find(pos) || t.start() != pos) return null;
            int end = t.end();
            if (end < s.length() && !Character.isWhitespace(s.charAt(end))) return null;
            if (t.group(1) != null) {
                String bare = t.group(1).substring(1);
                throw new IllegalArgumentException("attribute '" + t.group(1)
                        + "' isn't a valid HTML name. Write {" + bare + "=" + t.group(2)
                        + "} for an attribute, or {." + bare + " " + bare + "=" + t.group(2)
                        + "} for a class as well.");
            } else if (t.group(3) != null) {
                classes.add(t.group(3));
            } else if (t.group(4) != null) {
                out.put("id", t.group(4));
            } else {
                String value = t.group(6) != null ? t.group(6)
                        : t.group(7) != null ? t.group(7) : t.group(8);
                if (t.group(5).equals("class")) classes.add(value);
                else out.put(t.group(5), value);
            }
            pos = end;
        }
        if (!classes.isEmpty()) {
            Map<String, String> withClass = new LinkedHashMap<>();
            withClass.put("class", String.join(" ", classes));
            withClass.putAll(out);
            return withClass;
        }
        return out;
    }

    /** Merge {@code parsed} into what {@code node} already has; classes accumulate. */
    private void add(Node node, Map<String, String> parsed) {
        Map<String, String> existing = attributes.computeIfAbsent(node, n -> new LinkedHashMap<>());
        for (Map.Entry<String, String> e : parsed.entrySet()) {
            if (e.getKey().equals("class") && existing.containsKey("class")) {
                existing.put("class", existing.get("class") + " " + e.getValue());
            } else {
                existing.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Every node of {@code type} in document order, snapshotted so callers can unlink. */
    private static <T extends Node> List<T> collect(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            collect(c, type, out);
        }
    }
}
