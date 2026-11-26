package dev.noregressions.paperband;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Facts: the figures a document quotes, each written once in the data file {@code facts}
 * ({@code data/facts.yml}) and used by name wherever it appears, so a number that changes is
 * changed in one place.
 * <pre>
 * dependency-lag:
 *   value: 278            # a number or a text; the only key a fact needs
 *   unit: days            # written after the value: 278 days, 8.7%
 *   prefix: $             # written before it: $4.99
 *   text: 278 days        # or the whole wording, when value and unit don't give it
 *   was: 215              # the value it is compared with, written the same way
 *   good: down            # which direction is the good one: up or down
 *   label: Median dependency lag
 *   source: Datadog
 *   url: https://www.datadoghq.com/state-of-devsecops
 *   kind: vendor          # where it comes from: vendor, self-reported, derived...
 *   asof: 2026-11-12
 * </pre>
 * A fact that is only a value can be written as one: {@code cves-2026: 93081}.
 * <p>
 * In a template, {@code {{ fact("dependency-lag") }}} is the fact as it is written, "278 days",
 * and {@code {{ fact("dependency-lag").source }}} one of its keys. Unlike {@code data.facts.x},
 * which is empty for a name that doesn't exist, {@code fact} stops the build for a name it doesn't
 * know: a figure never silently goes missing. {@code .tile} is the fact as a {@code tiles} block
 * takes it (see the chart module).
 */
public final class Facts extends AbstractExtension {

    /** The data file's name: {@code data/facts.yml} is {@code data.facts}. */
    public static final String DATA = "facts";

    private static final Set<String> KEYS = Set.of("value", "unit", "prefix", "text", "was", "good", "label",
            "source", "url", "kind", "asof", "note");

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("fact", new Function() {
            @Override
            public List<String> getArgumentNames() {
                return List.of("name");
            }

            @Override
            public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int line) {
                Object data = context.getVariable("data");
                try {
                    return of(data instanceof Map<?, ?> m ? m.get(DATA) : null, String.valueOf(args.get("name")));
                } catch (IllegalArgumentException e) { // as a template error, so the build names the file and line
                    throw new io.pebbletemplates.pebble.error.PebbleException(null, e.getMessage(), line, self.getName());
                }
            }
        });
    }

    /**
     * The fact called {@code name} among {@code facts}, the content of the facts data file.
     *
     * @throws IllegalArgumentException if there is no such fact, or it has a key a fact doesn't have
     */
    public static Fact of(Object facts, String name) {
        if (!(facts instanceof Map<?, ?> all)) {
            throw new IllegalArgumentException("fact(\"" + name + "\"): there is no data file named " + DATA
                    + " (data/" + DATA + ".yml) holding name: fact pairs");
        }
        Object raw = all.get(name);
        if (raw == null) {
            throw new IllegalArgumentException("fact(\"" + name + "\"): no fact has that name" + nearest(all, name));
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!KEYS.contains(key)) {
                    throw new IllegalArgumentException("fact \"" + name + "\": unknown key '" + key + "'");
                }
                keys.put(key, e.getValue());
            }
            if (keys.get("value") == null && keys.get("text") == null) {
                throw new IllegalArgumentException("fact \"" + name + "\" needs a 'value' or a 'text'");
            }
        } else {
            keys.put("value", raw);
        }
        Object good = keys.get("good");
        if (good != null && !good.equals("up") && !good.equals("down")) {
            throw new IllegalArgumentException("fact \"" + name + "\": 'good' is up or down, not " + good);
        }
        return new Fact(name, keys);
    }

    /** A hint for a mistyped name: the known name that starts the same way, if one does. */
    private static String nearest(Map<?, ?> all, String name) {
        String stem = name.length() > 4 ? name.substring(0, 4) : name;
        for (Object k : all.keySet()) {
            if (String.valueOf(k).startsWith(stem)) {
                return " (did you mean \"" + k + "\"?)";
            }
        }
        return "";
    }

    /** One fact. In a template it prints as it is written ({@link #toString()}); its keys are properties. */
    public static final class Fact {

        private final String name;
        private final Map<String, Object> keys;

        private Fact(String name, Map<String, Object> keys) {
            this.name = name;
            this.keys = keys;
        }

        public String getName() {
            return name;
        }

        /** The value as the data file has it: a number stays a number, so templates can calculate with it. */
        public Object getValue() {
            return keys.get("value");
        }

        /** The fact as it is written: its {@code text}, or its value with its prefix and unit. */
        public String getText() {
            Object text = keys.get("text");
            return text != null ? String.valueOf(text) : written(keys.get("value"));
        }

        /** The value it is compared with, written the same way as the fact; null if it has none. */
        public String getWas() {
            Object was = keys.get("was");
            return was == null ? null : written(was);
        }

        /** {@code up}, {@code down} or {@code flat} against {@code was}; null unless both are numbers. */
        public String getTrend() {
            if (keys.get("value") instanceof Number now && keys.get("was") instanceof Number then) {
                int c = Double.compare(now.doubleValue(), then.doubleValue());
                return c > 0 ? "up" : c < 0 ? "down" : "flat";
            }
            return null;
        }

        public String getGood() {
            return str("good");
        }

        public String getUnit() {
            return str("unit");
        }

        public String getLabel() {
            return str("label");
        }

        public String getSource() {
            return str("source");
        }

        public String getUrl() {
            return str("url");
        }

        public String getKind() {
            return str("kind");
        }

        public Object getAsof() {
            return keys.get("asof");
        }

        public String getNote() {
            return str("note");
        }

        /**
         * The fact as one entry of a {@code tiles} block, a YAML mapping on one line:
         * {@code - { fact: {{ fact("dependency-lag").tile }}, tag: 2027 watch }}.
         */
        public String getTile() {
            Map<String, String> tile = new LinkedHashMap<>();
            tile.put("value", getText());
            tile.put("label", getLabel());
            String source = getSource();
            tile.put("source", source != null && getUrl() != null ? "[" + source + "](" + getUrl() + ")" : source);
            tile.put("kind", getKind());
            tile.put("was", getWas());
            tile.put("trend", getTrend());
            tile.put("good", getGood());
            StringBuilder sb = new StringBuilder("{");
            tile.forEach((k, v) -> {
                if (v != null) {
                    sb.append(sb.length() > 1 ? ", " : "").append(k).append(": \"")
                            .append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
            });
            return sb.append('}').toString();
        }

        @Override
        public String toString() {
            return getText();
        }

        private String str(String key) {
            Object v = keys.get(key);
            return v == null ? null : String.valueOf(v);
        }

        /** A value with the fact's prefix and unit: 93,081, $4.99, 8.7%, 278 days. */
        private String written(Object value) {
            String unit = str("unit"), prefix = str("prefix");
            String number = value instanceof Number n ? number(n) : String.valueOf(value);
            return (prefix == null ? "" : prefix) + number
                    + (unit == null ? "" : (unit.equals("%") || unit.equals("×") ? "" : " ") + unit);
        }

        private static String number(Number n) {
            double v = n.doubleValue();
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.format(Locale.ROOT, "%,d", (long) v)
                    : new BigDecimal(String.valueOf(n)).stripTrailingZeros().toPlainString();
        }
    }
}
