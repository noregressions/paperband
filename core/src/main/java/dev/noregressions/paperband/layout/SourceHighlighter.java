package dev.noregressions.paperband.layout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns markdown source into escaped HTML with this project's syntax picked out, for source
 * boxes. Each token is a {@code <span>} with these classes:
 *
 * <pre>
 * tok-fm                   front matter block at the top of the file
 * tok-pre  tok-print       {{ value }}      pre pass
 * tok-pre  tok-tag         {% tag %}
 * tok-pre  tok-comment     {# comment #}
 * tok-post tok-print       [[ value ]]      post pass
 * tok-post tok-tag         [% tag %]
 * tok-post tok-comment     [# comment #]
 * tok-marker               {! tag key=value}
 * tok-div                  ::: at the start of a line
 * tok-fence                ``` / ~~~ and the language, at the start of a line
 * tok-heading              the #'s of a heading
 * </pre>
 *
 * Escaped markers (<code>\&#123;!</code>) are left plain, and so are markers inside code (fenced blocks
 * and {@code `code spans`}), because markers there are never applied. Template syntax inside
 * code is still highlighted: Pebble processes it like anything else. Everything else is escaped text.
 */
public final class SourceHighlighter {

    private static final Pattern FRONT_MATTER = Pattern.compile(
            "\\A---[ \\t]*\\r?\\n(?:.*?\\r?\\n)??(?:---|\\.\\.\\.)[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);

    private static final String[][] TOKENS = {
            {"escaped", "\\\\\\{!"},
            {"tok-pre tok-print", "\\{\\{.*?\\}\\}"},
            {"tok-pre tok-tag", "\\{%.*?%\\}"},
            {"tok-pre tok-comment", "\\{#.*?#\\}"},
            {"tok-post tok-print", "\\[\\[.*?\\]\\]"},
            {"tok-post tok-tag", "\\[%.*?%\\]"},
            {"tok-post tok-comment", "\\[#.*?#\\]"},
            {"tok-marker", "\\{!(?:[^}\"\\n]|\"(?:[^\"\\\\\\n]|\\\\.)*\")*\\}"},
            {"tok-div", "(?m)^ {0,3}:{3,}"},
            {"tok-fence", "(?m)^ {0,3}(?:`{3,}|~{3,})[^\\s{`]*"},
            {"tok-heading", "(?m)^ {0,3}#{1,6}(?=[ \\t]|$)"},
    };

    private static final Pattern ALL;

    static {
        StringBuilder sb = new StringBuilder("(?s)");
        for (int i = 0; i < TOKENS.length; i++) {
            sb.append(i == 0 ? "" : "|").append("(?<t").append(i).append('>').append(TOKENS[i][1]).append(')');
        }
        ALL = Pattern.compile(sb.toString());
    }

    private SourceHighlighter() {
    }

    public static String highlight(String source) {
        StringBuilder out = new StringBuilder();
        String body = source;
        Matcher fm = FRONT_MATTER.matcher(source);
        if (fm.find()) {
            span(out, "tok-fm", fm.group());
            body = source.substring(fm.end());
        }
        boolean[] code = codeRanges(body);
        Matcher m = ALL.matcher(body);
        int last = 0;
        while (m.find()) {
            out.append(SiteHtmlRenderer.esc(body.substring(last, m.start())));
            for (int i = 0; i < TOKENS.length; i++) {
                if (m.group("t" + i) != null) {
                    if (TOKENS[i][0].equals("escaped") || (TOKENS[i][0].equals("tok-marker") && code[m.start()])) {
                        out.append(SiteHtmlRenderer.esc(m.group()));
                    } else {
                        span(out, TOKENS[i][0], m.group());
                    }
                    break;
                }
            }
            last = m.end();
        }
        out.append(SiteHtmlRenderer.esc(body.substring(last)));
        return out.toString();
    }

    private static final Pattern FENCE = Pattern.compile(" {0,3}(`{3,}|~{3,}).*");
    private static final Pattern CODE_SPAN = Pattern.compile("(`+)(?!`).+?(?<!`)\\1(?!`)", Pattern.DOTALL);

    /** Which characters are code: fenced block bodies (not the fence lines) and inline code spans. */
    private static boolean[] codeRanges(String text) {
        boolean[] code = new boolean[text.length() + 1];
        String fence = null;
        int pos = 0;
        StringBuilder prose = new StringBuilder();
        for (String line : text.split("(?<=\n)", -1)) {
            String bare = line.stripTrailing();
            Matcher f = FENCE.matcher(bare);
            if (fence == null && f.matches()) {
                fence = f.group(1);
                prose.append(" ".repeat(line.length()));
            } else if (fence != null && bare.strip().startsWith(fence) && bare.strip().replace(fence.substring(0, 1), "").isEmpty()) {
                fence = null;
                prose.append(" ".repeat(line.length()));
            } else if (fence != null) {
                java.util.Arrays.fill(code, pos, pos + line.length(), true);
                prose.append(" ".repeat(line.length()));
            } else {
                prose.append(line);
            }
            pos += line.length();
        }
        Matcher spans = CODE_SPAN.matcher(prose);
        while (spans.find()) {
            java.util.Arrays.fill(code, spans.start(), spans.end(), true);
        }
        return code;
    }

    private static void span(StringBuilder out, String cssClass, String text) {
        out.append("<span class=\"").append(cssClass).append("\">").append(SiteHtmlRenderer.esc(text)).append("</span>");
    }
}
