package dev.noregressions.paperband.predicate;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.StringLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates Pebble expressions to booleans. Used for {@code when="..."} on
 * fenced divs and {@code where: "..."} in collection declarations. Same
 * expression syntax as templates, deliberately constrained to comparators,
 * boolean ops, membership tests, and {@code defined()}.
 *
 * <p>Implementation note: Pebble doesn't expose a raw expression parser, so
 * predicates are wrapped in a tiny template ({@code {{ expr }}}) and the
 * stringified result is parsed as a boolean. Compiled templates are cached
 * by predicate string for repeated evaluation.
 *
 * <p>Thread-safe.
 */
public final class PredicateEvaluator {

    private final PebbleEngine engine;
    private final Map<String, PebbleTemplate> cache = new ConcurrentHashMap<>();

    public PredicateEvaluator() {
        this.engine = new PebbleEngine.Builder()
                .loader(new StringLoader())
                .strictVariables(false)
                .autoEscaping(false)
                .build();
    }

    /**
     * Evaluate {@code predicate} against {@code context}.
     *
     * @return {@code true} if the expression evaluates truthy; {@code false} otherwise
     * @throws PredicateException if parsing or evaluation fails
     */
    public boolean evaluate(String predicate, Map<String, Object> context) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        try {
            PebbleTemplate tmpl = cache.computeIfAbsent(predicate,
                    p -> engine.getTemplate("{{ " + p + " }}"));
            StringWriter sw = new StringWriter();
            tmpl.evaluate(sw, context == null ? Map.of() : context);
            String result = sw.toString().trim();
            return parseTruthy(result);
        } catch (RuntimeException e) {
            throw new PredicateException(
                    "Failed to evaluate predicate: " + predicate
                    + " (" + e.getMessage() + ")", e);
        } catch (Exception e) {
            throw new PredicateException(
                    "Failed to evaluate predicate: " + predicate, e);
        }
    }

    private static boolean parseTruthy(String s) {
        if (s == null || s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false")
                || s.equalsIgnoreCase("null")) {
            return false;
        }
        return true;
    }
}
