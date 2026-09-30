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
import java.util.StringJoiner;

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
 *
 * <p>{@link #of(String)} reads a fragment that has no such history, for a
 * block built by hand rather than loaded from a card.
 */
public final class ContentNodes {

    private record Origin(String lang, String code, boolean drawn) {
    }

    /** Elements that stand for a fence, keyed by identity: the DOM is mutable. */
    private final Map<Element, Origin> fences = new IdentityHashMap<>();

    ContentNodes() {
    }

    /**
     * The nodes of an HTML fragment read on its own: a {@code <pre>} is a
     * fence with the language its code element names, since no pass has
     * replaced one.
     *
     * @param html a block's content, or any fragment of it
     * @return its top-level nodes, in order
     */
    public static List<Node> of(String html) {
        ContentNodes reader = new ContentNodes();
        List<Node> out = new ArrayList<>();
        if (html == null) return out;
        for (org.jsoup.nodes.Node child : org.jsoup.Jsoup.parseBodyFragment(html).body().childNodes()) {
            if (child instanceof Element el) {
                out.add(reader.read(el));
            } else if (child instanceof TextNode tn && !tn.isBlank()) {
                out.add(reader.read(tn));
            }
        }
        return out;
    }

    /**
     * {@code root} stands for a {@code ```lang} fence with this text: the fence
     * itself, or what a pass drew it as.
     *
     * @param drawn true when a pass replaced the fence with its finished
     *              markup, so layout writes it as it is rather than through a
     *              block template
     */
    void fence(Element root, String lang, String code, boolean drawn) {
        fences.put(root, new Origin(lang, code, drawn));
    }

    /** A pass replaced {@code from} with {@code to}, its finished markup: {@code to} keeps any fence origin. */
    void moved(Element from, Element to) {
        Origin o = fences.remove(from);
        if (o != null) fences.put(to, new Origin(o.lang(), o.code(), true));
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
        List<String> order = new ArrayList<>();
        for (Attribute a : el.attributes()) {
            String key = a.getKey();
            order.add(key);
            if (key.equals("class") || key.equals("id")) continue;
            if (key.startsWith(AttributeSyntax.DIRECTIVE_ATTR_PREFIX)) {
                directives.put(key.substring(AttributeSyntax.DIRECTIVE_ATTR_PREFIX.length()), a.getValue());
            } else {
                attributes.put(key, a.getValue());
            }
        }
        Map<String, String> props = new LinkedHashMap<>();
        String type = type(el, tag, props);
        boolean opaque = OPAQUE_TAGS.contains(tag);
        List<Node> children = opaque ? List.of() : children(el);
        return new Node(type, tag, el.id().isEmpty() ? null : el.id(), el.classNames(),
                attributes, directives, opaque ? drawnText(el) : el.text(), el.outerHtml(), children, props, order);
    }

    /**
     * The words a drawing shows, one element's text at a time. jsoup's
     * {@code text()} runs an svg's {@code <text>} elements together, since it
     * doesn't know them as blocks: "3. Tree" and "structure" would read as
     * "Treestructure".
     */
    private static String drawnText(Element el) {
        StringJoiner out = new StringJoiner(" ");
        for (Element e : el.getAllElements()) {
            String own = e.ownText();
            if (!own.isBlank()) out.add(own);
        }
        return out.toString();
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
            if (origin != null && origin.drawn()) props.put("drawn", "true");
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
