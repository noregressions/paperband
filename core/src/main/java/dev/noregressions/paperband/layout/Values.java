package dev.noregressions.paperband.layout;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Comparing typed front matter values, as used by {@code where} and {@code sort}. */
final class Values {

    private Values() {
    }

    /** Looks up a dotted key ({@code author.name}) in front matter; null if any part is missing. */
    static Object lookup(Map<String, Object> page, String dottedKey) {
        Object current = page;
        for (String part : dottedKey.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(part);
        }
        return current;
    }

    /** Equality, treating numbers of different types ({@code 3} and {@code 3.0}) as equal. */
    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        return Objects.equals(a, b);
    }

    /**
     * Orders two values of the same kind: numbers, strings, booleans, dates or date-times.
     *
     * @throws IllegalArgumentException if they are different kinds or can't be ordered
     */
    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y));
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.compareTo(y);
        }
        if (a instanceof LocalDate x && b instanceof LocalDate y) {
            return x.compareTo(y);
        }
        if (a instanceof OffsetDateTime x && b instanceof OffsetDateTime y) {
            return x.compareTo(y);
        }
        throw new IllegalArgumentException("can't compare " + kind(a) + " " + show(a) + " with " + kind(b) + " " + show(b));
    }

    static String kind(Object v) {
        return switch (v) {
            case null -> "nothing";
            case Number n -> "number";
            case String s -> "text";
            case Boolean b -> "boolean";
            case LocalDate d -> "date";
            case OffsetDateTime t -> "date-time";
            case List<?> l -> "list";
            case Map<?, ?> m -> "mapping";
            default -> v.getClass().getSimpleName();
        };
    }

    static String show(Object v) {
        return v instanceof String ? "\"" + v + "\"" : String.valueOf(v);
    }

    private static BigDecimal decimal(Number n) {
        return new BigDecimal(n.toString());
    }
}
