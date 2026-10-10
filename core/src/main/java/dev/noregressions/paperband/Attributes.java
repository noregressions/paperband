package dev.noregressions.paperband;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The handles a {@code {! ...}} marker gives an element:
 *
 * <pre>
 * {! step}                    a tag              → class="step"           selector .step
 * {! level=beginner}          a tag with a value → data-level="beginner"  selector [data-level=beginner]
 * {! id=download}             the element's id   → id="download"          selector #download
 * {! title="Quick start"}     quoted values may contain spaces; \" and \\ escape inside quotes
 * </pre>
 *
 * Names start with a letter or {@code _} and use letters, digits, {@code -} and {@code _}.
 *
 * @param id     the element id, or null
 * @param tags   plain tags, in order, no duplicates
 * @param values tags with values, in order ({@code id} isn't one of them)
 */
public record Attributes(String id, List<String> tags, Map<String, String> values) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    public Attributes {
        tags = List.copyOf(tags);
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Thrown for a malformed marker; the message says what's wrong, without location. */
    public static final class MarkerException extends RuntimeException {

        private final int line;

        public MarkerException(String message) {
            this(message, -1);
        }

        public MarkerException(String message, int line) {
            super(message);
            this.line = line;
        }

        /** 1-based line in the file, or -1 if unknown. */
        public int line() {
            return line;
        }

        MarkerException at(int line) {
            return this.line > 0 || line <= 0 ? this : new MarkerException(getMessage(), line);
        }
    }

    /**
     * Index just past the {@code }} closing a marker that starts at {@code start} (the {@code {}),
     * skipping over quoted values; -1 if it isn't closed.
     */
    static int end(CharSequence text, int start) {
        boolean quoted = false;
        for (int i = start + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\' && i + 1 < text.length()) {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == '}' && !quoted) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Parses a whole marker, {@code {! ...}}. */
    static Attributes parseMarker(String marker) {
        if (!marker.startsWith("{!") || !marker.endsWith("}")) {
            throw new MarkerException("expected a {! ...} marker, got \"" + marker + "\"");
        }
        return parse(marker.substring(2, marker.length() - 1), marker);
    }

    /** Parses the inside of a marker; {@code shown} is used in messages. */
    static Attributes parse(String body, String shown) {
        String id = null;
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Map<String, String> values = new LinkedHashMap<>();
        int i = 0;
        int n = body.length();
        while (true) {
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(body.charAt(i)) && body.charAt(i) != '=') {
                i++;
            }
            String name = body.substring(start, i);
            if (!NAME.matcher(name).matches()) {
                throw new MarkerException("\"" + name + "\" in " + shown
                        + " isn't a valid name (start with a letter or _, then letters, digits, - or _)");
            }
            if (i < n && body.charAt(i) == '=') {
                i++;
                StringBuilder value = new StringBuilder();
                if (i < n && body.charAt(i) == '"') {
                    i++;
                    boolean closed = false;
                    while (i < n) {
                        char c = body.charAt(i++);
                        if (c == '\\' && i < n) {
                            value.append(body.charAt(i++));
                        } else if (c == '"') {
                            closed = true;
                            break;
                        } else {
                            value.append(c);
                        }
                    }
                    if (!closed) {
                        throw new MarkerException("unclosed quote in " + shown);
                    }
                } else {
                    while (i < n && !Character.isWhitespace(body.charAt(i))) {
                        value.append(body.charAt(i++));
                    }
                }
                if (i < n && !Character.isWhitespace(body.charAt(i))) {
                    throw new MarkerException("expected a space after the value of \"" + name + "\" in " + shown);
                }
                if (value.isEmpty()) {
                    throw new MarkerException("\"" + name + "=\" has no value in " + shown);
                }
                if (name.equals("id")) {
                    if (id != null) {
                        throw new MarkerException("id is given twice in " + shown);
                    }
                    if (!NAME.matcher(value).matches()) {
                        throw new MarkerException("id \"" + value + "\" in " + shown
                                + " must start with a letter or _, then letters, digits, - or _");
                    }
                    id = value.toString();
                } else if (name.equals("class")) {
                    throw new MarkerException("use plain tags instead of class= in " + shown + " (e.g. {! " + value + "})");
                } else if (values.put(name, value.toString()) != null) {
                    throw new MarkerException("\"" + name + "\" is given twice in " + shown);
                }
            } else {
                if (name.equals("id")) {
                    throw new MarkerException("id needs a value in " + shown + " (e.g. {! id=download})");
                }
                tags.add(name);
            }
        }
        if (id == null && tags.isEmpty() && values.isEmpty()) {
            throw new MarkerException("empty marker " + shown);
        }
        return new Attributes(id, new ArrayList<>(tags), values);
    }

    /** Both sets of handles together; conflicting ids or values are an error. */
    Attributes merge(Attributes other) {
        if (id != null && other.id != null && !id.equals(other.id)) {
            throw new MarkerException("two different ids for one element: " + id + " and " + other.id);
        }
        LinkedHashSet<String> t = new LinkedHashSet<>(tags);
        t.addAll(other.tags);
        Map<String, String> v = new LinkedHashMap<>(values);
        for (var e : other.values.entrySet()) {
            String old = v.put(e.getKey(), e.getValue());
            if (old != null && !old.equals(e.getValue())) {
                throw new MarkerException("two different values for \"" + e.getKey() + "\" on one element: "
                        + old + " and " + e.getValue());
            }
        }
        return new Attributes(id != null ? id : other.id, new ArrayList<>(t), v);
    }

    /** As HTML attributes: {@code id}, {@code class} (the tags) and {@code data-*} (the values). */
    public Map<String, String> html() {
        Map<String, String> out = new LinkedHashMap<>();
        if (id != null) {
            out.put("id", id);
        }
        if (!tags.isEmpty()) {
            out.put("class", String.join(" ", tags));
        }
        values.forEach((k, v) -> out.put("data-" + k, v));
        return out;
    }

    /** The canonical marker, e.g. {@code {! step optional id=download level=beginner}}. */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<>(tags);
        if (id != null) {
            parts.add("id=" + id);
        }
        values.forEach((k, v) -> parts.add(k + "=" + (v.matches("[^\\s\"}\\\\]+") ? v
                : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")));
        return "{! " + String.join(" ", parts) + "}";
    }
}
