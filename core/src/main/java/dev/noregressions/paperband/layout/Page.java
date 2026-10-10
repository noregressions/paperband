package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.Variants;
import org.commonmark.node.Node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One output page, independent of output format: which layout made it, its geometry, and the
 * AST nodes placed in each box. Renderers (HTML now; PDF and PPTX later) draw it.
 *
 * @param id     unique anchor for the page, e.g. {@code docs-intro-md}
 * @param source the source file, or null for a generated page
 * @param grid   which of the layout's {@code grids} the page uses, or null for its own grid
 */
public record Page(String id, Layout layout, Path source, PageSpec size, List<PlacedBox> boxes, String grid) {

    public Page(String id, Layout layout, Path source, PageSpec size, List<PlacedBox> boxes) {
        this(id, layout, source, size, boxes, null);
    }

    public Page withBoxes(List<PlacedBox> boxes) {
        return new Page(id, layout, source, size, boxes, grid);
    }

    public Page withGrid(String grid) {
        return new Page(id, layout, source, size, boxes, grid);
    }

    /**
     * A layout box and what goes in it: the AST nodes selected for it, in order; for a source box
     * the file's text; for a box holding layouts, the filled inner layouts (one per match for
     * {@code each}).
     *
     * @param prefer the variant shown of each variants group it shows whole, or null for the longest
     * @param omit   nodes in it to leave out (by identity): {@code {! optional}} blocks a remedy dropped
     * @param part   for a box that splits across pages, this page's part of it; else null
     */
    public record PlacedBox(Layout.Box box, List<Node> nodes, String source, List<Nested> nested,
                            Variants.Variant prefer, Set<Node> omit, Part part) {

        public PlacedBox(Layout.Box box, List<Node> nodes) {
            this(box, nodes, null, List.of(), null, Set.of(), null);
        }

        public PlacedBox(Layout.Box box, List<Node> nodes, String source) {
            this(box, nodes, source, List.of(), null, Set.of(), null);
        }

        public PlacedBox(Layout.Box box, List<Node> nodes, String source, List<Nested> nested) {
            this(box, nodes, source, nested, null, Set.of(), null);
        }

        public PlacedBox preferring(Variants.Variant variant) {
            return new PlacedBox(box, nodes, source, nested, variant, omit, part);
        }

        public PlacedBox omitting(Set<Node> nodes) {
            return new PlacedBox(box, this.nodes, source, nested, prefer, nodes, part);
        }

        public PlacedBox withNested(List<Nested> nested) {
            return new PlacedBox(box, nodes, source, nested, prefer, omit, part);
        }

        /** This box showing only some of its units: {@code nodes} (or {@code nested} items), as {@code part}. */
        public PlacedBox splitAs(List<Node> nodes, List<Nested> nested, Part part) {
            return new PlacedBox(box, nodes, source, nested, prefer, omit, part);
        }
    }

    /**
     * One page's part of a box that splits across pages.
     *
     * @param unitStarts the first node of each unit shown, marked in the HTML so the browser can
     *                   measure where each unit starts (an each box's items are marked instead)
     * @param repeat     a node repeated at the top, from before the break (see {@code split.repeat}), or null
     * @param label      shown after the repeated node, e.g. {@code continued}; or null
     */
    public record Part(Set<Node> unitStarts, Node repeat, String label) {
    }

    /**
     * An inner layout and its filled boxes.
     *
     * @param match the nodes the inner layout was given (an each box's match), in order
     */
    public record Nested(Layout layout, List<PlacedBox> boxes, List<Node> match) {

        public Nested(Layout layout, List<PlacedBox> boxes) {
            this(layout, boxes, List.of());
        }
    }
}
