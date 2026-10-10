package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@code where:} filter on front matter. Every condition must hold.
 *
 * <pre>
 * where:
 *   draft: false                    # equals
 *   author.name: Steve              # dotted keys reach into nested mappings
 *   chapter: { gte: 2, lt: 10 }     # operators: eq ne gt gte lt lte in contains exists
 *   tags: { contains: java }
 *   status: { in: [final, review] }
 *   summary: { exists: true }
 * </pre>
 *
 * A missing key never equals anything, so {@code ne} is true for it and {@code gt}/{@code lt} are false.
 */
final class Where {

    enum Op { EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS, EXISTS }

    record Condition(String key, Op op, Object value) {
    }

    private final String context;
    private final List<Condition> conditions;

    private Where(String context, List<Condition> conditions) {
        this.context = context;
        this.conditions = conditions;
    }

    static Where parse(YamlSpec spec) throws ConfigException {
        List<Condition> conditions = new ArrayList<>();
        for (String key : spec.keys()) {
            Object value = spec.raw(key);
            if (!(value instanceof Map<?, ?> ops)) {
                if (value instanceof List) {
                    throw spec.error(key, "a list isn't a value to match; use { in: [...] } or { contains: ... }");
                }
                conditions.add(new Condition(key, Op.EQ, value));
                continue;
            }
            if (ops.isEmpty()) {
                throw spec.error(key, "no operator given");
            }
            for (var e : ops.entrySet()) {
                Op op = op(spec, key, String.valueOf(e.getKey()));
                Object operand = e.getValue();
                if (op == Op.IN && !(operand instanceof List)) {
                    throw spec.error(key, "\"in\" expects a list, e.g. { in: [a, b] }");
                }
                if (op == Op.EXISTS && !(operand instanceof Boolean)) {
                    throw spec.error(key, "\"exists\" expects true or false");
                }
                if (operand instanceof Map || (operand instanceof List && op != Op.IN)) {
                    throw spec.error(key, "\"" + e.getKey() + "\" expects a single value");
                }
                conditions.add(new Condition(key, op, operand));
            }
        }
        return new Where(spec.context(), List.copyOf(conditions));
    }

    private static Op op(YamlSpec spec, String key, String name) throws ConfigException {
        try {
            return Op.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw spec.error(key, "unknown operator \"" + name + "\"; expected one of eq, ne, gt, gte, lt, lte, in, contains, exists");
        }
    }

    List<Condition> conditions() {
        return conditions;
    }

    /** @throws ConfigException if a comparison mixes kinds of value (e.g. a number with text) */
    boolean test(Map<String, Object> page, String file) throws ConfigException {
        for (Condition c : conditions) {
            Object actual = Values.lookup(page, c.key());
            try {
                if (!holds(c, actual)) {
                    return false;
                }
            } catch (IllegalArgumentException e) {
                throw new ConfigException(context + "." + c.key() + ": " + e.getMessage() + " (in " + file + ")");
            }
        }
        return true;
    }

    private static boolean holds(Condition c, Object actual) {
        Object v = c.value();
        return switch (c.op()) {
            case EQ -> actual != null && Values.same(actual, v);
            case NE -> actual == null || !Values.same(actual, v);
            case GT -> actual != null && Values.compare(actual, v) > 0;
            case GTE -> actual != null && Values.compare(actual, v) >= 0;
            case LT -> actual != null && Values.compare(actual, v) < 0;
            case LTE -> actual != null && Values.compare(actual, v) <= 0;
            case IN -> actual != null && ((List<?>) v).stream().anyMatch(x -> Values.same(actual, x));
            case CONTAINS -> actual instanceof List<?> list
                    ? list.stream().anyMatch(x -> Values.same(x, v))
                    : actual instanceof String s && s.contains(String.valueOf(v));
            case EXISTS -> (actual != null) == (Boolean) v;
        };
    }
}
