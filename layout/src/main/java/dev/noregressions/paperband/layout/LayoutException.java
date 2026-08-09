package dev.noregressions.paperband.layout;

/** Thrown when a layout cannot be resolved or rendered. */
public class LayoutException extends RuntimeException {

    public LayoutException(String message) {
        super(message);
    }

    public LayoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
