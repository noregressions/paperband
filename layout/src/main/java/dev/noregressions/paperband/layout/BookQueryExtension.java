package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.pebble.LenientMap;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code | query('css')}: the parts of several cards that match a CSS
 * selector, each with the card and block it came from -- {@code find} across
 * cards, for a template that sees the whole book.
 *
 * <pre>
 * {% for e in cards | query('block[data-paperband-step] pre.command') %}
 *   &lt;a href="card:{{ e.card.id }}"&gt;{{ e.block.heading }}&lt;/a&gt; {{ e.node.code }}
 * {% endfor %}
 * </pre>
 *
 * <p>{@code find} sees one block's nodes, and a node it returns doesn't know
 * where it was. A page built from the whole book -- every command, every
 * Watch Out, every check -- has to cross cards and has to say where each piece
 * came from, to group it or link back to it. So the selector sees two more
 * levels above the nodes:
 * <ul>
 *   <li>{@code card}: its id, a {@code <axis>-<value>} class per axis value
 *       (the classes its article carries), and its frontmatter's scalar keys
 *       as attributes, a list's items joined by spaces:
 *       {@code card#setup}, {@code card.level-beginner},
 *       {@code card[index~=maven]}</li>
 *   <li>{@code block}: a block's id, classes, attributes and directives, the
 *       same as its {@code <section>}, with its nested blocks inside it:
 *       {@code block.watch-out}, {@code block[data-paperband-step]}</li>
 * </ul>
 * Under each block are its nodes, as {@code find} sees them.
 *
 * <p>It takes {@code cards} (or any list of cards), one card, or one block,
 * and returns an entry per match in document order: {@code kind} ({@code card},
 * {@code block} or {@code node}); {@code card} and {@code block}, the card and
 * innermost block it sits in, or the match itself at that level; {@code step},
 * the innermost {@code {!step}} block around it, or null; and {@code node},
 * the node for a node match. Each is the model a template already has, so
 * {@code e.block.heading} and {@code e.node | find(...)} work as usual. Like
 * {@code find}, it keeps a match inside another. No match is an empty list.
 */
final class BookQueryExtension extends AbstractExtension {

    /** A frontmatter key that can stand as an attribute name. Anything else isn't selectable. */
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    @Override
    public Map<String, Filter> getFilters() {
        return Map.of("query", new QueryFilter());
    }

    /** What a stand-in element was made from. */
    private record Origin(String kind, Map<?, ?> model) {
    }

    private static final class QueryFilter implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            Object selector = args.get("selector");
            if (selector == null || selector.toString().isBlank()) {
                throw new PebbleException(null, "query needs a CSS selector, as in"
                        + " cards | query('block.watch-out')", lineNumber, self.getName());
            }
            Map<Element, Origin> origins = new IdentityHashMap<>();
            Element top = new Element("paperband-book");
            if (!addInput(top, input, origins)) {
                throw new PebbleException(null, "query works on cards, a card or a block, not "
                        + input.getClass().getSimpleName(), lineNumber, self.getName());
            }
            List<Object> out = new ArrayList<>();
            try {
                for (Element match : top.select(selector.toString())) {
                    Map<String, Object> entry = entry(match, origins);
                    if (entry != null) out.add(entry);
                }
            } catch (Selector.SelectorParseException e) {
                throw new PebbleException(e, "query('" + selector + "') isn't a CSS selector jsoup"
                        + " can read: " + e.getMessage(), lineNumber, self.getName());
            }
            return out;
        }

        /** Stand-ins for {@code input} under {@code top}. False when it's none of the things query takes. */
        private static boolean addInput(Element top, Object input, Map<Element, Origin> origins) {
            if (input == null) return true;
            if (input instanceof List<?> list) {
                for (Object item : list) {
                    if (!addInput(top, item, origins)) return false;
                }
                return true;
            }
            if (!(input instanceof Map<?, ?> m)) return false;
            if (m.get("blocks") instanceof List<?>) {
                addCard(top, m, origins);
                return true;
            }
            if (m.get("nodes") instanceof List<?> && m.containsKey("heading")) {
                addBlock(top, m, origins);
                return true;
            }
            return false;
        }

        private static void addCard(Element parent, Map<?, ?> card, Map<Element, Origin> origins) {
            Element el = new Element("card");
            if (card.get("id") != null) el.id(card.get("id").toString());
            if (card.get("axes") instanceof Map<?, ?> axes) {
                axes.forEach((axis, value) -> {
                    if (value instanceof Map<?, ?> v && v.get("id") != null) el.addClass(axis + "-" + v.get("id"));
                });
            }
            if (card.get("frontmatter") instanceof Map<?, ?> fm) {
                fm.forEach((k, v) -> {
                    String value = attributeValue(v);
                    if (value != null && ATTRIBUTE_NAME.matcher(k.toString()).matches()) el.attr(k.toString(), value);
                });
            }
            parent.appendChild(el);
            origins.put(el, new Origin("card", card));
            for (Object b : (List<?>) card.get("blocks")) {
                if (b instanceof Map<?, ?> block) addBlock(el, block, origins);
            }
        }

        private static void addBlock(Element parent, Map<?, ?> block, Map<Element, Origin> origins) {
            Element el = new Element("block");
            if (block.get("id") != null) el.id(block.get("id").toString());
            if (block.get("classes") instanceof List<?> classes) {
                for (Object c : classes) el.addClass(c.toString());
            }
            if (block.get("attributes") instanceof Map<?, ?> attrs) {
                attrs.forEach((k, v) -> el.attr(k.toString(), v == null ? "" : v.toString()));
            }
            if (block.get("directives") instanceof Map<?, ?> dirs) {
                dirs.forEach((k, v) -> el.attr(NodeHtml.DIRECTIVE_PREFIX + k, v == null ? "" : v.toString()));
            }
            parent.appendChild(el);
            origins.put(el, new Origin("block", block));
            Map<Element, Map<?, ?>> nodes = new IdentityHashMap<>();
            for (Object n : (List<?>) block.get("nodes")) NodeFindExtension.standIn(el, n, nodes);
            nodes.forEach((e, n) -> origins.put(e, new Origin("node", n)));
            if (block.get("children") instanceof List<?> children) {
                for (Object c : children) {
                    if (c instanceof Map<?, ?> child) addBlock(el, child, origins);
                }
            }
        }

        /** A scalar as attribute text, a list as its items joined by spaces for {@code ~=}; null otherwise. */
        private static String attributeValue(Object v) {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) return v.toString();
            if (v instanceof List<?> list && list.stream().allMatch(i -> i instanceof String || i instanceof Number)) {
                return list.stream().map(Object::toString).collect(Collectors.joining(" "));
            }
            return null;
        }

        /** The entry for {@code match}: it, and the card, block and step it sits in. */
        private static Map<String, Object> entry(Element match, Map<Element, Origin> origins) {
            Origin own = origins.get(match);
            if (own == null) return null;
            LenientMap<String, Object> e = new LenientMap<>();
            e.put("kind", own.kind());
            e.put("node", own.kind().equals("node") ? own.model() : null);
            for (Element at = match; at != null; at = at.parent()) {
                Origin o = origins.get(at);
                if (o == null) continue;
                if (o.kind().equals("block")) {
                    e.putIfAbsent("block", o.model());
                    if (o.model().get("directives") instanceof Map<?, ?> d && d.get("step") != null) {
                        e.putIfAbsent("step", o.model());
                    }
                } else if (o.kind().equals("card")) {
                    e.putIfAbsent("card", o.model());
                }
            }
            return e;
        }
    }
}
