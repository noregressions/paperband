package dev.noregressions.paperband.config;

/** Thrown when a {@code pagewright.yaml} cannot be parsed or is malformed. */
public class ConfigParseException extends RuntimeException {

    public ConfigParseException(String message) {
        super(message);
    }

    public ConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
