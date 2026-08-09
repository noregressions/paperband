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

    private final Path sourceFile;

    public IncludeException(String message, Path sourceFile) {
        super(message);
        this.sourceFile = sourceFile;
    }

    public IncludeException(String message, Path sourceFile, Throwable cause) {
        super(message, cause);
        this.sourceFile = sourceFile;
    }

    /** Path of the markdown file that contained the offending directive; may be null. */
    public Path sourceFile() {
        return sourceFile;
    }
}
