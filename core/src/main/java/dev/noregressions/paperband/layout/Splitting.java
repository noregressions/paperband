package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.Breaks;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.NodeInfo;
import dev.noregressions.paperband.Variants;
import org.commonmark.node.Node;
import org.jsoup.nodes.Element;
import org.jsoup.select.Evaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Divides a splitting box's content into units, the pieces a page break can fall between (see
 * {@link Layout.Split}):
 * <ul>
 *   <li>a select or rest box's top-level blocks, grouped so each unit starts at a
 *       {@code between} match (without {@code between}, every block is a unit);</li>
 *   <li>an each box's items;</li>
 *   <li>with {@code markers: add}, a top-level block marked {@code {! break=prefer}} or
 *       {@code force} starts a unit too;</li>
 *   <li>no break falls inside a {@code keep-together} match.</li>
 * </ul>
 * Each unit knows the break marker on its first block (unless {@code markers: ignore}) and
 * whether a break may fall before it.
 */
public final class Splitting {

    /**
     * One unit.
     *
     * @param nodes      a select or rest box's top-level nodes in it
     * @param item       an each box's item index; else -1
     * @param marker     the break marker on its first block, or null
     * @param breakable  whether a page may break just before it
     * @param line       its first line in its file, for reports; 0 if unknown
     */
    public record Unit(List<Node> nodes, int item, Breaks.Break marker, boolean breakable, int line) {
    }

    private Splitting() {
    }

    /** The units of a placed box with a split rule; for an each box, its items. */
    public static List<Unit> units(Page.PlacedBox placed, Layout.Split split) {
        boolean useMarkers = split.markers() != Layout.BreakMarkers.IGNORE;
        if (placed.box().kind() == Layout.Kind.EACH) {
            List<Unit> out = new ArrayList<>();
            for (int i = 0; i < placed.nested().size(); i++) {
                List<Node> match = placed.nested().get(i).match();
                Node first = match.isEmpty() ? null : match.getFirst();
                Breaks.Break marker = useMarkers && first != null ? Breaks.of(MarkdownFile.attributesOf(first)) : null;
                out.add(new Unit(List.of(), i, marker, i > 0 && marker != Breaks.Break.AVOID,
                        first == null ? 0 : NodeInfo.line(first)));
            }
            return out;
        }
        List<Node> nodes = placed.nodes();
        if (nodes.isEmpty()) {
            return List.of();
        }
        boolean[] starts = new boolean[nodes.size()];
        boolean[] kept = new boolean[nodes.size()]; // inside something kept together: no break before it
        starts[0] = true;
        AstView view = view(nodes.getFirst(), placed.prefer());
        Map<Node, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        if (split.betweenEvaluator() == null || view == null) {
            java.util.Arrays.fill(starts, true);
        } else {
            for (int i = 1; i < nodes.size(); i++) {
                starts[i] = startsMatch(view, nodes.get(i), split.betweenEvaluator(), index);
            }
        }
        for (int i = 1; i < nodes.size(); i++) {
            Breaks.Break b = Breaks.of(MarkdownFile.attributesOf(nodes.get(i)));
            if (split.markers() == Layout.BreakMarkers.ADD && b != null && b.wantsBreak()) {
                starts[i] = true;
            }
        }
        if (view != null) {
            for (Evaluator keep : split.keepEvaluators()) {
                for (Element e : view.match(keep, null, List.of(view.root()))) {
                    List<Node> covered = view.nodes(List.of(e));
                    int first = Integer.MAX_VALUE;
                    int last = -1;
                    for (Node n : covered) {
                        Integer i = index.get(n);
                        if (i != null) {
                            first = Math.min(first, i);
                            last = Math.max(last, i);
                        }
                    }
                    for (int i = first + 1; i <= last; i++) {
                        kept[i] = true;
                    }
                }
            }
        }
        List<Unit> out = new ArrayList<>();
        List<Node> current = null;
        for (int i = 0; i < nodes.size(); i++) {
            if (starts[i] && !kept[i] || current == null) {
                Node first = nodes.get(i);
                Breaks.Break marker = useMarkers ? Breaks.of(MarkdownFile.attributesOf(first)) : null;
                current = new ArrayList<>();
                out.add(new Unit(current, -1, marker, i > 0 && marker != Breaks.Break.AVOID, NodeInfo.line(first)));
            }
            current.add(nodes.get(i));
        }
        return out.stream().map(u -> new Unit(List.copyOf(u.nodes()), u.item(), u.marker(), u.breakable(), u.line()))
                .toList();
    }

    /**
     * The node to repeat at the top of a continuation starting at unit {@code from}: the last
     * {@code repeat} match that starts before it, unless that unit starts with a match itself.
     * Only select and rest boxes repeat; null when there's nothing to repeat.
     */
    public static Node repeat(Page.PlacedBox placed, Layout.Split split, List<Unit> units, int from) {
        if (split.repeatEvaluator() == null || placed.box().kind() == Layout.Kind.EACH || from <= 0
                || from >= units.size()) {
            return null;
        }
        List<Node> nodes = placed.nodes();
        AstView view = view(nodes.getFirst(), placed.prefer());
        if (view == null) {
            return null;
        }
        Map<Node, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        int breakAt = index.get(units.get(from).nodes().getFirst());
        Node found = null;
        int foundAt = -1;
        for (Element e : view.match(split.repeatEvaluator(), null, List.of(view.root()))) {
            List<Node> n = view.nodes(List.of(e));
            Integer i = n.isEmpty() ? null : index.get(n.getFirst());
            if (i == null) {
                continue;
            }
            if (i == breakAt) {
                return null; // the continuation starts with its own
            }
            if (i < breakAt && i > foundAt) {
                found = n.getFirst();
                foundAt = i;
            }
        }
        return found;
    }

    /** True if {@code node} begins an element matching {@code between} that lies wholly in the box. */
    private static boolean startsMatch(AstView view, Node node, Evaluator between, Map<Node, Integer> index) {
        for (Element x = view.element(node); x != null; x = x.parent()) {
            if (x.is(between)) {
                List<Node> covered = view.nodes(List.of(x));
                return !covered.isEmpty() && covered.stream().allMatch(index::containsKey);
            }
            Element parent = x.parent();
            if (parent == null || parent.firstElementChild() != x) {
                return false;
            }
        }
        return false;
    }

    private static final Map<MarkdownFile, Map<Variants.Variant, AstView>> VIEWS = new java.util.WeakHashMap<>();

    /** The selector view of the file {@code node} is in. */
    private static synchronized AstView view(Node node, Variants.Variant prefer) {
        MarkdownFile file = null;
        for (Node p = node; p != null; p = p.getParent()) {
            if (p instanceof MarkdownFile f) {
                file = f;
            }
        }
        if (file == null) {
            return null;
        }
        MarkdownFile f = file;
        return VIEWS.computeIfAbsent(f, k -> new HashMap<>()).computeIfAbsent(prefer, v -> new AstView(f, v));
    }
}
