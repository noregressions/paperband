package dev.noregressions.paperband.layout;

/**
 * Escaped HTML for a layout's YAML with keys, quoted strings and comments picked out, for
 * {@code content: layout-source} boxes: {@code <span class="tok-key">}, {@code tok-string} and
 * {@code tok-yaml-comment}. Keys are recognised at the start of a line (after any {@code - }) and
 * after <code>&#123;</code> or {@code ,} in flow mappings; a {@code #} starts a comment at the start of a
 * line or after a space, outside quotes. The lines of a block scalar ({@code key: |} or
 * {@code key: >}, more indented than the key) are text, shown as a string.
 */
public final class YamlHighlighter {

    private YamlHighlighter() {
    }

    private static final java.util.regex.Pattern BLOCK_SCALAR =
            java.util.regex.Pattern.compile("^( *)(?:- +)?[^#'\"]*:\\s+[|>][-+0-9]*\\s*(#.*)?$");

    public static String highlight(String yaml) {
        StringBuilder out = new StringBuilder();
        int blockIndent = -1;   // while inside a block scalar: the indent of the key that started it
        for (String line : yaml.split("(?<=\n)", -1)) {
            String bare = line.stripTrailing();
            int indent = bare.length() - bare.stripLeading().length();
            if (blockIndent >= 0) {
                if (bare.isEmpty() || indent > blockIndent) {
                    if (!bare.isEmpty()) {
                        span(out, "tok-string", bare);
                    }
                    out.append(line.substring(bare.length()));
                    continue;
                }
                blockIndent = -1;
            }
            line(line, out);
            java.util.regex.Matcher m = BLOCK_SCALAR.matcher(bare);
            if (m.matches()) {
                blockIndent = m.group(1).length();
            }
        }
        return out.toString();
    }

    private static void line(String line, StringBuilder out) {
        int i = 0;
        boolean keyAllowed = true;   // at the start of a line (after any "- "), or after { or ,
        boolean valueStart = true;   // where a scalar can start, so a quote opens a string
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                span(out, "tok-yaml-comment", line.substring(i).stripTrailing());
                out.append(line.substring(line.stripTrailing().length()));
                return;
            }
            if (valueStart && (c == '"' || c == '\'')) {
                int end = closingQuote(line, i);
                span(out, "tok-string", line.substring(i, end));
                i = end;
                keyAllowed = false;
                valueStart = false;
                continue;
            }
            if (keyAllowed && (Character.isLetterOrDigit(c) || c == '_')) {
                int end = i;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || "_-.".indexOf(line.charAt(end)) >= 0)) {
                    end++;
                }
                if (end < line.length() && line.charAt(end) == ':'
                        && (end + 1 == line.length() || Character.isWhitespace(line.charAt(end + 1)))) {
                    span(out, "tok-key", line.substring(i, end));
                    out.append(':');
                    i = end + 1;
                    keyAllowed = false;
                    valueStart = true;
                    continue;
                }
            }
            if (c == '{' || c == ',' || c == '[') {
                keyAllowed = c != '[';
                valueStart = true;
            } else if (!Character.isWhitespace(c) && c != '-') {
                keyAllowed = false;
                valueStart = false;
            }
            out.append(SiteHtmlRenderer.esc(String.valueOf(c)));
            i++;
        }
    }

    /** Index just past the quote that closes the one at {@code start} (or the line's end). */
    private static int closingQuote(String line, int start) {
        char quote = line.charAt(start);
        for (int i = start + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                return i + 1;
            }
        }
        return line.length();
    }

    private static void span(StringBuilder out, String cssClass, String text) {
        out.append("<span class=\"").append(cssClass).append("\">").append(SiteHtmlRenderer.esc(text)).append("</span>");
    }
}
