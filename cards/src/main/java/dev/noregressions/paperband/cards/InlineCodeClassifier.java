package dev.noregressions.paperband.cards;

import org.jsoup.nodes.Element;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Post-flexmark HTML transform that adds a semantic CSS class to every inline
 * {@code <code>} element (i.e. {@code <code>} <i>not</i> inside a {@code <pre>}).
 *
 * <p>The classification is mechanical, based on the shape of the inline
 * fragment. It mirrors how the SB4 guide already uses inline code in prose —
 * the categories emerged from a survey of ~2,000 inline spans, not from a
 * style guide. Authors keep writing backticks normally; the styling becomes
 * consistent automatically.
 *
 * <h2>Categories (priority order; first match wins)</h2>
 * <table>
 *   <tr><th>Class</th>             <th>Pattern</th>                  <th>Example</th></tr>
 *   <tr><td>{@code cls-annotation}</td><td>{@code @Word}</td>        <td>{@code @Bean}</td></tr>
 *   <tr><td>{@code cls-keyword}</td>   <td>fixed set</td>            <td>{@code null}, {@code void}</td></tr>
 *   <tr><td>{@code cls-constant}</td>  <td>{@code ALL_CAPS_SNAKE}</td><td>{@code JAVA_LEGACY}</td></tr>
 *   <tr><td>{@code cls-method}</td>    <td>{@code name(...)}</td>    <td>{@code merge()}</td></tr>
 *   <tr><td>{@code cls-type}</td>      <td>{@code UpperCamelCase}</td><td>{@code RestClient}</td></tr>
 *   <tr><td>{@code cls-path}</td>      <td>starts with {@code /}</td><td>{@code /actuator/health}</td></tr>
 *   <tr><td>{@code cls-property}</td>  <td>lowercase + dot/hyphen</td><td>{@code application.properties}</td></tr>
 * </table>
 *
 * <p>Anything that doesn't match (plain identifiers, mixed content,
 * unusual punctuation) falls through with no class — preserves the existing
 * default inline-code styling. No regression for unclassified content.
 *
 * <p>Idempotent — a {@code <code>} that already carries a {@code cls-*} class
 * is left alone.
 */
final class InlineCodeClassifier {

    /** Inline code we treat as keywords/literals. Small fixed set. */
    private static final Set<String> KEYWORDS = Set.of(
            "true", "false", "null", "void", "this", "super");

    private static final Pattern P_ANNOTATION = Pattern.compile("^@[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern P_CONSTANT   = Pattern.compile("^[A-Z][A-Z0-9_]+$");
    private static final Pattern P_METHOD     = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\([^)]*\\)$");
    private static final Pattern P_TYPE       = Pattern.compile("^[A-Z][A-Za-z0-9]+$");
    private static final Pattern P_PATH       = Pattern.compile("^/[A-Za-z0-9/_.\\-{}]+$");
    // property: lowercase-start, allowed chars include dots, hyphens, underscores,
    // wildcards. Requires AT LEAST ONE dot or hyphen so plain identifiers like
    // "id" or "name" fall through unclassified.
    private static final Pattern P_PROPERTY   = Pattern.compile("^[a-z][a-z0-9._\\-*]*[.\\-][a-z0-9._\\-*]*$");

    private InlineCodeClassifier() {}

    /**
     * Walk {@code bodyEl} and classify every inline {@code <code>}. Code
     * inside {@code <pre>} (block listings emitted from fenced code) is left
     * alone — Prism handles those.
     */
    static void process(Element bodyEl) {
        for (Element code : bodyEl.select("code")) {
            Element parent = code.parent();
            if (parent != null && "pre".equals(parent.tagName())) continue;
            if (hasClassWithPrefix(code.className(), "cls-")) continue;  // idempotent

            String text = code.wholeText();
            String cls = classify(text);
            if (cls != null) code.addClass(cls);
        }
    }

    /** Return the {@code cls-*} class for {@code text}, or null if no match. */
    static String classify(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;

        if (P_ANNOTATION.matcher(t).matches())              return "cls-annotation";
        if (KEYWORDS.contains(t))                            return "cls-keyword";
        if (P_CONSTANT.matcher(t).matches())                 return "cls-constant";
        if (P_METHOD.matcher(t).matches())                   return "cls-method";
        if (P_TYPE.matcher(t).matches())                     return "cls-type";
        if (P_PATH.matcher(t).matches())                     return "cls-path";
        if (P_PROPERTY.matcher(t).matches())                 return "cls-property";
        return null;
    }

    private static boolean hasClassWithPrefix(String classAttr, String prefix) {
        if (classAttr == null || classAttr.isEmpty()) return false;
        for (String token : classAttr.split("\\s+")) {
            if (token.startsWith(prefix)) return true;
        }
        return false;
    }
}
