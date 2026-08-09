package dev.noregressions.paperband.cards;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-flexmark HTML transform that rewrites two custom fenced-code conventions
 * into structured HTML:
 *
 * <h2>{@code ```diff-card}</h2>
 * <pre>
 * ```diff-card
 * # // optional caption
 * @@removed
 * old code
 * @@added
 * new code
 * ```
 * </pre>
 *
 * <p>Becomes a {@code <figure class="diff-card">} with two
 * {@code <pre><code class="language-java">} columns, each fronted by a
 * {@code <header>Before</header>} / {@code <header>After</header>} label.
 * Prism then highlights each column independently.
 *
 * <p>Single-marker form ({@code @@added} only — used for "add this dependency"
 * snippets) emits one full-width column with header {@code Added}.
 *
 * <p><b>Language defaulting.</b> {@code ```diff-card} defaults to
 * {@code java}; override via a hyphen suffix on the fence info string —
 * {@code ```diff-card-xml}, {@code ```diff-card-yaml}, etc.
 *
 * <p><b>Page-break hint.</b> An optional {@code @@break} marker line (anywhere
 * before {@code @@removed}/{@code @@added}, same body-directive style as the
 * caption) puts a break preference on this one specific {@code <figure>} —
 * useful when two {@code ```diff-card} fences share one heading section and
 * only markdown's heading-attribute syntax ({@code {.pw-page-start}}) isn't
 * reachable, since that only attaches to headings, never to an individual
 * fenced code block:
 * <pre>
 * ```diff-card
 * @@break page-start
 * # // second change
 * @@removed
 * ...
 * @@added
 * ...
 * ```
 * </pre>
 * Same two hint values as {@code SlotTracker}'s layout hints and the YAML
 * card {@code layout:} key ({@code page-start} → {@code break-before: page},
 * {@code avoid-split} → {@code break-inside: avoid}), reused for consistency
 * — resolves to the identical {@code pw-page-start}/{@code pw-avoid-split}
 * scaffold CSS class every bundled theme already ships. An unrecognised
 * value throws (wrapped as {@code CardParseException} with file context by
 * {@code CardLoader}).
 *
 * <h2>{@code ```error-output}</h2>
 * <p>Free-form terminal output. Rewritten to
 * {@code <pre class="error-output"><code>…</code></pre>} with two opt-in
 * line-level decorations: lines starting with {@code $ } get wrapped in
 * {@code <span class="err-prompt">}, lines starting with {@code [ERROR]} in
 * {@code <span class="err-error">}. The class swap (from
 * {@code language-error-output} to {@code error-output}) keeps Prism's
 * autoloader from issuing a 404 for the nonexistent language definition.
 *
 * <p>Untouched code blocks (real languages like {@code language-java}) pass
 * straight through to Prism.
 */
final class DiffCardProcessor {

    private static final String DIFF_CARD_PREFIX = "language-diff-card";
    private static final String ERROR_OUTPUT_CLASS = "language-error-output";
    private static final String DEFAULT_LANGUAGE = "java";

    /** {@code @@break} marker values, mapped to the scaffold CSS classes every bundled theme ships. */
    private static final java.util.Map<String, String> BREAK_HINT_CLASSES =
            java.util.Map.of("page-start", "pw-page-start", "avoid-split", "pw-avoid-split");

    private DiffCardProcessor() {}

    /**
     * Walk {@code bodyEl} and rewrite every matching code block in place.
     * Idempotent — already-rewritten figures don't match the selectors.
     */
    static void process(Element bodyEl) {
        // Snapshot first; we mutate the tree during iteration.
        List<Element> codes = new ArrayList<>(bodyEl.select("pre > code"));
        for (Element code : codes) {
            String cls = code.className();
            if (hasClassWithPrefix(cls, DIFF_CARD_PREFIX)) {
                String lang = extractDiffCardLanguage(cls);
                rewriteDiffCard(code, lang);
            } else if (hasClass(cls, ERROR_OUTPUT_CLASS)) {
                rewriteErrorOutput(code);
            }
        }
    }

    /** Class-attr lookup, multi-class safe. */
    private static boolean hasClass(String classAttr, String cls) {
        for (String token : classAttr.split("\\s+")) {
            if (token.equals(cls)) return true;
        }
        return false;
    }

    /** Find first class token that starts with {@code prefix}. */
    private static boolean hasClassWithPrefix(String classAttr, String prefix) {
        for (String token : classAttr.split("\\s+")) {
            if (token.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Return the language suffix after {@code language-diff-card}, defaulting to "java". */
    static String extractDiffCardLanguage(String classAttr) {
        for (String token : classAttr.split("\\s+")) {
            if (!token.startsWith(DIFF_CARD_PREFIX)) continue;
            String tail = token.substring(DIFF_CARD_PREFIX.length());
            if (tail.isEmpty()) return DEFAULT_LANGUAGE;
            if (tail.startsWith("-")) {
                String lang = tail.substring(1);
                return lang.isEmpty() ? DEFAULT_LANGUAGE : lang;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    private static void rewriteDiffCard(Element code, String lang) {
        Element pre = code.parent();
        if (pre == null) return;

        Parsed p = parseDiffCard(code.wholeText());

        Element figure = new Element("figure").addClass("diff-card");
        figure.attr("data-lang", lang);
        boolean additionsOnly = p.removed.isEmpty() && !p.added.isEmpty();
        if (additionsOnly) figure.addClass("diff-card-additions-only");
        if (p.breakHint != null) {
            String cls = BREAK_HINT_CLASSES.get(p.breakHint);
            if (cls == null) {
                throw new IllegalArgumentException("```diff-card @@break: unknown hint '"
                        + p.breakHint + "' (expected page-start or avoid-split)");
            }
            figure.addClass(cls);
        }

        if (!p.caption.isEmpty()) {
            figure.appendElement("figcaption").text(p.caption);
        }

        if (!p.removed.isEmpty()) {
            appendSide(figure, "diff-side-removed", "Before", lang, p.removed);
        }
        if (!p.added.isEmpty()) {
            appendSide(figure, "diff-side-added",
                    additionsOnly ? "Added" : "After", lang, p.added);
        }

        pre.replaceWith(figure);
    }

    private static void appendSide(
            Element figure, String sideClass, String label,
            String lang, List<String> lines) {
        Element side = figure.appendElement("div")
                .addClass("diff-side").addClass(sideClass);
        side.appendElement("header").text(label);
        side.appendElement("pre")
                .appendElement("code")
                .addClass("language-" + lang)
                .text(String.join("\n", lines));
    }

    /** Parsed shape of a diff-card body. Package-private for testability. */
    static final class Parsed {
        final String caption;
        final List<String> removed;
        final List<String> added;
        final String breakHint;
        Parsed(String caption, List<String> removed, List<String> added, String breakHint) {
            this.caption = caption;
            this.removed = removed;
            this.added = added;
            this.breakHint = breakHint;
        }
    }

    static Parsed parseDiffCard(String text) {
        List<String> captionLines = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> current = null;     // null = caption phase
        String breakHint = null;

        for (String line : text.split("\n", -1)) {
            if (line.startsWith("@@break")) {
                breakHint = line.substring("@@break".length()).trim();
                continue;
            }
            if (line.startsWith("@@removed")) {
                current = removed;
                continue;
            }
            if (line.startsWith("@@added")) {
                current = added;
                continue;
            }
            if (current == null) {
                String c = stripCaptionPrefix(line);
                if (!c.isEmpty()) captionLines.add(c);
            } else {
                current.add(line);
            }
        }

        trimTrailingBlanks(removed);
        trimTrailingBlanks(added);
        String caption = String.join(" ", captionLines).trim();
        return new Parsed(caption, removed, added, breakHint);
    }

    /**
     * Strip a leading {@code #}, then a following {@code //}, then trim.
     * Returns empty string for lines that have nothing left (or were blank).
     */
    private static String stripCaptionPrefix(String line) {
        String s = line.strip();
        if (s.isEmpty()) return "";
        if (s.startsWith("#")) {
            s = s.substring(1).strip();
            if (s.startsWith("//")) {
                s = s.substring(2).strip();
            }
        }
        return s;
    }

    private static void trimTrailingBlanks(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
    }

    private static void rewriteErrorOutput(Element code) {
        Element pre = code.parent();
        if (pre == null) return;

        String text = code.wholeText();

        Element newPre = new Element("pre").addClass("error-output");
        Element newCode = newPre.appendElement("code");

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) newCode.appendChild(new TextNode("\n"));
            String line = lines[i];
            String decoration = errorLineDecoration(line);
            if (decoration == null) {
                newCode.appendChild(new TextNode(line));
            } else {
                newCode.appendElement("span").addClass(decoration).text(line);
            }
        }

        pre.replaceWith(newPre);
    }

    /** Return CSS class for whole-line decoration, or null if the line is plain. */
    static String errorLineDecoration(String line) {
        if (line.startsWith("$ ") || line.equals("$")) return "err-prompt";
        if (line.startsWith("[ERROR]")) return "err-error";
        return null;
    }
}
