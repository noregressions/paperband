package dev.noregressions.paperband.config;

/** Thrown when a {@code paperband.yaml} cannot be parsed or is malformed. */
public class ConfigParseException extends RuntimeException {

    /**
     * Creates a new exception with the given message.
     * @param message the error message
     */
    public ConfigParseException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     * @param message the error message
     * @param cause the cause
     */
    public ConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
