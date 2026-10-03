package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentSanitizer;
import dev.noregressions.paperband.model.Node;
import dev.noregressions.paperband.pebble.LenientMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An empty block a transform adds, written the way a card writes attributes:
 * {@code '{.answer #q1 lines=4}'}. The braces are optional.
 *
 * <p>It says what a markdown attribute list can say -- an id, classes,
 * attributes -- and nothing else: no tag, no content, and no attribute the
 * content policy would strip from a card ({@code style}, {@code on*} and the
 * rest), nor one that carries a URL. So what a transform adds is as safe as
 * what an author could have written.
 *
 * @param id         the id, or null
 * @param classes    the classes, in order
 * @param attributes every other attribute, in order
 */
record BlockSpec(String id, Set<String> classes, Map<String, String> attributes) {

    private static final Pattern NAME = Pattern.compile("-?[A-Za-z_][A-Za-z0-9_-]*");

    /** Attributes that hold a URL: a link or a source a template didn't choose. */
    private static final Set<String> URL_ATTRS = Set.of(
            "href", "src", "srcset", "action", "formaction", "poster", "data", "cite", "ping", "background");

    /**
     * {@code text} read as a spec.
     *
     * @throws IllegalArgumentException naming what's wrong with it
     */
    static BlockSpec parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a block is written like a card's attributes, as in"
                    + " '{.answer lines=4}', not nothing");
        }
        String s = text.strip();
        if (s.startsWith("{")) {
            if (!s.endsWith("}")) throw new IllegalArgumentException("'" + text + "' opens a { it doesn't close");
            s = s.substring(1, s.length() - 1);
        }
        String id = null;
        Set<String> classes = new LinkedHashSet<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (c == '.' || c == '#') {
                i++;
                while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
                String name = s.substring(start + 1, i);
                checkName(text, (c == '.' ? "class" : "id"), name);
                if (c == '.') classes.add(name);
                else id = name;
                continue;
            }
            while (i < s.length() && s.charAt(i) != '=' && !Character.isWhitespace(s.charAt(i))) i++;
            String key = s.substring(start, i);
            if (i >= s.length() || s.charAt(i) != '=') {
                throw new IllegalArgumentException("'" + text + "': '" + key + "' needs a value, as in "
                        + key + "=4, or a . for a class");
            }
            checkName(text, "attribute", key);
            checkAllowed(text, key);
            i++;
            String value;
            if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                char quote = s.charAt(i);
                int close = s.indexOf(quote, i + 1);
                if (close < 0) throw new IllegalArgumentException("'" + text + "': " + key + "'s value isn't closed");
                value = s.substring(i + 1, close);
                i = close + 1;
            } else {
                int from = i;
                while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
                value = s.substring(from, i);
            }
            attributes.put(key, value);
        }
        return new BlockSpec(id, classes, attributes);
    }

    private static void checkName(String text, String what, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + text + "': '" + name + "' isn't a " + what
                    + " name; use letters, digits, - and _");
        }
    }

    private static void checkAllowed(String text, String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("id") || lower.equals("class")) {
            throw new IllegalArgumentException("'" + text + "': write " + key + " as "
                    + (lower.equals("id") ? "#name" : ".name"));
        }
        if (!ContentSanitizer.keepsAttribute(key) || URL_ATTRS.contains(lower)) {
            throw new IllegalArgumentException("'" + text + "': a transform can't add " + key
                    + "; a card's content can't carry it either. Add a class and style that in the theme");
        }
    }

    /** True when it names attributes only: what {@code set} takes. */
    boolean attributesOnly() {
        return id == null && classes.isEmpty();
    }

    /** An empty {@code <div>} node, with {@code fallbackId} when the spec has no id. */
    Node node(String fallbackId, List<Node> children) {
        String nodeId = id != null ? id : fallbackId;
        Node draft = new Node("element", "div", nodeId, classes, attributes, Map.of(), "", "", children, Map.of());
        String html = NodeHtml.write(List.of(draft));
        String text = children.isEmpty() ? "" : org.jsoup.Jsoup.parseBodyFragment(html).body().text();
        return new Node("element", "div", nodeId, classes, attributes, Map.of(), text, html, children, Map.of());
    }

    /**
     * An empty block model, keyed as {@code LayoutEngine.blockModel} keys one:
     * no heading, no content, at {@code level}, holding {@code children}.
     * {@code fallbackId} and {@code fallbackAnchor} keep what it replaces
     * linkable when the spec names no id.
     */
    Map<String, Object> block(int level, String fallbackId, String fallbackAnchor,
                              List<Map<String, Object>> children) {
        Map<String, Object> bm = new HashMap<>();
        bm.put("kind", "FENCED_DIV");
        bm.put("id", id != null ? id : fallbackId);
        bm.put("anchor", id != null ? id : fallbackAnchor);
        bm.put("classes", new ArrayList<>(classes));
        bm.put("classAttr", String.join(" ", classes));
        bm.put("attributes", LenientMap.of(attributes));
        bm.put("directives", LenientMap.of(Map.of()));
        bm.put("directiveAttrs", "");
        bm.put("heading", null);
        bm.put("level", level);
        bm.put("html", "");
        bm.put("nodes", new ArrayList<>());
        bm.put("children", children);
        return bm;
    }
}
