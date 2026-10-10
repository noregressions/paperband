package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Confines a layout's stylesheet to that layout's content by prefixing every selector with the
 * layout's class, so layouts can't restyle each other or the rest of the page:
 *
 * <pre>
 * h1 { ... }                →  .layout-hero h1 { ... }
 * &amp; > .box-title { ... }    →  .layout-hero > .box-title { ... }     (&amp; or :scope: the layout itself)
 * &amp;.grid { ... }           →  .layout-hero.grid { ... }
 * @media (...) { p { } }    →  @media (...) { .layout-hero p { } }   (also @supports, @container, @layer)
 * </pre>
 *
 * {@code @font-face}, {@code @keyframes}, {@code @property} and {@code @page} are kept as written.
 * {@code html}, {@code body} and {@code :root} can't be styled from a layout (use the manifest's
 * stylesheets), and {@code @import} isn't allowed because stylesheets are inlined. Plain CSS
 * comes out, which is what PDF rendering needs too.
 */
final class CssScoper {

    private final String css;
    private final String prefix;
    private final String source;
    private int pos;

    private CssScoper(String css, String prefix, String source) {
        this.css = css;
        this.prefix = prefix;
        this.source = source;
    }

    /**
     * @param prefix the selector everything is confined to, e.g. {@code .layout-hero}
     * @param source the stylesheet's name, for messages
     */
    static String scope(String css, String prefix, String source) throws ConfigException {
        return new CssScoper(css, prefix, source).rules(false);
    }

    /** Reads rules up to the end of input (or a closing brace, when {@code nested}). */
    private String rules(boolean nested) throws ConfigException {
        StringBuilder out = new StringBuilder();
        while (true) {
            skipSpaceAndComments();
            if (pos >= css.length()) {
                if (nested) {
                    throw error("missing }");
                }
                return out.toString();
            }
            if (css.charAt(pos) == '}') {
                if (!nested) {
                    throw error("unexpected }");
                }
                pos++;
                return out.toString();
            }
            int start = pos;
            int end = scanTo("{;");
            String prelude = clean(css.substring(start, end));
            if (end >= css.length()) {
                throw error("rule \"" + prelude + "\" has no { ... }");
            }
            if (css.charAt(end) == ';') {
                pos = end + 1;
                statement(prelude, out);
                continue;
            }
            pos = end + 1;
            String keyword = prelude.startsWith("@") ? prelude.split("[\\s(]", 2)[0].toLowerCase(Locale.ROOT) : "";
            switch (keyword) {
                case "" -> out.append(selectors(prelude)).append(" {").append(block()).append("}\n");
                case "@media", "@supports", "@container", "@layer" ->
                        out.append(prelude).append(" {\n").append(rules(true)).append("}\n");
                case "@font-face", "@keyframes", "@-webkit-keyframes", "@property", "@page" ->
                        out.append(prelude).append(" {").append(block()).append("}\n");
                default -> throw error("\"" + keyword + "\" isn't supported in a layout stylesheet");
            }
        }
    }

    private void statement(String prelude, StringBuilder out) throws ConfigException {
        String lower = prelude.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@import")) {
            throw error("@import isn't allowed (stylesheets are inlined into the page)");
        }
        if (lower.startsWith("@charset")) {
            return;
        }
        if (lower.startsWith("@layer")) {
            out.append(prelude).append(";\n");
            return;
        }
        throw error("unexpected \"" + prelude + ";\"");
    }

    private String selectors(String list) throws ConfigException {
        List<String> scoped = new ArrayList<>();
        for (String selector : splitTopLevel(list)) {
            String s = selector.strip();
            if (s.isEmpty()) {
                throw error("empty selector in \"" + list + "\"");
            }
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.matches("(html|body|:root)([\\s.#:\\[>+~].*)?")) {
                throw error("\"" + s + "\" can't be styled from a layout stylesheet (it's confined to the layout's"
                        + " content); use the manifest's stylesheets");
            }
            if (s.startsWith("&")) {
                scoped.add(prefix + s.substring(1));
            } else if (lower.startsWith(":scope")) {
                scoped.add(prefix + s.substring(6));
            } else {
                scoped.add(prefix + " " + s);
            }
        }
        return String.join(", ", scoped);
    }

    /** A declaration block's contents, up to and past its closing brace, kept as written. */
    private String block() throws ConfigException {
        int start = pos;
        int depth = 1;
        while (pos < css.length()) {
            char c = css.charAt(pos);
            if (c == '"' || c == '\'') {
                skipString(c);
                continue;
            }
            if (c == '/' && pos + 1 < css.length() && css.charAt(pos + 1) == '*') {
                skipComment();
                continue;
            }
            pos++;
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return css.substring(start, pos - 1);
            }
        }
        throw error("missing }");
    }

    /** Index of the next of {@code stops} outside strings, comments and brackets. */
    private int scanTo(String stops) {
        int depth = 0;
        int i = pos;
        while (i < css.length()) {
            char c = css.charAt(i);
            if (c == '"' || c == '\'') {
                int close = css.indexOf(c, i + 1);
                i = close < 0 ? css.length() : close + 1;
                continue;
            }
            if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                int close = css.indexOf("*/", i + 2);
                i = close < 0 ? css.length() : close + 2;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (depth == 0 && stops.indexOf(c) >= 0) {
                return i;
            }
            i++;
        }
        return i;
    }

    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(list.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(list.substring(start));
        return parts;
    }

    private void skipSpaceAndComments() {
        while (pos < css.length()) {
            if (Character.isWhitespace(css.charAt(pos))) {
                pos++;
            } else if (css.startsWith("/*", pos)) {
                skipComment();
            } else {
                return;
            }
        }
    }

    private void skipComment() {
        int close = css.indexOf("*/", pos + 2);
        pos = close < 0 ? css.length() : close + 2;
    }

    private void skipString(char quote) {
        int i = pos + 1;
        while (i < css.length() && css.charAt(i) != quote) {
            i += css.charAt(i) == '\\' ? 2 : 1;
        }
        pos = Math.min(i + 1, css.length());
    }

    /** A prelude with comments removed and whitespace collapsed. */
    private static String clean(String prelude) {
        return prelude.replaceAll("/\\*.*?\\*/", " ").replaceAll("\\s+", " ").strip();
    }

    private ConfigException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, css.length()); i++) {
            if (css.charAt(i) == '\n') {
                line++;
            }
        }
        return new ConfigException(source + " line " + line + ": " + message);
    }
}
