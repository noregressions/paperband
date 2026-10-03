package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Node;
import dev.noregressions.paperband.pebble.LenientMap;

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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filters that change a card's content as data: what a view does to a card
 * before a layout writes it.
 *
 * <pre>
 * {{ e.block | drop('pre.console') | addClass('p.instructions', 'lead') | html | raw }}
 * {% set card = card | replace('.solution', '{.answer-space lines=4}') %}
 * </pre>
 *
 * <p>Each takes a CSS selector and changes what it matches:
 * <ul>
 *   <li>{@code drop} leaves it out, and what's in it. {@code keep} is the
 *       other way round: it leaves out everything that isn't a match, inside
 *       one, or around one.</li>
 *   <li>{@code addClass} and {@code removeClass} change its classes, and
 *       {@code set('{key=value}')} its attributes.</li>
 *   <li>{@code replace}, {@code insertBefore}, {@code insertAfter},
 *       {@code prepend}, {@code append} and {@code wrap} add an empty block,
 *       written the way a card writes attributes: {@code '{.answer lines=4}'}
 *       (see {@link BlockSpec}). {@code replace} keeps the id and attributes
 *       of what it replaces, its own winning over the spec's, so a link to it
 *       still lands and {@code {lines=8}} on a solution sizes the answer.
 *       {@code blank('css', 'name')} is {@code replace} with only a class,
 *       and a node it blanks keeps nothing.</li>
 *   <li>{@code html} writes nodes back as HTML, the way {@code block.html} is
 *       written: an unchanged node prints the bytes it came from, and a
 *       templated fence goes through its block template. Print it with
 *       {@code | raw}, as {@code block.html} is.</li>
 * </ul>
 *
 * <p>What a filter takes decides what it sees and what it returns:
 * <ul>
 *   <li>A block (its own nodes, children excluded, like {@code block.html}),
 *       a list of nodes or one node: the selector sees nodes, as
 *       {@code find}'s does, and the filter returns the changed nodes as a
 *       list, so filters chain and {@code html} writes them.</li>
 *   <li>A card, or a list of cards: the selector sees cards, blocks and nodes,
 *       as {@code query}'s does ({@code block.solution}, {@code p.solution},
 *       {@code card:has(pre.command)}), and the filter returns the changed card
 *       -- its blocks, each block's html, its steps and its slots made again --
 *       or the list of them. A card a {@code drop} or {@code keep} leaves out
 *       is null, or missing from the list.</li>
 * </ul>
 * Every match is found before anything changes, so a filter never matches what
 * it added; a filter later in the chain sees what an earlier one did. Nothing
 * is changed in place, so the card prints as it did everywhere else.
 *
 * <p>The content was sanitised when the card loaded, and no transform can add
 * markup of the template's choosing: they remove nodes, change classes and
 * attributes the content policy allows, or add an empty {@code div} with them.
 */
final class NodeTransformExtension extends AbstractExtension {

    private static final Pattern CLASS_NAME = Pattern.compile("-?[A-Za-z_][A-Za-z0-9_-]*");

    /** Read as one element without children; see NodeHtml. */
    private static final Set<String> OPAQUE_TAGS = Set.of("svg", "math");

    private static final List<String> SELECTOR = List.of("selector");
    private static final List<String> SELECTOR_NAME = List.of("selector", "name");
    private static final List<String> SELECTOR_BLOCK = List.of("selector", "block");

    /** What each kind of filter takes after its selector, for a failure to show. */
    private static final String NAME_EXAMPLE = ", 'lead'";
    private static final String SET_EXAMPLE = ", '{lines=8}'";
    private static final String BLOCK_EXAMPLE = ", '{.answer}'";

    /** The transforms by name, each a filter and what a statement compiles to. */
    private static final Map<String, Transform> TRANSFORMS = new LinkedHashMap<>();

    private static void add(String name, List<String> argumentNames, String example, Maker maker) {
        TRANSFORMS.put(name, new Transform(name, argumentNames, example, maker));
    }

    static {
        add("drop", SELECTOR, "", a -> NodeTransformExtension.DROP);
        add("keep", SELECTOR, "", a -> NodeTransformExtension.DROP);
        add("addClass", SELECTOR_NAME, NAME_EXAMPLE,
                a -> new Restyle(className("addClass", a.get("name")), null, null));
        add("removeClass", SELECTOR_NAME, NAME_EXAMPLE,
                a -> new Restyle(null, className("removeClass", a.get("name")), null));
        add("set", List.of("selector", "attributes"), SET_EXAMPLE,
                a -> new Restyle(null, null, attributes(a.get("attributes"))));
        add("blank", SELECTOR_NAME, NAME_EXAMPLE,
                a -> new Replace(new BlockSpec(null, Set.of(className("blank", a.get("name"))), Map.of()), false));
        add("replace", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Replace(spec(a.get("block")), true));
        add("insertBefore", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Insert(spec(a.get("block")), Where.BEFORE));
        add("insertAfter", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Insert(spec(a.get("block")), Where.AFTER));
        add("prepend", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Insert(spec(a.get("block")), Where.FIRST));
        add("append", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Insert(spec(a.get("block")), Where.LAST));
        add("wrap", SELECTOR_BLOCK, BLOCK_EXAMPLE,
                a -> new Wrap(spec(a.get("block"))));
    }

    @Override
    public Map<String, Filter> getFilters() {
        Map<String, Filter> filters = new LinkedHashMap<>(TRANSFORMS);
        filters.put("html", new Html());
        return filters;
    }

    // ---- what each filter does to a match ----

    /** What a match becomes: the nodes or blocks that take its place, none to leave it out. */
    private interface Op {
        List<Node> node(Node matched);

        List<Map<String, Object>> block(Map<String, Object> matched);

        /** Whether it leaves a match out: the only change a card can take. */
        default boolean drops() {
            return false;
        }
    }

    private static final Op DROP = new Op() {
        @Override
        public List<Node> node(Node matched) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> block(Map<String, Object> matched) {
            return List.of();
        }

        @Override
        public boolean drops() {
            return true;
        }
    };

    /** A class added or removed, or attributes set. */
    private record Restyle(String add, String remove, Map<String, String> set) implements Op {

        private Set<String> classes(Iterable<?> current) {
            Set<String> out = new LinkedHashSet<>();
            for (Object c : current) out.add(c.toString());
            if (add != null) out.add(add);
            if (remove != null) out.remove(remove);
            return out;
        }

        private Map<String, String> attributes(Map<?, ?> current) {
            Map<String, String> out = new LinkedHashMap<>();
            current.forEach((k, v) -> out.put(k.toString(), v == null ? "" : v.toString()));
            if (set != null) out.putAll(set);
            return out;
        }

        @Override
        public List<Node> node(Node n) {
            if (n.tag() == null) return List.of(n);
            return List.of(restyled(n, classes(n.classes()), attributes(n.attributes())));
        }

        @Override
        public List<Map<String, Object>> block(Map<String, Object> b) {
            List<String> classes = new ArrayList<>(classes((List<?>) b.get("classes")));
            Map<String, Object> out = new HashMap<>(b);
            out.put("classes", classes);
            out.put("classAttr", String.join(" ", classes));
            out.put("attributes", LenientMap.of(attributes((Map<?, ?>) b.get("attributes"))));
            return List.of(out);
        }
    }

    /**
     * An empty block in a match's place. A block keeps the match's id, anchor
     * and attributes, its own winning over the spec's; a node does too when
     * {@code keepOwn}, and {@code blank}'s keeps nothing.
     */
    private record Replace(BlockSpec spec, boolean keepOwn) implements Op {

        @Override
        public List<Node> node(Node n) {
            return keepOwn ? List.of(spec.node(n.id(), n.attributes(), List.of()))
                    : List.of(spec.node(null, Map.of(), List.of()));
        }

        @Override
        public List<Map<String, Object>> block(Map<String, Object> b) {
            return List.of(spec.block(level(b), (String) b.get("id"), (String) b.get("anchor"),
                    (Map<?, ?>) b.get("attributes"), new ArrayList<>()));
        }
    }

    private enum Where { BEFORE, AFTER, FIRST, LAST }

    /** An empty block beside a match, or inside it, first or last. */
    private record Insert(BlockSpec spec, Where where) implements Op {

        @Override
        public List<Node> node(Node n) {
            Node added = spec.node(null, Map.of(), List.of());
            return switch (where) {
                case BEFORE -> List.of(added, n);
                case AFTER -> List.of(n, added);
                case FIRST, LAST -> {
                    // Text has nowhere to put it, and a drawing is written from its own html.
                    if (n.tag() == null || OPAQUE_TAGS.contains(n.tag())) yield List.of(n);
                    List<Node> children = new ArrayList<>(n.children());
                    if (where == Where.FIRST) children.add(0, added);
                    else children.add(added);
                    yield List.of(rewritten(n, children));
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> block(Map<String, Object> b) {
            return switch (where) {
                case BEFORE -> List.of(spec.block(level(b), null, null, Map.of(), new ArrayList<>()), b);
                case AFTER -> List.of(b, spec.block(level(b), null, null, Map.of(), new ArrayList<>()));
                case FIRST, LAST -> {
                    List<Map<String, Object>> children =
                            new ArrayList<>((List<Map<String, Object>>) b.get("children"));
                    Map<String, Object> added = spec.block(level(b) + 1, null, null, Map.of(), new ArrayList<>());
                    if (where == Where.FIRST) children.add(0, added);
                    else children.add(added);
                    Map<String, Object> out = new HashMap<>(b);
                    out.put("children", children);
                    yield List.of(out);
                }
            };
        }
    }

    /** A match inside a new block. */
    private record Wrap(BlockSpec spec) implements Op {

        @Override
        public List<Node> node(Node n) {
            return List.of(spec.node(null, Map.of(), List.of(n)));
        }

        @Override
        public List<Map<String, Object>> block(Map<String, Object> b) {
            List<Map<String, Object>> children = new ArrayList<>();
            children.add(b);
            return List.of(spec.block(level(b), null, null, Map.of(), children));
        }
    }

    private static int level(Map<String, Object> b) {
        return b.get("level") instanceof Number n ? n.intValue() : 0;
    }

    // ---- the filters ----

    /** Builds a filter's {@link Op} from its arguments, or says what's wrong with them. */
    @FunctionalInterface
    private interface Maker {
        Op make(Map<String, Object> args);
    }

    private record Transform(String name, List<String> argumentNames, String example, Maker maker)
            implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return argumentNames;
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            try {
                return run(input, args);
            } catch (IllegalArgumentException e) {
                throw new PebbleException(e.getCause(), e.getMessage(), lineNumber, self.getName());
            }
        }

        /** {@code input} changed, or a failure saying what's wrong with the call. */
        Object run(Object input, Map<String, Object> args) {
            Object selector = args.get("selector");
            if (selector == null || selector.toString().isBlank()) {
                throw new IllegalArgumentException(name + " needs a CSS selector, as in block | " + name
                        + "('pre.console'" + example + ")");
            }
            Op op;
            try {
                op = maker.make(args);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
            }
            Call call = new Call(name, selector.toString(), op, name.equals("keep"));
            if (isCard(input)) return call.card((Map<?, ?>) input);
            if (input instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(NodeTransformExtension::isCard)) {
                return call.cards(list);
            }
            return call.nodes(models(name, input));
        }
    }

    /**
     * The transform {@code name} applied to {@code input} with {@code args}, as
     * its filter would: how a statement runs.
     *
     * @throws IllegalArgumentException when there's no such transform, or the
     *         call is wrong, saying why
     */
    static Object run(String name, Object input, Map<String, Object> args) {
        Transform t = TRANSFORMS.get(name);
        if (t == null) throw new IllegalArgumentException("no transform '" + name + "'");
        return t.run(input, args);
    }

    private static final class Html implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of();
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            try {
                return html(models("html", input));
            } catch (IllegalArgumentException e) {
                throw new PebbleException(null, e.getMessage(), lineNumber, self.getName());
            }
        }
    }

    private static boolean isCard(Object o) {
        return o instanceof Map<?, ?> m && !(o instanceof NodeModel) && m.get("blocks") instanceof List<?>;
    }

    /** {@code name} as a class, or a failure saying what a class is. */
    private static String className(String filter, Object name) {
        if (name == null || !CLASS_NAME.matcher(name.toString()).matches()) {
            throw new IllegalArgumentException("needs a selector and a class name of letters,"
                    + " digits, - and _, as in " + filter + "('.solution', 'answer-space'), not "
                    + (name == null ? "nothing" : "'" + name + "'"));
        }
        return name.toString();
    }

    private static BlockSpec spec(Object text) {
        return BlockSpec.parse(text == null ? null : text.toString());
    }

    private static Map<String, String> attributes(Object text) {
        BlockSpec spec = spec(text);
        if (!spec.attributesOnly() || spec.attributes().isEmpty()) {
            throw new IllegalArgumentException("sets attributes, as in set('.solution', '{lines=8}');"
                    + " use addClass for a class");
        }
        return spec.attributes();
    }

    /** Nodes written back as HTML, each through its own block's fences. */
    private static String html(List<?> models) {
        StringBuilder out = new StringBuilder();
        for (Object o : models) {
            if (o instanceof NodeModel m) {
                out.append(m.fences() == null ? NodeHtml.write(List.of(m.node()))
                        : NodeHtml.write(List.of(m.node()), m.fences()));
            }
        }
        return out.toString();
    }

    /** The node models {@code input} holds: a block's nodes, a list of nodes, or one node. */
    private static List<NodeModel> models(String filter, Object input) {
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
            throw new IllegalArgumentException(filter + " works on a card, cards, a block, block.nodes or a node"
                    + " (from a query entry, e.node or e.block), not "
                    + (input instanceof List<?> ? "a list of something else" : input.getClass().getSimpleName()));
        }
        return out;
    }

    // ---- one call: find the matches, then make the changed copy ----

    private record Call(String filter, String selector, Op op, boolean keep) {

        /** The changed nodes: the selector sees nodes only. */
        List<NodeModel> nodes(List<NodeModel> roots) {
            Map<Element, Object> models = new IdentityHashMap<>();
            Element top = new Element("paperband-nodes");
            Map<Element, Map<?, ?>> byElement = new IdentityHashMap<>();
            for (NodeModel m : roots) NodeFindExtension.standIn(top, m, byElement);
            models.putAll(byElement);
            Targets t = targets(top, models);
            List<NodeModel> out = new ArrayList<>(roots.size());
            for (NodeModel m : roots) out.addAll(rebuilt(m, t));
            return out;
        }

        /** The changed card, or null when it's left out. */
        Map<String, Object> card(Map<?, ?> card) {
            List<Map<String, Object>> out = cards(List.of(card));
            return out.isEmpty() ? null : out.get(0);
        }

        /** The changed cards, less any left out. */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards(List<?> cards) {
            Map<Element, BookQueryExtension.Origin> origins = new IdentityHashMap<>();
            Element top = new Element("paperband-book");
            for (Object c : cards) BookQueryExtension.addCard(top, (Map<?, ?>) c, origins);
            Map<Element, Object> models = new IdentityHashMap<>();
            origins.forEach((e, o) -> models.put(e, o.model()));
            Targets t = targets(top, models);
            if (!op.drops() && !t.cards.isEmpty()) {
                throw new IllegalArgumentException(filter + "('" + selector + "') matches a card; " + filter
                        + " changes blocks and nodes. Use drop or keep to leave cards out");
            }
            List<Map<String, Object>> out = new ArrayList<>(cards.size());
            for (Object o : cards) {
                Map<String, Object> card = (Map<String, Object>) o;
                if (t.cards.contains(card)) continue;
                List<Map<String, Object>> blocks = (List<Map<String, Object>>) card.get("blocks");
                List<Map<String, Object>> changed = rebuiltBlocks(blocks, t);
                if (changed == blocks) {
                    out.add(card);
                    continue;
                }
                Map<String, Object> copy = new HashMap<>(card);
                copy.put("blocks", changed);
                copy.put("slots", card.get("slots") instanceof SlotTracker s ? s.derive(changed)
                        : new SlotTracker(changed));
                copy.put("steps", StepList.steps(changed));
                out.add(copy);
            }
            return out;
        }

        /** What the selector matched under {@code top}, by kind: for {@code keep}, what it didn't. */
        private Targets targets(Element top, Map<Element, Object> models) {
            List<Element> matched;
            try {
                matched = top.select(selector);
            } catch (Selector.SelectorParseException e) {
                throw new IllegalArgumentException(filter + "('" + selector + "') isn't a CSS selector jsoup"
                        + " can read: " + e.getMessage(), e);
            }
            Set<Element> hit = Collections.newSetFromMap(new IdentityHashMap<>());
            if (keep) {
                // What survives a keep: each match, what's in it, and what it's in.
                Set<Element> kept = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Element m : matched) {
                    kept.addAll(m.getAllElements());
                    for (Element p = m.parent(); p != null && p != top; p = p.parent()) kept.add(p);
                }
                for (Element e : top.getAllElements()) {
                    if (e != top && !kept.contains(e)) hit.add(e);
                }
            } else {
                hit.addAll(matched);
            }
            Targets t = new Targets();
            for (Element e : hit) {
                Object model = models.get(e);
                if (model instanceof NodeModel n) t.nodes.add(n.node());
                else if (model instanceof Map<?, ?> m && m.get("blocks") instanceof List<?>) t.cards.add(m);
                else if (model instanceof Map<?, ?> m) t.blocks.add(m);
            }
            return t;
        }

        /** {@code m}'s node with the op applied wherever it matched, as models. */
        private List<NodeModel> rebuilt(NodeModel m, Targets t) {
            List<Node> r = rebuilt(m.node(), t.nodes);
            if (r.size() == 1 && r.get(0) == m.node()) return List.of(m);
            List<NodeModel> out = new ArrayList<>(r.size());
            for (Node n : r) out.add(NodeModel.of(n, m.fences()));
            return out;
        }

        /**
         * What takes {@code n}'s place: its children changed first, then the op
         * applied to it if it matched. {@code n} itself when nothing changed.
         */
        private List<Node> rebuilt(Node n, Set<Node> matched) {
            Node self = n;
            if (!n.children().isEmpty()) {
                List<Node> children = new ArrayList<>(n.children().size());
                boolean changed = false;
                for (Node c : n.children()) {
                    List<Node> r = rebuilt(c, matched);
                    if (r.size() != 1 || r.get(0) != c) changed = true;
                    children.addAll(r);
                }
                if (changed) self = rewritten(n, children);
            }
            return matched.contains(n) ? op.node(self) : List.of(self);
        }

        /** {@code blocks} changed, or {@code blocks} itself when nothing in them did. */
        private List<Map<String, Object>> rebuiltBlocks(List<Map<String, Object>> blocks, Targets t) {
            List<Map<String, Object>> out = new ArrayList<>(blocks.size());
            boolean changed = false;
            for (Map<String, Object> b : blocks) {
                List<Map<String, Object>> r = rebuiltBlock(b, t);
                if (r.size() != 1 || r.get(0) != b) changed = true;
                out.addAll(r);
            }
            return changed ? out : blocks;
        }

        /** What takes {@code b}'s place: its nodes and nested blocks changed, then the op if it matched. */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> rebuiltBlock(Map<String, Object> b, Targets t) {
            List<Object> nodes = new ArrayList<>();
            boolean nodesChanged = false;
            for (Object o : (List<?>) b.get("nodes")) {
                if (!(o instanceof NodeModel m)) {
                    nodes.add(o);
                    continue;
                }
                List<NodeModel> r = rebuilt(m, t);
                if (r.size() != 1 || r.get(0) != m) nodesChanged = true;
                nodes.addAll(r);
            }
            List<Map<String, Object>> children = (List<Map<String, Object>>) b.get("children");
            List<Map<String, Object>> newChildren = rebuiltBlocks(children, t);
            Map<String, Object> self = b;
            if (nodesChanged || newChildren != children) {
                self = new HashMap<>(b);
                if (nodesChanged) {
                    self.put("nodes", nodes);
                    self.put("html", html(nodes));
                }
                self.put("children", newChildren);
            }
            return t.blocks.contains(b) ? op.block(self) : List.of(self);
        }
    }

    /** What a selector matched, by identity: the models, and the records under the nodes. */
    private static final class Targets {
        final Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> cards = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // ---- rewriting a node ----

    /** {@code n} with new children, its {@code html} and {@code text} written from them. */
    private static Node rewritten(Node n, List<Node> children) {
        Node draft = new Node(n.type(), n.tag(), n.id(), n.classes(), n.attributes(), n.directives(),
                n.text(), n.html(), children, n.props(), n.attributeOrder());
        String html = NodeHtml.write(List.of(draft));
        String text = Jsoup.parseBodyFragment(html).body().text();
        return new Node(n.type(), n.tag(), n.id(), n.classes(), n.attributes(), n.directives(),
                text, html, children, n.props(), n.attributeOrder());
    }

    /** {@code n} with these classes and attributes, in its HTML too. */
    private static Node restyled(Node n, Set<String> classes, Map<String, String> attributes) {
        if (classes.equals(n.classes()) && attributes.equals(n.attributes())) return n;
        List<String> order = n.attributeOrder();
        if (!order.isEmpty()) {
            order = new ArrayList<>(order);
            if (classes.isEmpty()) order.remove("class");
            else if (!order.contains("class")) order.add("class");
            for (String key : attributes.keySet()) {
                if (!order.contains(key)) order.add(key);
            }
        }
        Node draft = new Node(n.type(), n.tag(), n.id(), classes, attributes, n.directives(),
                n.text(), n.html(), n.children(), n.props(), order);
        String html;
        if (OPAQUE_TAGS.contains(n.tag())) {
            // A drawing is written from its own HTML, not from its children.
            Element drawn = Jsoup.parseBodyFragment(n.html()).body().firstElementChild();
            if (drawn == null) {
                html = n.html();
            } else {
                drawn.classNames(classes);
                if (classes.isEmpty()) drawn.removeAttr("class");
                attributes.forEach(drawn::attr);
                html = drawn.outerHtml();
            }
        } else {
            html = NodeHtml.write(List.of(draft));
        }
        return new Node(n.type(), n.tag(), n.id(), classes, attributes, n.directives(),
                n.text(), html, n.children(), n.props(), order);
    }
}
