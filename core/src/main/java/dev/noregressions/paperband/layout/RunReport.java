package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.List;

/** Warnings collected during a build: things that didn't stop it but shouldn't go unnoticed. */
public final class RunReport {

    private final List<String> warnings = new ArrayList<>();

    public void warn(String message) {
        warnings.add(message);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
