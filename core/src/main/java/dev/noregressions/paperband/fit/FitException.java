package dev.noregressions.paperband.fit;

import dev.noregressions.paperband.layout.ConfigException;

import java.nio.file.Path;
import java.util.List;

/** Thrown when a measured output doesn't fit; the fit report pages say where. */
public final class FitException extends ConfigException {

    private final List<Problem> problems;

    public FitException(String message, List<Problem> problems) {
        super(message);
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    /** E.g. {@code output handbook doesn't fit (2 problems; see target/handbook.fit.html):} and one line per problem. */
    public static String describe(String output, List<Problem> problems, Path report) {
        StringBuilder sb = new StringBuilder("output " + output + " doesn't fit (" + problems.size() + " problem(s)"
                + (report == null ? "" : "; see " + report) + "):");
        for (Problem p : problems) {
            sb.append("\n  ").append(p.message());
            if (!p.tried().isEmpty()) {
                sb.append("\n    tried: ").append(String.join("; ", p.tried()));
            }
        }
        return sb.toString();
    }
}
