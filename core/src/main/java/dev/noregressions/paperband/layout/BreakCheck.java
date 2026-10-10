package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.Attributes;
import dev.noregressions.paperband.Breaks;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.NodeInfo;
import dev.noregressions.paperband.Variants;
import org.commonmark.node.Node;
import org.jsoup.nodes.Element;
import org.jsoup.select.Evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Checks, for a paged output, that every break marker on a page can take effect in the box it
 * landed in: the box splits, the marker isn't at the very start of the box or inside something
 * kept together, and (with {@code markers: narrow}) it's at one of the box's break points.
 * {@code {! break=avoid}} is always satisfied where nothing breaks, so it's only checked like the
 * others in a splitting box. A box with {@code markers: ignore} isn't checked.
 */
final class BreakCheck {

    private BreakCheck() {
    }

    /**
     * @param units  each placed each box's matches, as the nodes each match stands for
     * @param views  a file's selector view, for a variant preference
     */
    static void check(Layout layout, List<Page.PlacedBox> placed, Map<Layout.Box, List<List<Node>>> units,
                      Variants.Variant prefer, BiFunction<MarkdownFile, Variants.Variant, AstView> views,
                      String where) throws ConfigException {
        for (Page.PlacedBox p : placed) {
            Layout.Box box = p.box();
            List<List<Node>> boxUnits = switch (box.kind()) {
                case EACH -> units.getOrDefault(box, List.of());
                case SELECT, REST -> p.nodes().stream().map(List::of).toList();
                default -> nestedNodes(p).stream().map(List::of).toList();
            };
            List<Node> blocks = boxUnits.stream().flatMap(List::stream).toList();
            Set<Node> inBox = AstView.identitySet(List.of());
            for (Node b : blocks) {
                addAll(b, prefer, inBox);
            }
            for (Node b : blocks) {
                List<Node> marked = new ArrayList<>();
                marked(b, prefer, marked);
                for (Node n : marked) {
                    checkOne(n, box, layout, boxUnits, inBox, prefer, views, where);
                }
            }
        }
    }

    private static void checkOne(Node n, Layout.Box box, Layout layout, List<List<Node>> units, Set<Node> inBox,
                                 Variants.Variant prefer, BiFunction<MarkdownFile, Variants.Variant, AstView> views,
                                 String where) throws ConfigException {
        MarkdownFile file = fileOf(n);
        Breaks.Break b = Breaks.of(file.getAttributes(n));
        Layout.Split split = box.rules().split();
        String marker = "{! break=" + b.id() + "}";
        String at = file.getPath() + " line " + NodeInfo.line(n) + ": " + marker + " is in box \"" + box.id()
                + "\" of layout \"" + layout.name() + "\" (" + where + ")";
        if (split == null) {
            if (b.wantsBreak()) {
                throw new ConfigException(at + ", which doesn't split; give the box a split rule, or remove the marker");
            }
            return;
        }
        if (split.markers() == Layout.BreakMarkers.IGNORE) {
            return;
        }
        if (!units.isEmpty() && !units.getFirst().isEmpty() && leads(n, units.getFirst().getFirst())) {
            if (b.wantsBreak()) {
                throw new ConfigException(at + ", but it's at the very start of the box, with nothing before it to break from");
            }
            return;
        }
        AstView view = views.apply(file, prefer);
        Element e = view.element(n);
        if (e == null) {
            return;
        }
        for (int i = 0; i < split.keepEvaluators().size(); i++) {
            for (Element parent : e.parents()) {
                if (parent.is(split.keepEvaluators().get(i)) && within(view, parent, inBox)) {
                    throw new ConfigException(at + ", inside \"" + split.keepTogether().get(i)
                            + "\", which split.keep-together never breaks");
                }
            }
        }
        if (split.markers() == Layout.BreakMarkers.ADD) {
            // A page only breaks between the box's blocks (an each box's items), never inside a list or group.
            if (b.wantsBreak() && units.stream().noneMatch(u -> !u.isEmpty() && leads(n, u.getFirst()))) {
                throw new ConfigException(at + ", inside a list or group; with split.markers: add, a marker can"
                        + " make a break between the box's blocks, not inside one");
            }
            return;
        }
        boolean candidate = split.between() == null
                ? units.stream().anyMatch(u -> !u.isEmpty() && leads(n, u.getFirst()))
                : startsMatch(e, split.betweenEvaluator(), view, inBox);
        if (!candidate) {
            String points = split.between() != null ? "split.between: " + split.between()
                    : box.kind() == Layout.Kind.EACH ? "between its each: " + box.select() + " matches"
                    : "between its blocks";
            throw new ConfigException(at + ", but not at a break point the box allows (" + points
                    + "); move the marker, or let markers make break points with split.markers: add");
        }
    }

    /** True if {@code e}, or an element it begins, matches {@code between} and lies wholly in the box. */
    private static boolean startsMatch(Element e, Evaluator between, AstView view, Set<Node> inBox) {
        for (Element x = e; x != null; x = x.parent()) {
            if (x.is(between) && within(view, x, inBox)) {
                return true;
            }
            Element parent = x.parent();
            if (parent == null || parent.firstElementChild() != x) {
                return false;
            }
        }
        return false;
    }

    private static boolean within(AstView view, Element e, Set<Node> inBox) {
        List<Node> nodes = view.nodes(List.of(e));
        return !nodes.isEmpty() && inBox.containsAll(nodes);
    }

    /** True if {@code n} is {@code unit} or begins it (first child, first child of that, ...). */
    private static boolean leads(Node n, Node unit) {
        for (Node x = n; x != null; x = x.getParent()) {
            if (x == unit) {
                return true;
            }
            if (x.getPrevious() != null) {
                return false;
            }
        }
        return false;
    }

    /** Nodes with a break marker in {@code n} and below it, chosen variants only. */
    private static void marked(Node n, Variants.Variant prefer, List<Node> into) {
        MarkdownFile file = fileOf(n);
        Attributes a = file == null ? null : file.getAttributes(n);
        if (Breaks.of(a) != null) {
            into.add(n);
        }
        for (Node c : children(n, prefer)) {
            marked(c, prefer, into);
        }
    }

    private static void addAll(Node n, Variants.Variant prefer, Set<Node> into) {
        into.add(n);
        for (Node c : children(n, prefer)) {
            addAll(c, prefer, into);
        }
    }

    private static List<Node> children(Node n, Variants.Variant prefer) {
        if (Variants.isGroup(n)) {
            Node chosen = Variants.choose(n, prefer);
            return chosen == null ? List.of() : List.of(chosen);
        }
        List<Node> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            out.add(c);
        }
        return out;
    }

    /** What the inner layouts of a box holding layouts placed, all the way down. */
    private static List<Node> nestedNodes(Page.PlacedBox p) {
        List<Node> out = new ArrayList<>(p.nodes());
        for (Page.Nested n : p.nested()) {
            for (Page.PlacedBox inner : n.boxes()) {
                out.addAll(nestedNodes(inner));
            }
        }
        return out;
    }

    private static MarkdownFile fileOf(Node n) {
        for (Node x = n; x != null; x = x.getParent()) {
            if (x instanceof MarkdownFile f) {
                return f;
            }
        }
        return null;
    }
}
