package dev.noregressions.paperband.layout;

/**
 * A {@code card:} link that points at nothing.
 *
 * <p>A content failure rather than a crash, in the same family as
 * {@link SlotPlacementException}: the build was well-formed and the book
 * rendered, but a cross-reference in the prose names a card (or a block) that
 * isn't there. The goals map it onto Maven's build-failure kind, so it reads
 * like the page-budget check rather than like a bug in the plugin.
 */
public class CardLinkException extends LayoutException {

    public CardLinkException(String message) {
        super(message);
    }
}
