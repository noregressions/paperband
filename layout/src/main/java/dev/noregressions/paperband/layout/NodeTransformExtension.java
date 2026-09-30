package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Node;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filters that change a block's content as data and write it back:
 * {@code drop('css')}, {@code addClass('css', 'name')}, {@code blank('css', 'name')}
 * and {@code html}.
 *
 * <pre>
 * {{ e.block | drop('pre.console') | addClass('p.instructions', 'lead') | html | raw }}
 * </pre>
 *
 * <p>{@code find} and {@code query} pick parts out of a card, but what they
 * pick can only be printed as it was. These make a changed copy: each takes a
 * block (its own nodes, children excluded like {@code block.html}), a list of
 * nodes or one node, and returns the nodes it changed, as a list. Nothing is
 * changed in place, so the card prints as it did everywhere else.
 * <ul>
 *   <li>{@code drop} leaves out every node matching a selector, and what's in
 *       it.</li>
 *   <li>{@code addClass} adds a class to every node matching a selector. A
 *       class is letters, digits, {@code -} and {@code _}.</li>
 *   <li>{@code blank} puts an empty {@code <div>} with a class where each
 *       node matching a selector was: space to write in, where a student
 *       edition leaves out a solution.</li>
 *   <li>{@code html} writes nodes back as HTML, the way {@code block.html} is
 *       written: an unchanged node prints the bytes it came from, and a
 *       templated fence goes through its block template. Print it with
 *       {@code | raw}, as {@code block.html} is.</li>
 * </ul>
 * The selector sees what {@code find}'s does. The content was sanitised when the
 * card loaded, and no transform can add markup of the template's choosing:
 * they remove nodes, add a class the writer escapes, or add an empty
 * {@code div} with one.
 */
final class NodeTransformExtension extends AbstractExtension {

    private static final Pattern CLASS_NAME = Pattern.compile("-?[A-Za-z_][A-Za-z0-9_-]*");

    /** Read as one element without children; see NodeHtml. */
    private static final Set<String> OPAQUE_TAGS = Set.of("svg", "math");

    @Override
    public Map<String, Filter> getFilters() {
        return Map.of("drop", new Drop(), "addClass", new AddClass(), "blank", new Blank(), "html", new Html());
    }

    /** What a node becomes: itself, a changed copy, or null to leave it out. */
    @FunctionalInterface
    private interface Change {
        Node apply(Node matched);
    }

    private static final class Drop implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            return transform("drop", input, args.get("selector"), n -> null, self, lineNumber);
        }
    }

    private static final class AddClass implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector", "name");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            String cls = className("addClass", args.get("name"), self, lineNumber);
            return transform("addClass", input, args.get("selector"), n -> withClass(n, cls), self, lineNumber);
        }
    }

    private static final class Blank implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector", "name");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            String cls = className("blank", args.get("name"), self, lineNumber);
            Node space = space(cls);
            return transform("blank", input, args.get("selector"), n -> space, self, lineNumber);
        }
    }

    private static final class Html implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of();
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            StringBuilder out = new StringBuilder();
            for (NodeModel m : models("html", input, self, lineNumber)) {
                out.append(NodeHtml.write(List.of(m.node()), m.fences()));
            }
            return out.toString();
        }
    }

    /** {@code name} as a class, or a failure naming {@code filter}'s arguments. */
    private static String className(String filter, Object name, PebbleTemplate self, int lineNumber)
            throws PebbleException {
        if (name == null || !CLASS_NAME.matcher(name.toString()).matches()) {
            throw new PebbleException(null, filter + " needs a selector and a class name of letters,"
                    + " digits, - and _, as in " + filter + "('.solution', 'answer-space'), not "
                    + (name == null ? "nothing" : "'" + name + "'"), lineNumber, self.getName());
        }
        return name.toString();
    }

    /** An empty {@code <div class="cls">}: what {@code blank} leaves where a node was. */
    private static Node space(String cls) {
        String html = "<div class=\"" + cls + "\"></div>";
        return new Node("element", "div", null, Set.of(cls), Map.of(), Map.of(), "", html, List.of(), Map.of());
    }

    /** {@code input}'s nodes with {@code change} applied to each that {@code selector} matches. */
    private static List<NodeModel> transform(String filter, Object input, Object selector, Change change,
                                             PebbleTemplate self, int lineNumber) throws PebbleException {
        if (selector == null || selector.toString().isBlank()) {
            throw new PebbleException(null, filter + " needs a CSS selector, as in"
                    + " block | " + filter + "('pre.console'" + (filter.equals("addClass") ? ", 'x'" : "")
                    + ")", lineNumber, self.getName());
        }
        List<NodeModel> roots = models(filter, input, self, lineNumber);
        Set<Node> matched = matches(filter, roots, selector.toString(), self, lineNumber);
        List<NodeModel> out = new ArrayList<>(roots.size());
        for (NodeModel m : roots) {
            Node changed = rebuild(m.node(), matched, change);
            if (changed == null) continue;
            out.add(changed == m.node() ? m : NodeModel.of(changed, m.fences()));
        }
        return out;
    }

    /** The node models {@code input} holds: a block's nodes, a list of nodes, or one node. */
    private static List<NodeModel> models(String filter, Object input, PebbleTemplate self, int lineNumber)
            throws PebbleException {
        if (input == null) return List.of();
        List<?> items;
        if (input instanceof NodeModel m) items = List.of(m);
        else if (input instanceof List<?> list) items = list;
        else if (input instanceof Map<?, ?> block && block.get("nodes") instanceof List<?> nodes) items = nodes;
        else items = null;
        List<NodeModel> out = new ArrayList<>();
        if (items != null) {
            for (Object item : items) {
                if (!(item instanceof NodeModel m)) {
                    items = null;
                    break;
                }
                out.add(m);
            }
        }
        if (items == null) {
            throw new PebbleException(null, filter + " works on a block, block.nodes or a node"
                    + " (from a query entry, e.node or e.block), not "
                    + (input instanceof List<?> ? "a list of something else" : input.getClass().getSimpleName()),
                    lineNumber, self.getName());
        }
        return out;
    }

    /** The nodes under {@code roots} that {@code selector} matches, by identity. */
    private static Set<Node> matches(String filter, List<NodeModel> roots, String selector,
                                     PebbleTemplate self, int lineNumber) throws PebbleException {
        Map<Element, Map<?, ?>> byElement = new IdentityHashMap<>();
        Element top = new Element("paperband-nodes");
        for (NodeModel m : roots) NodeFindExtension.standIn(top, m, byElement);
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Element match : top.select(selector)) {
                if (byElement.get(match) instanceof NodeModel m) out.add(m.node());
            }
        } catch (Selector.SelectorParseException e) {
            throw new PebbleException(e, filter + "('" + selector + "') isn't a CSS selector jsoup"
                    + " can read: " + e.getMessage(), lineNumber, self.getName());
        }
        return out;
    }

    /**
     * {@code n} with {@code change} applied wherever it matched: {@code n}
     * itself when nothing under it changed, null when it's left out.
     */
    private static Node rebuild(Node n, Set<Node> matched, Change change) {
        Node self = n;
        if (matched.contains(n)) {
            self = change.apply(n);
            if (self == null) return null;
        }
        List<Node> children = new ArrayList<>(self.children().size());
        boolean changed = false;
        for (Node c : self.children()) {
            Node r = rebuild(c, matched, change);
            if (r != c) changed = true;
            if (r != null) children.add(r);
        }
        if (!changed) return self;
        return rewritten(self, children);
    }

    /** {@code n} with new children, its {@code html} and {@code text} written from them. */
    private static Node rewritten(Node n, List<Node> children) {
        Node draft = new Node(n.type(), n.tag(), n.id(), n.classes(), n.attributes(), n.directives(),
                n.text(), n.html(), children, n.props(), n.attributeOrder());
        String html = NodeHtml.write(List.of(draft));
        String text = Jsoup.parseBodyFragment(html).body().text();
        return new Node(n.type(), n.tag(), n.id(), n.classes(), n.attributes(), n.directives(),
                text, html, children, n.props(), n.attributeOrder());
    }

    /** {@code n} with {@code cls} among its classes, and in its HTML. */
    private static Node withClass(Node n, String cls) {
        if (n.tag() == null || n.classes().contains(cls)) return n;
        Set<String> classes = new LinkedHashSet<>(n.classes());
        classes.add(cls);
        List<String> order = n.attributeOrder();
        if (!order.isEmpty() && !order.contains("class")) {
            order = new ArrayList<>(order);
            order.add("class");
        }
        Node draft = new Node(n.type(), n.tag(), n.id(), classes, n.attributes(), n.directives(),
                n.text(), n.html(), n.children(), n.props(), order);
        String html;
        if (OPAQUE_TAGS.contains(n.tag())) {
            // A drawing is written from its own HTML, not from its children.
            Element drawn = Jsoup.parseBodyFragment(n.html()).body().firstElementChild();
            html = drawn == null ? n.html() : drawn.addClass(cls).outerHtml();
        } else {
            html = NodeHtml.write(List.of(draft));
        }
        return new Node(n.type(), n.tag(), n.id(), classes, n.attributes(), n.directives(),
                n.text(), html, n.children(), n.props(), order);
    }
}
