package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code | find('css')}: the nodes of a block's content that match a CSS
 * selector, as data -- the {@code block.nodes} counterpart of
 * {@code | select}, which returns HTML.
 *
 * <pre>
 * {% set command = block.nodes | find('pre.command, pre.console') | first %}
 * {% if command is not null %}&lt;kbd&gt;{{ command.code }}&lt;/kbd&gt;{% endif %}
 * </pre>
 *
 * <p>It takes a block ({@code block | find(...)}), a list of nodes
 * ({@code block.nodes}, {@code node.children}) or one node, and searches them
 * and everything under them. The selector sees each node's tag, id, classes
 * and attributes, and its directives as {@code data-paperband-<name>}, the
 * same markup {@code select} sees. A fence written as code also carries its
 * type as a class: {@code pre.command} is a {@code ```command} block here as
 * it is in the HTML its block template writes. It returns the matching node maps in
 * document order, and unlike {@code select} it keeps a match inside another:
 * data isn't printed twice, and a template asking for every list item wants
 * the nested ones too. No match is an empty list.
 */
final class NodeFindExtension extends AbstractExtension {

    @Override
    public Map<String, Filter> getFilters() {
        return Map.of("find", new FindFilter());
    }

    private static final class FindFilter implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            Object selector = args.get("selector");
            if (selector == null || selector.toString().isBlank()) {
                throw new PebbleException(null, "find needs a CSS selector, as in"
                        + " block.nodes | find('.instructions')", lineNumber, self.getName());
            }
            List<?> roots = roots(input);
            if (roots == null) {
                throw new PebbleException(null, "find works on a block, block.nodes or a node, not "
                        + (input == null ? "null" : input.getClass().getSimpleName()),
                        lineNumber, self.getName());
            }
            // A stand-in element per node, so jsoup's selector engine does the
            // matching and each match maps straight back to its node.
            Map<Element, Map<?, ?>> byElement = new IdentityHashMap<>();
            Element top = new Element("paperband-nodes");
            for (Object n : roots) add(top, n, byElement);
            List<Object> out = new ArrayList<>();
            try {
                for (Element match : top.select(selector.toString())) {
                    Map<?, ?> node = byElement.get(match);
                    if (node != null) out.add(node);
                }
            } catch (Selector.SelectorParseException e) {
                throw new PebbleException(e, "find('" + selector + "') isn't a CSS selector jsoup"
                        + " can read: " + e.getMessage(), lineNumber, self.getName());
            }
            return out;
        }

        /** What to search: a block's nodes, a list of nodes, or one node. Null when it's none of those. */
        private static List<?> roots(Object input) {
            if (input == null) return List.of();
            if (input instanceof List<?> list) return list;
            if (input instanceof Map<?, ?> m) {
                if (m.get("type") != null) return List.of(m);
                if (m.get("nodes") instanceof List<?> nodes) return nodes;
            }
            return null;
        }

        private static void add(Element parent, Object n, Map<Element, Map<?, ?>> byElement) {
            if (!(n instanceof Map<?, ?> node)) return;
            Object tag = node.get("tag");
            if (tag == null) {
                if (node.get("text") != null) parent.appendChild(new TextNode(node.get("text").toString()));
                return;
            }
            Element el = new Element(tag.toString());
            if (node.get("id") != null) el.id(node.get("id").toString());
            if (node.get("classes") instanceof List<?> classes) {
                for (Object c : classes) el.addClass(c.toString());
            }
            if ("fence".equals(node.get("type")) && "pre".equals(tag) && node.get("lang") != null) {
                el.addClass(node.get("lang").toString());
            }
            if (node.get("attributes") instanceof Map<?, ?> attrs) {
                attrs.forEach((k, v) -> el.attr(k.toString(), v == null ? "" : v.toString()));
            }
            if (node.get("directives") instanceof Map<?, ?> dirs) {
                dirs.forEach((k, v) -> el.attr("data-paperband-" + k, v == null ? "" : v.toString()));
            }
            parent.appendChild(el);
            byElement.put(el, node);
            if (node.get("children") instanceof List<?> children) {
                for (Object c : children) add(el, c, byElement);
            }
        }
    }
}
