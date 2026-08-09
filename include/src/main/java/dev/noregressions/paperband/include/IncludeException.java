package dev.noregressions.paperband.include;

import java.nio.file.Path;

/**
 * Unchecked exception raised when an include directive cannot be processed
 * (unknown provider, unknown processor, or any wrapped
 * {@link ContentResolutionException}).
 *
 * <p>Carries the source markdown file path so callers can surface a helpful
 * error message without having to thread the path through their stack.
 */
public class IncludeException extends RuntimeException {

    /** The source markdown file that contains the directive. */
    private final Path sourceFile;

    /**
     * Creates a new include exception.
     * @param message the error message
     * @param sourceFile the source markdown file
     */
    public IncludeException(String message, Path sourceFile) {
        super(message);
        this.sourceFile = sourceFile;
    }

    /**
     * Creates a new include exception with a cause.
     * @param message the error message
     * @param sourceFile the source markdown file
     * @param cause the cause
     */
    public IncludeException(String message, Path sourceFile, Throwable cause) {
        super(message, cause);
        this.sourceFile = sourceFile;
    }

    /** Path of the markdown file that contained the offending directive; may be null. */
    public Path sourceFile() {
        return sourceFile;
    }
}
