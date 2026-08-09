package dev.noregressions.paperband.predicate;

/** Thrown when a predicate fails to parse or evaluate. */
public class PredicateException extends RuntimeException {

    public PredicateException(String message) {
        super(message);
    }

    public PredicateException(String message, Throwable cause) {
        super(message, cause);
    }
}
