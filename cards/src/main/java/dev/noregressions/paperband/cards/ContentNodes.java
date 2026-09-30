package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Node;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a card's finished HTML as {@link Node}s, the data a block carries
 * next to its HTML. One instance serves one card.
 *
 * <p>The HTML is read once every phase-4 pass has run, so the nodes match
 * what {@code block.html} prints. What those passes erase is the origin of a
 * fence: a {@code ```command} block template replaces the {@code <pre><code
 * class="language-command">} with its own markup, and a {@code ```diff-card}
 * becomes a {@code <figure>}. So each pass that replaces a fence reports it
 * here -- {@link #fence} for the element that now stands for it, {@link #moved}
 * when a later pass replaces that one in turn -- and the element reads as a
 * {@code fence} with the type and text the author wrote.
 */
final class ContentNodes {

    private record Origin(String lang, String code) {
    }

    /** Elements that stand for a fence, keyed by identity: the DOM is mutable. */
    private final Map<Element, Origin> fences = new IdentityHashMap<>();

    /** {@code root} is what a {@code ```lang} fence with this text became. */
    void fence(Element root, String lang, String code) {
        fences.put(root, new Origin(lang, code));
    }

    /** A pass replaced {@code from} with {@code to}: {@code to} keeps any fence origin. */
    void moved(Element from, Element to) {
        Origin o = fences.remove(from);
        if (o != null) fences.put(to, o);
    }

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "ul", "ol", "li", "pre", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "blockquote", "figure", "figcaption", "div", "section", "hr",
            "h1", "h2", "h3", "h4", "h5", "h6", "dl", "dt", "dd", "details", "summary");

    /** Drawing, not text: read as one element, without its insides. */
    private static final Set<String> OPAQUE_TAGS = Set.of("svg", "math");

    /** One element, and everything under it. */
    Node read(Element el) {
        String tag = el.normalName();
        Map<String, String> attributes = new LinkedHashMap<>();
        Map<String, String> directives = new LinkedHashMap<>();
        for (Attribute a : el.attributes()) {
            String key = a.getKey();
            if (key.equals("class") || key.equals("id")) continue;
            if (key.startsWith(AttributeSyntax.DIRECTIVE_ATTR_PREFIX)) {
                directives.put(key.substring(AttributeSyntax.DIRECTIVE_ATTR_PREFIX.length()), a.getValue());
            } else {
                attributes.put(key, a.getValue());
            }
        }
        Map<String, String> props = new LinkedHashMap<>();
        String type = type(el, tag, props);
        List<Node> children = OPAQUE_TAGS.contains(tag) ? List.of() : children(el);
        return new Node(type, tag, el.id().isEmpty() ? null : el.id(), el.classNames(),
                attributes, directives, el.text(), el.outerHtml(), children, props);
    }

    /** A top-level run of text, as a block's own content holds it. */
    Node read(TextNode tn) {
        return Node.text(tn.text(), tn.outerHtml());
    }

    private String type(Element el, String tag, Map<String, String> props) {
        Origin origin = fences.get(el);
        if (origin != null || tag.equals("pre")) {
            String lang = origin != null ? origin.lang() : language(el);
            if (lang != null) props.put("lang", lang);
            props.put("code", origin != null ? origin.code() : codeText(el));
            return "fence";
        }
        return switch (tag) {
            case "p" -> "paragraph";
            case "ul", "ol" -> {
                props.put("ordered", String.valueOf(tag.equals("ol")));
                yield "list";
            }
            case "li" -> "item";
            case "table" -> "table";
            case "tr" -> "row";
            case "td", "th" -> {
                props.put("header", String.valueOf(tag.equals("th")));
                yield "cell";
            }
            case "blockquote" -> "quote";
            case "figure" -> "figure";
            case "hr" -> "rule";
            case "code" -> "code";
            case "em", "i" -> "emphasis";
            case "strong", "b" -> "strong";
            case "a" -> "link";
            case "img" -> "image";
            case "span" -> "span";
            case "br" -> "break";
            default -> "element";
        };
    }

    /** The {@code language-x} of a {@code <pre>}'s code, or null. */
    private static String language(Element pre) {
        Element code = pre.selectFirst("> code");
        if (code == null) return null;
        for (String c : code.classNames()) {
            if (c.startsWith("language-") && c.length() > "language-".length()) {
                return c.substring("language-".length());
            }
        }
        return null;
    }

    private static String codeText(Element pre) {
        Element code = pre.selectFirst("> code");
        return (code != null ? code : pre).wholeText();
    }

    private List<Node> children(Element el) {
        boolean hasBlockChild = false;
        for (Element c : el.children()) {
            if (BLOCK_TAGS.contains(c.normalName())) {
                hasBlockChild = true;
                break;
            }
        }
        boolean preformatted = el.normalName().equals("pre") || el.closest("pre") != null;
        List<Node> out = new ArrayList<>();
        for (org.jsoup.nodes.Node child : el.childNodes()) {
            if (child instanceof Element c) {
                out.add(read(c));
            } else if (child instanceof TextNode tn) {
                // Between blocks, whitespace is the source's line breaks; between
                // words, it's a space the reader sees.
                if (tn.isBlank() && (hasBlockChild || tn.getWholeText().isEmpty())) continue;
                out.add(preformatted ? Node.text(tn.getWholeText(), tn.outerHtml()) : read(tn));
            }
        }
        return out;
    }
}
