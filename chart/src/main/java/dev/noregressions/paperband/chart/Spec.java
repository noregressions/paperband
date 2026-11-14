package dev.noregressions.paperband.chart;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One mapping of a chart's YAML, read key by key. It remembers which keys were asked for, so
 * {@link #done()} can refuse the ones nothing read: an unknown key is an error, as everywhere
 * else in Paperband.
 */
final class Spec {

    /** A chart that can't be drawn; its message is what the build reports. */
    static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message);
        }
    }

    private final Map<?, ?> map;
    private final String where;
    private final Set<String> read = new HashSet<>();

    Spec(Object value, String where) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new Problem(where + " must be key: value pairs");
        }
        this.map = m;
        this.where = where;
    }

    boolean has(String key) {
        return map.get(key) != null;
    }

    Object get(String key) {
        read.add(key);
        return map.get(key);
    }

    String str(String key) {
        Object v = get(key);
        return v == null ? null : String.valueOf(v);
    }

    String str(String key, String otherwise) {
        String v = str(key);
        return v == null ? otherwise : v;
    }

    String required(String key) {
        String v = str(key);
        if (v == null) {
            throw new Problem(where + " needs '" + key + "'");
        }
        return v;
    }

    Double num(String key) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new Problem(where + ": '" + key + "' must be a number, not " + v);
    }

    double num(String key, double otherwise) {
        Double v = num(key);
        return v == null ? otherwise : v;
    }

    boolean flag(String key) {
        Object v = get(key);
        if (v == null || v instanceof Boolean) {
            return Boolean.TRUE.equals(v);
        }
        throw new Problem(where + ": '" + key + "' must be true or false");
    }

    /** A list of numbers, or a single number as a list of one. */
    List<Double> nums(String key) {
        List<Double> out = new ArrayList<>();
        for (Object v : values(key)) {
            if (!(v instanceof Number n)) {
                throw new Problem(where + ": '" + key + "' must hold numbers, not " + v);
            }
            out.add(n.doubleValue());
        }
        return out;
    }

    /** A list of strings, or a single value as a list of one. */
    List<String> strs(String key) {
        return values(key).stream().map(String::valueOf).toList();
    }

    /** A list of mappings. */
    List<Spec> list(String key) {
        List<Spec> out = new ArrayList<>();
        int i = 1;
        for (Object v : values(key)) {
            out.add(new Spec(v, where + " " + key + " " + i++));
        }
        return out;
    }

    private List<?> values(String key) {
        Object v = get(key);
        return v == null ? List.of() : v instanceof List<?> l ? l : List.of(v);
    }

    /** A moment as seconds since 1970, from a date, a date and time, {@code 2026-08} or {@code 2026}. */
    Double time(String key) {
        Object v = get(key);
        if (v == null) {
            return null;
        }
        try {
            LocalDateTime t = switch (v) {
                case LocalDate d -> d.atStartOfDay();
                case OffsetDateTime o -> o.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                case Number n -> LocalDate.of(n.intValue(), 1, 1).atStartOfDay();
                default -> {
                    String s = String.valueOf(v).strip().replace(' ', 'T');
                    yield s.length() == 7 ? LocalDate.parse(s + "-01").atStartOfDay()
                            : s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s);
                }
            };
            return (double) t.toEpochSecond(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            throw new Problem(where + ": '" + key + "' must be a date such as 2026-10-21 or 2026-10-21 14:00, not " + v);
        }
    }

    double requiredTime(String key) {
        Double t = time(key);
        if (t == null) {
            throw new Problem(where + " needs '" + key + "'");
        }
        return t;
    }

    /** Refuses any key nothing asked for. */
    void done() {
        for (Object k : map.keySet()) {
            if (!read.contains(String.valueOf(k))) {
                throw new Problem(where + ": unknown key '" + k + "'");
            }
        }
    }
}
