package dev.noregressions.paperband.cards;

import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.PostProcessor;

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
 * siblings in between don't reset the count. The number replaces the
 * directive's empty value and renders as {@code data-paperband-step}; nothing
 * is written into the text, so each output decides how to show it.
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
        number(document);
        return document;
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
                        + " section. Put the step on a heading below it.");
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
