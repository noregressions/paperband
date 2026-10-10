package dev.noregressions.paperband.layout;

import java.io.IOException;

/** A problem in a manifest or layout file. The message says where, e.g. {@code manifest.yml: sequence[2].sort: ...}. */
public class ConfigException extends IOException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
