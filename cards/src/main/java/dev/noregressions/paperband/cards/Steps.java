package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.PostProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Numbers {@code {!step}} by position: the first stepped child of a parent is
 * 1, the next is 2, and so on, whatever kind of element each one is.
 *
 * <p>A number that is derived, never written, stays right when steps are
 * added, removed or reordered -- the same reason chapter numbers are derived.
 * The count is per parent, so each parent heading's steps start again at 1:
 *
 * <pre>
 * ## Install
 * ### Get it {!step}      1
 * ### Build it {!step}    2
 * ## Configure
 * ### Edit {!step}        1
 * </pre>
 *
 * <p>A heading's step belongs to its {@link Sections.Section}, so it's the
 * section that is counted among its siblings, and paragraphs, list items or
 * fences under the same parent count in the same sequence. Non-stepped
 * siblings in between don't reset the count.
 *
 * <p>A {@code {!step}} marker in the text belongs to the block around it -- a
 * heading's section, a list item, a paragraph, a table cell -- and is replaced
 * with {@link Block#stepLabel "Step N"} for that block's number. The block also
 * carries the number as {@code data-paperband-step}, and so does one numbered
 * with {@code !step} in an attribute group, which writes no text.
 *
 * <p>Runs after {@link Sections}, which creates the parents headings imply.
 */
final class Steps implements PostProcessor {

    private static final String STEP = "step";

    private final AttributeSyntax syntax;

    Steps(AttributeSyntax syntax) {
        this.syntax = syntax;
    }

    @Override
    public Node process(Node document) {
        List<Directives.Marker> markers = markers(document);
        // Each marker numbers the block it sits in, so register that block first.
        for (Directives.Marker marker : markers) {
            Node owner = owner(marker);
            if (!stepped(owner)) syntax.setDirective(owner, STEP, "");
        }
        number(document);
        for (Directives.Marker marker : markers) {
            marker.setText(Block.stepLabel(syntax.directives(owner(marker)).get(STEP)));
        }
        return document;
    }

    private static List<Directives.Marker> markers(Node document) {
        List<Directives.Marker> out = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                if (node instanceof Directives.Marker m && m.name().equals(STEP)) out.add(m);
                visitChildren(node);
            }
        });
        return out;
    }

    /**
     * The block a marker numbers: the nearest enclosing block, except that a
     * list item's first paragraph means the list item, whose siblings are the
     * other items. A heading means its section, which {@link #number} counts.
     */
    private static Node owner(Directives.Marker marker) {
        Node node = marker.getParent();
        while (node != null && !(node instanceof org.commonmark.node.Block)) node = node.getParent();
        if (node instanceof Paragraph p && p.getParent() instanceof ListItem item
                && item.getFirstChild() == p) {
            return item;
        }
        return node;
    }

    private void number(Node parent) {
        int count = 0;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            Node owner = child;
            if (child instanceof Sections.Section section) {
                owner = section.heading();
            } else if (child instanceof Heading heading && isSectionHeading(heading)) {
                owner = null;   // counted as its section, one level up
            } else if (child instanceof Heading heading && stepped(heading)) {
                // Not in a section: the heading consumed as the card's title.
                throw new IllegalArgumentException("the card title '" + Sections.text(heading)
                        + "' can't carry {!step}: it names the card rather than starting a"
                        + " section. Put the step on a heading below it, or give the card a"
                        + " title: in its frontmatter so this heading becomes a section.");
            }
            if (owner != null && stepped(owner)) {
                syntax.setDirective(owner, STEP, String.valueOf(++count));
            }
            number(child);
        }
    }

    private boolean stepped(Node node) {
        return syntax.directives(node).containsKey(STEP);
    }

    private static boolean isSectionHeading(Heading heading) {
        return heading.getParent() instanceof Sections.Section s && s.getFirstChild() == heading;
    }
}
