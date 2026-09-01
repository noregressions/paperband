package dev.noregressions.paperband.block;

/**
 * A block a {@link BlockRenderer} claimed and could not render.
 *
 * <p>Thrown, not swallowed: the alternative is a book that builds green with a
 * diagram missing from page 40, which nobody notices until it is printed. The
 * message should name what is wrong with the block, and the caller adds which
 * card it was in.
 */
public class BlockRenderException extends RuntimeException {

    public BlockRenderException(String message) {
        super(message);
    }

    public BlockRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
