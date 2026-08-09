package dev.noregressions.paperband.include;

/**
 * Raised when a {@link ContentProvider} fails to resolve a reference.
 * The message should be informative enough for the user to find and fix
 * the offending directive: include the provider name, the reference, and
 * what went wrong.
 */
public class ContentResolutionException extends Exception {

    public ContentResolutionException(String message) {
        super(message);
    }

    public ContentResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
