package dev.noregressions.paperband;

import org.commonmark.node.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds {@code {! ...}} markers in a parsed file, removes them from the content, and records the
 * {@link Attributes} they give each element. A marker may go:
 *
 * <pre>
 * ### A heading {! step}                       end of a heading (after a space)
 * A paragraph. {! instruction}                 end of a paragraph (after a space); in a list item's
 *                                              first paragraph it tags the list item
 * {! checklist}                                a line of its own: tags the block directly below
 * - item                                       (including a paragraph that starts on the next line)
 * ```bash {! command}                          after a code fence's language
 * Click [Save]{! ui}                           straight after [text]: tags that text as a span
 * Run `make`{! cmd} or [docs](u){! external}   straight after a code span, link, image or emphasis
 * ::: {! instruction} ... :::                  a fenced div (see {@link Div})
 * </pre>
 *
 * A marker anywhere else is an error, so typos don't silently become text. {@code \{!} keeps a
 * literal {@code {!}; {@code \`} puts a backtick in a value. Explicit ids must be unique within the
 * file. An element an {@code index} marker tags ({@link IndexTerms}) gets an id even without one.
 */
final class Markers {

    /** Stands in for an escaped {@code \{} while parsing; private-use, so never in real text. */
    private static final char ESCAPED_BRACE = '';

    private Markers() {
    }

    /** Hides {@code \{!} from marker processing. Apply to the source before parsing. */
    static String protectEscapes(String source) {
        return source.replace("\\{!", ESCAPED_BRACE + "!");
    }

    /**
     * Applies every marker in {@code doc} and restores escaped braces.
     *
     * @return the handles of each tagged node
     * @throws Attributes.MarkerException with the line, for a misplaced or malformed marker
     */
    static Map<Node, Attributes> apply(Node doc) {
        return apply(doc, null);
    }

    /**
     * As {@link #apply(Node)}, adding index terms found by rule: {@code autoIndex} gives the terms
     * for elements of the file once its markers are out (so a marker's own text isn't searched),
     * and they join any {@code index} marker the element has.
     */
    static Map<Node, Attributes> apply(Node doc, java.util.function.Function<Node, Map<Node, String>> autoIndex) {
        Map<Node, Attributes> out = new IdentityHashMap<>();
        List<Node> nodes = new ArrayList<>();
        collect(doc, nodes);

        for (Node n : nodes) {
            try {
                switch (n) {
                    case Div d -> {
                        if (!d.isClosed()) {
                            throw new Attributes.MarkerException("::: opened here is never closed", d.line());
                        }
                        put(out, d, d.getAttributes());
                        if (Variants.isGroup(d)) {
                            Variants.check(d);
                        } else if (d.getAttributes().values().containsKey(Variants.VARIANT_KEY) && !Variants.isGroup(d.getParent())) {
                            throw new Attributes.MarkerException("a ::: {! variant=...} group belongs inside a"
                                    + " :::: {! variants} group", d.line());
                        }
                    }
                    case FencedCodeBlock f -> fenceInfo(f, out);
                    case org.commonmark.ext.gfm.tables.TableCell c -> inlines(c, out); // [text]{! tag} in a cell
                    case Paragraph p -> {
                        if (Site.text(p).matches(":{3,}") && p.getFirstChild() == p.getLastChild()) {
                            throw new Attributes.MarkerException("closing ::: with no open ::: above it");
                        }
                        leading(p, out);
                        inlines(p, out);
                        trailing(p, out);
                    }
                    case Heading h -> {
                        inlines(h, out);
                        trailing(h, out);
                    }
                    default -> {
                    }
                }
            } catch (Attributes.MarkerException e) {
                throw e.at(NodeInfo.line(n));
            }
        }

        List<Node> after = new ArrayList<>();
        collect(doc, after);
        Map<String, Node> ids = new HashMap<>();
        for (Node n : after) {
            if (n instanceof Text t && t.getLiteral().contains("{!")) {
                int open = t.getLiteral().lastIndexOf("{!");
                if (Attributes.end(t.getLiteral(), open) < 0 && t.getNext() instanceof Code) {
                    throw new Attributes.MarkerException("a backtick inside a marker starts inline code, which cuts the"
                            + " marker short (\"" + t.getLiteral().substring(open).strip() + "\"); write \\` for a"
                            + " backtick in a marker's value, e.g. {! index=\"\\`jdeps\\`\"}", line(n));
                }
                throw new Attributes.MarkerException("a {! } marker can't go here (\"" + t.getLiteral().strip()
                        + "\"): put it at the end of a heading or paragraph, on its own line above a block, after a"
                        + " code fence's language, or straight after [text] or an inline element; write \\{! for"
                        + " literal text", line(n));
            }
            restore(n);
            Attributes a = out.get(n);
            if (a != null && a.tags().contains("optional") && a.values().containsKey("priority")
                    && !a.values().get("priority").matches("[1-9][0-9]*")) {
                throw new Attributes.MarkerException("{! optional} priority must be a whole number from 1, got \""
                        + a.values().get("priority") + "\"", line(n));
            }
            if (a != null) {
                Breaks.check(n, a, out, line(n));
            }
            if (a != null && a.id() != null) {
                Node other = ids.putIfAbsent(a.id(), n);
                if (other != null) {
                    throw new Attributes.MarkerException("id \"" + a.id() + "\" is used twice (also on line "
                            + line(other) + ")", line(n));
                }
            }
        }
        out.replaceAll((n, a) -> restore(a));
        if (autoIndex != null) {
            autoIndex.apply(doc).forEach((n, terms) -> out.merge(n,
                    new Attributes(null, List.of(), Map.of(IndexTerms.KEY, terms)), Markers::joinIndex));
        }
        indexIds(after, out, ids.keySet());
        return out;
    }

    /** Handles plus found index terms: the terms join the element's own index value, if it has one. */
    private static Attributes joinIndex(Attributes own, Attributes found) {
        String mine = own.values().get(IndexTerms.KEY);
        Map<String, String> values = new java.util.LinkedHashMap<>(own.values());
        values.put(IndexTerms.KEY, mine == null ? found.values().get(IndexTerms.KEY)
                : mine + "; " + found.values().get(IndexTerms.KEY));
        return new Attributes(own.id(), own.tags(), values);
    }

    /** The id given to the {@code n}th index marker in a file that has no id of its own: {@code index-1}, ... */
    static final String INDEX_ID = "index-";

    /**
     * Gives each element an index marker tags ({@link IndexTerms}) an id, in document order,
     * so the index can link to it: its own id if it has one, else the next free {@code index-N}. A
     * top-level heading needs none: it's linked by its section id, which links elsewhere may use.
     * The terms are checked here, so a malformed one is reported with its line.
     */
    private static void indexIds(List<Node> inOrder, Map<Node, Attributes> out, java.util.Set<String> taken) {
        int next = 1;
        for (Node n : inOrder) {
            Attributes a = out.get(n);
            if (a == null || !IndexTerms.marks(a)) {
                continue;
            }
            try {
                IndexTerms.of(a, n);
            } catch (Attributes.MarkerException e) {
                throw e.at(line(n));
            }
            if (a.id() == null && !(n instanceof Heading && n.getParent() instanceof Document)) {
                String id; // a top-level heading keeps its section id (MarkdownFile#headingId)
                do {
                    id = INDEX_ID + next++;
                } while (taken.contains(id));
                out.put(n, new Attributes(id, a.tags(), a.values()));
            }
        }
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            collect(c, into);
        }
    }

    private static void put(Map<Node, Attributes> out, Node node, Attributes a) {
        out.merge(node, a, Attributes::merge);
    }

    private static void fenceInfo(FencedCodeBlock f, Map<Node, Attributes> out) {
        String info = f.getInfo();
        int start = info == null ? -1 : info.indexOf("{!");
        if (start < 0) {
            return;
        }
        int end = Attributes.end(info, start);
        if (end < 0) {
            throw new Attributes.MarkerException("unclosed marker in code fence \"" + info + "\"");
        }
        if (!info.substring(end).isBlank()) {
            throw new Attributes.MarkerException("the marker must end the code fence line: \"" + info + "\"");
        }
        put(out, f, Attributes.parseMarker(info.substring(start, end)));
        f.setInfo(info.substring(0, start).strip());
    }

    /** Markers straight after {@code ]} or an inline element, within {@code parent} and inline containers in it. */
    private static void inlines(Node parent, Map<Node, Attributes> out) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
            if (!(c instanceof Text) && c.getFirstChild() != null) {
                inlines(c, out);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            scan:
            for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
                if (!(c instanceof Text t)) {
                    continue;
                }
                String lit = t.getLiteral();
                for (int i = lit.indexOf("{!"); i >= 0; i = lit.indexOf("{!", i + 2)) {
                    boolean afterBracket = i > 0 && lit.charAt(i - 1) == ']';
                    Node prev = t.getPrevious();
                    boolean afterInline = i == 0 && prev != null && !(prev instanceof Text)
                            && !(prev instanceof SoftLineBreak) && !(prev instanceof HardLineBreak);
                    if (!afterBracket && !afterInline) {
                        continue;
                    }
                    int end = Attributes.end(lit, i);
                    if (end < 0) {
                        throw new Attributes.MarkerException("unclosed marker \"" + lit.substring(i) + "\"");
                    }
                    Attributes a = Attributes.parseMarker(lit.substring(i, end));
                    if (afterInline) {
                        put(out, prev, a);
                        setOrRemove(t, lit.substring(end));
                    } else {
                        put(out, span(t, i - 1, end), a);
                    }
                    changed = true;
                    break scan;
                }
            }
        }
    }

    /** Wraps {@code [...]} ending at {@code close} in {@code closeNode} in a {@link Span}; the marker ends at {@code markerEnd}. */
    private static Span span(Text closeNode, int close, int markerEnd) {
        Text openNode = null;
        int open = -1;
        int depth = 0;
        for (Node n = closeNode; n != null && openNode == null; n = n.getPrevious()) {
            if (!(n instanceof Text tx)) {
                continue;
            }
            String s = tx.getLiteral();
            for (int k = n == closeNode ? close - 1 : s.length() - 1; k >= 0; k--) {
                char ch = s.charAt(k);
                if (ch == ']') {
                    depth++;
                } else if (ch == '[') {
                    if (depth == 0) {
                        openNode = tx;
                        open = k;
                        break;
                    }
                    depth--;
                }
            }
        }
        if (openNode == null) {
            throw new Attributes.MarkerException("\"]{!\" has no matching \"[\"");
        }

        Span span = new Span();
        String closeLit = closeNode.getLiteral();
        String after = closeLit.substring(markerEnd);
        if (openNode == closeNode) {
            String inner = closeLit.substring(open + 1, close);
            closeNode.insertAfter(span);
            closeNode.setLiteral(closeLit.substring(0, open));
            if (!inner.isEmpty()) {
                span.appendChild(new Text(inner));
            }
        } else {
            String openLit = openNode.getLiteral();
            openNode.insertAfter(span);
            openNode.setLiteral(openLit.substring(0, open));
            if (open + 1 < openLit.length()) {
                span.appendChild(new Text(openLit.substring(open + 1)));
            }
            for (Node m = span.getNext(); m != closeNode; ) {
                Node next = m.getNext();
                span.appendChild(m);
                m = next;
            }
            if (close > 0) {
                span.appendChild(new Text(closeLit.substring(0, close)));
            }
            closeNode.unlink();
        }
        if (!after.isEmpty()) {
            span.insertAfter(new Text(after));
        }
        if (openNode.getLiteral().isEmpty()) {
            openNode.unlink();
        }
        return span;
    }

    /**
     * A marker line directly above a paragraph's text: Markdown makes it the paragraph's first
     * line (plain text doesn't start a new block), so it tags that paragraph.
     */
    private static void leading(Paragraph p, Map<Node, Attributes> out) {
        while (p.getFirstChild() instanceof Text t && t.getNext() instanceof SoftLineBreak) {
            String marker = t.getLiteral().strip();
            if (!marker.startsWith("{!") || Attributes.end(marker, 0) != marker.length()) {
                return;
            }
            put(out, target(p), Attributes.parseMarker(marker));
            t.getNext().unlink();
            t.unlink();
        }
    }

    /** What a paragraph's marker tags: the list item, for an item's first paragraph; else the paragraph. */
    private static Node target(Block block) {
        return block instanceof Paragraph && block.getParent() instanceof ListItem li && li.getFirstChild() == block
                ? li : block;
    }

    /** Markers at the end of a heading or paragraph, or a paragraph that's only markers. */
    private static void trailing(Block block, Map<Node, Attributes> out) {
        while (block.getLastChild() instanceof Text t) {
            String lit = t.getLiteral();
            String trimmed = lit.stripTrailing();
            int start = markerEnding(trimmed);
            if (start < 0) {
                break;
            }
            boolean standaloneLine = start == 0 && (t.getPrevious() == null
                    || t.getPrevious() instanceof SoftLineBreak || t.getPrevious() instanceof HardLineBreak);
            if (start > 0 && !Character.isWhitespace(trimmed.charAt(start - 1))) {
                break; // glued to a word: not a block marker (and reported as misplaced later)
            }
            if (start == 0 && !standaloneLine) {
                break;
            }
            Attributes a = Attributes.parseMarker(trimmed.substring(start));
            setOrRemove(t, trimmed.substring(0, start).stripTrailing());
            while (block.getLastChild() instanceof SoftLineBreak || block.getLastChild() instanceof HardLineBreak) {
                block.getLastChild().unlink();
            }
            if (block.getFirstChild() == null) {
                if (block instanceof Heading) {
                    throw new Attributes.MarkerException("a heading needs text as well as a marker");
                }
                tagNextBlock(block, a, out);
                return;
            }
            put(out, target(block), a);
        }
    }

    /** Start of a whole marker that ends {@code text}, or -1. */
    private static int markerEnding(String text) {
        if (!text.endsWith("}")) {
            return -1;
        }
        for (int i = text.lastIndexOf("{!"); i >= 0; i = i == 0 ? -1 : text.lastIndexOf("{!", i - 1)) {
            if (Attributes.end(text, i) == text.length()) {
                return i;
            }
        }
        return -1;
    }

    /** A marker on a line of its own tags the block directly below it; the marker's paragraph is removed. */
    private static void tagNextBlock(Block markerParagraph, Attributes a, Map<Node, Attributes> out) {
        Node next = markerParagraph.getNext();
        if (next == null) {
            throw new Attributes.MarkerException("a marker on its own line must be directly above the block it tags,"
                    + " but nothing follows it");
        }
        int markerLine = lastLine(markerParagraph);
        int nextLine = NodeInfo.line(next);
        if (markerLine > 0 && nextLine > 0 && nextLine != markerLine + 1) {
            throw new Attributes.MarkerException("a marker on its own line must be directly above the block it tags"
                    + " (no blank line between)");
        }
        put(out, next, a);
        markerParagraph.unlink();
    }

    private static int lastLine(Node n) {
        List<SourceSpan> spans = n.getSourceSpans();
        return spans.isEmpty() ? -1 : spans.getLast().getLineIndex() + 1;
    }

    private static void setOrRemove(Text t, String literal) {
        if (literal.isEmpty()) {
            t.unlink();
        } else {
            t.setLiteral(literal);
        }
    }

    /** Puts escaped braces back: as plain {@code {} in text, as written ({@code \{}) in code and raw HTML. */
    private static void restore(Node n) {
        String raw = "\\{";
        switch (n) {
            case Text t -> t.setLiteral(t.getLiteral().replace(String.valueOf(ESCAPED_BRACE), "{"));
            case Code c -> c.setLiteral(c.getLiteral().replace(String.valueOf(ESCAPED_BRACE), raw));
            case FencedCodeBlock f -> {
                f.setLiteral(f.getLiteral().replace(String.valueOf(ESCAPED_BRACE), raw));
                if (f.getInfo() != null) {
                    f.setInfo(f.getInfo().replace(String.valueOf(ESCAPED_BRACE), raw));
                }
            }
            case IndentedCodeBlock i -> i.setLiteral(i.getLiteral().replace(String.valueOf(ESCAPED_BRACE), raw));
            case HtmlBlock h -> h.setLiteral(h.getLiteral().replace(String.valueOf(ESCAPED_BRACE), raw));
            case HtmlInline h -> h.setLiteral(h.getLiteral().replace(String.valueOf(ESCAPED_BRACE), raw));
            default -> {
            }
        }
    }

    private static Attributes restore(Attributes a) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        a.values().forEach((k, v) -> values.put(k, v.replace(String.valueOf(ESCAPED_BRACE), "{")));
        return new Attributes(a.id(), a.tags(), values);
    }

    /** Line of a node, or of its nearest block ancestor (inline nodes have no position of their own). */
    private static int line(Node n) {
        for (Node p = n; p != null; p = p.getParent()) {
            int line = NodeInfo.line(p);
            if (line > 0) {
                return line;
            }
        }
        return -1;
    }
}
