package dev.noregressions.paperband.layout;

/**
 * A card the book holds that its templates never printed.
 *
 * <p>A content failure rather than a crash, like {@link CardLinkException}:
 * the book rendered, but a template left out {@code id="card-<id>"}, so links,
 * the contents and the bookmarks would point at nothing. See {@link Printed}.
 */
public class UnprintedCardException extends LayoutException {

    public UnprintedCardException(String message) {
        super(message);
    }
}
