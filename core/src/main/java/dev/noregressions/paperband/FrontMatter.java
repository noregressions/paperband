package dev.noregressions.paperband;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * YAML front matter: a block at the very top of a file, opened by {@code ---} and closed by
 * {@code ---} or {@code ...}, parsed as YAML into a map with real types: strings, numbers,
 * booleans, lists, nested maps, and dates ({@link LocalDate}, or {@link OffsetDateTime} in UTC
 * when a time is given).
 */
public final class FrontMatter {

    private static final Pattern BLOCK = Pattern.compile(
            "\\A---[ \\t]*\\r?\\n(.*?\\r?\\n)??(?:---|\\.\\.\\.)[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);

    /**
     * A file split into its front matter and body.
     *
     * @param block the front matter exactly as written, delimiters included ("" if none)
     * @param body  everything after it
     * @param data  the parsed YAML (empty if none)
     */
    public record Split(String block, String body, Map<String, Object> data) {

        /** The body, preceded by one blank line per front matter line, so body line numbers match the file's. */
        public String paddedBody() {
            return "\n".repeat((int) block.chars().filter(c -> c == '\n').count()) + body;
        }
    }

    private FrontMatter() {
    }

    /** @throws IOException if the front matter isn't valid YAML or isn't a mapping; the message names {@code source} */
    public static Split split(Object source, String text) throws IOException {
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            return new Split("", text, Map.of());
        }
        String yaml = m.group(1) == null ? "" : m.group(1);
        try {
            return new Split(m.group(), text.substring(m.end()), parseMapping(yaml));
        } catch (YAMLException | IllegalArgumentException e) {
            throw new IOException("invalid front matter in " + source + ": " + e.getMessage(), e);
        }
    }

    /** Parses a YAML document that must be a mapping (or empty). */
    static Map<String, Object> parseMapping(String yaml) {
        Object value = parse(yaml);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected key: value pairs, got " + describeType(value));
        }
        return stringKeys(map);
    }

    /**
     * Parses any YAML (or JSON) document, safely (no arbitrary Java types), converting dates
     * to java.time and mapping keys to strings.
     */
    public static Object parseYaml(String yaml) {
        return parse(yaml);
    }

    static Object parse(String yaml) {
        return normalise(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
    }

    private static Object normalise(Object value) {
        return switch (value) {
            case Map<?, ?> map -> stringKeys(map);
            case List<?> list -> Collections.unmodifiableList(new ArrayList<>(list.stream().map(FrontMatter::normalise).toList()));
            case Date date -> {
                OffsetDateTime t = date.toInstant().atOffset(ZoneOffset.UTC);
                yield t.toLocalTime().toNanoOfDay() == 0 ? t.toLocalDate() : t;
            }
            case null, default -> value;
        };
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), normalise(v)));
        return Collections.unmodifiableMap(out);
    }

    /** Compact display form: {@code [a, b]} for lists, {@code {k: v}} for maps. */
    public static String format(Object value) {
        return switch (value) {
            case Map<?, ?> map -> map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + format(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
            case List<?> list -> list.stream().map(FrontMatter::format).collect(Collectors.joining(", ", "[", "]"));
            case null -> "null";
            default -> value.toString();
        };
    }

    private static String describeType(Object value) {
        return value instanceof List ? "a list" : "a single value (" + format(value) + ")";
    }
}
