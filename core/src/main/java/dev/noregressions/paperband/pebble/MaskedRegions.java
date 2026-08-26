package dev.noregressions.paperband.pebble;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks the character ranges in raw markdown where template syntax must not
 * be evaluated: a leading YAML frontmatter block, fenced code blocks, and
 * inline code spans. Used to protect documentation that shows
 * {@code {% fragment %}}, {@code {% if %}}, {@code {{ vars.x }}} (or any
 * other brace-delimited syntax) as a literal example from being evaluated by
 * a Pebble pass.
 *
 * <p>Shared by every pre-flexmark Pebble evaluation pass —
 * {@code include}'s {@code {% fragment %}} tag resolution and
 * {@code cards}' whole-body vars/conditionals pass both run their
 * masked source through a real Pebble parser and need the exact same
 * protection, so the logic lives here once rather than being duplicated (and
 * risking the two copies drifting apart) in each module.
 */
public final class MaskedRegions {

    private MaskedRegions() {}

    /**
     * Mark every character that lives in a region where template syntax must
     * not be evaluated:
     * <ul>
     *   <li>a leading YAML frontmatter block ({@code ---} … {@code ---});</li>
     *   <li>fenced code blocks ({@code ```}/{@code ~~~}, up to three leading
     *       spaces of indent, closed by a same-or-longer run of the same fence
     *       character);</li>
     *   <li>single-line inline code spans (a run of N backticks closed by
     *       another run of exactly N backticks on the same line).</li>
     * </ul>
     * Indented (4-space) code blocks are intentionally not masked, as they
     * are ambiguous with list continuations.
     */
    public static boolean[] mask(String markdown) {
        int n = markdown.length();
        boolean[] mask = new boolean[n];
        String fence = null; // active fence marker (e.g. "```"), or null when outside a fence
        // A leading frontmatter block is metadata; never evaluate template syntax in it.
        int i = frontmatterEnd(markdown);
        fill(mask, 0, i);
        while (i < n) {
            int lineStart = i;
            int nl = markdown.indexOf('\n', i);
            int lineEnd = (nl < 0) ? n : nl;       // exclusive, excludes the '\n'
            String line = markdown.substring(lineStart, lineEnd);
            String body = stripUpTo3Spaces(line);

            if (fence == null) {
                String opener = fenceMarker(body);
                if (opener != null) {
                    fence = opener;
                    fill(mask, lineStart, lineEnd);
                } else {
                    maskInlineSpans(mask, line, lineStart);
                }
            } else {
                fill(mask, lineStart, lineEnd);
                if (isClosingFence(body, fence)) fence = null;
            }
            i = lineEnd + 1; // step past the newline
        }
        return mask;
    }

    /**
     * Replace every masked run of {@code markdown} with an inert sentinel
     * token, ready to hand to a Pebble parser, and remember the original text
     * so {@link Masked#restore} can splice it back in afterwards.
     *
     * <p>This is the substitute/restore half of the masking dance that every
     * caller of {@link #mask} otherwise has to write itself; factored out
     * here so the sentinel format only needs to be gotten right once.
     */
    public static Masked substitute(String markdown) {
        return substituteFrom(markdown, mask(markdown));
    }

    /**
     * Mark the regions of an <em>HTML</em> source where template syntax must
     * not be evaluated — the HTML-shaped equivalents of markdown's escape
     * hatches: comments ({@code <!-- -->}), {@code <pre>} blocks, and
     * {@code <code>} spans. Used when the card being preprocessed is an
     * {@code .html} source rather than markdown, where fences and backticks
     * don't exist.
     */
    public static boolean[] maskHtml(String html) {
        boolean[] mask = new boolean[html.length()];
        java.util.regex.Matcher m = HTML_PROTECTED.matcher(html);
        while (m.find()) {
            fill(mask, m.start(), m.end());
        }
        return mask;
    }

    /** {@link #substitute}, with {@link #maskHtml}'s regions. */
    public static Masked substituteHtml(String html) {
        return substituteFrom(html, maskHtml(html));
    }

    // Non-greedy so adjacent regions stay separate; DOTALL because pre blocks
    // span lines; a <code> inside a <pre> is swallowed by the pre match.
    private static final java.util.regex.Pattern HTML_PROTECTED =
            java.util.regex.Pattern.compile(
                    "<!--.*?-->|<pre\\b.*?</pre\\s*>|<code\\b.*?</code\\s*>",
                    java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

    private static Masked substituteFrom(String markdown, boolean[] masked) {
        StringBuilder out = new StringBuilder(markdown.length());
        List<String> originals = new ArrayList<>();
        int n = markdown.length();
        int i = 0;
        while (i < n) {
            if (!masked[i]) {
                out.append(markdown.charAt(i));
                i++;
                continue;
            }
            int start = i;
            while (i < n && masked[i]) i++;
            out.append(SENTINEL_PREFIX).append(originals.size()).append(SENTINEL_SUFFIX);
            originals.add(markdown.substring(start, i));
        }
        return new Masked(out.toString(), originals);
    }

    // Plain alphanumeric, deliberately containing none of Pebble's special
    // characters ({, }, %, #) so the sentinel itself can never be mistaken
    // for template syntax while it sits in the unmasked text.
    //
    // The suffix MUST be non-empty and non-digit. restore() replaces tokens
    // one index at a time with a plain String.replace, and with an empty
    // suffix "PWMASK1" is a literal substring of "PWMASK10", "PWMASK11", ...,
    // "PWMASK19" (and so on for every longer index sharing that prefix) — so
    // replacing index 1 first mangles every not-yet-replaced token whose
    // digits happen to start with "1", leaving a stray trailing digit behind
    // (e.g. index 10's sentinel becomes "<original for index 1>0"). A fixed
    // non-digit suffix makes every token's digit run terminate before a
    // letter, so no token can ever be a substring of another.
    private static final String SENTINEL_PREFIX = "PWMASK";
    private static final String SENTINEL_SUFFIX = "END";

    /** Masked text ready for a Pebble parser, plus the means to restore it. */
    public record Masked(String text, List<String> originals) {
        public String restore(String rendered) {
            String result = rendered;
            for (int idx = 0; idx < originals.size(); idx++) {
                result = result.replace(SENTINEL_PREFIX + idx + SENTINEL_SUFFIX, originals.get(idx));
            }
            return result;
        }
    }

    /**
     * If {@code markdown} opens with a YAML frontmatter block, return the
     * offset just past its closing {@code ---} line; otherwise 0. Mirrors the
     * delimiter rule used when the card is later parsed.
     */
    private static int frontmatterEnd(String markdown) {
        int n = markdown.length();
        int firstNl = markdown.indexOf('\n');
        int firstEnd = (firstNl < 0) ? n : firstNl;
        if (!markdown.substring(0, firstEnd).stripTrailing().equals("---")) return 0;
        int i = firstEnd + 1;
        while (i < n) {
            int nl = markdown.indexOf('\n', i);
            int end = (nl < 0) ? n : nl;
            if (markdown.substring(i, end).stripTrailing().equals("---")) {
                return (nl < 0) ? n : nl + 1;
            }
            i = end + 1;
        }
        return 0; // no closing delimiter — not a frontmatter block
    }

    private static String stripUpTo3Spaces(String line) {
        int k = 0;
        while (k < line.length() && k < 3 && line.charAt(k) == ' ') k++;
        return line.substring(k);
    }

    /** If {@code body} opens a fence, return the fence marker run; else null. */
    private static String fenceMarker(String body) {
        if (body.isEmpty()) return null;
        char c = body.charAt(0);
        if (c != '`' && c != '~') return null;
        int len = 0;
        while (len < body.length() && body.charAt(len) == c) len++;
        if (len < 3) return null;
        // A backtick info string may not itself contain a backtick.
        if (c == '`' && body.indexOf('`', len) >= 0) return null;
        return body.substring(0, len);
    }

    private static boolean isClosingFence(String body, String fence) {
        char c = fence.charAt(0);
        int len = 0;
        while (len < body.length() && body.charAt(len) == c) len++;
        if (len < fence.length()) return false;
        // Nothing but trailing whitespace may follow a closing fence.
        return body.substring(len).isBlank();
    }

    private static void maskInlineSpans(boolean[] mask, String line, int base) {
        int j = 0, L = line.length();
        while (j < L) {
            if (line.charAt(j) != '`') { j++; continue; }
            int openStart = j;
            while (j < L && line.charAt(j) == '`') j++;
            int runLen = j - openStart;
            int k = j;
            int closeEnd = -1;
            while (k < L) {
                if (line.charAt(k) != '`') { k++; continue; }
                int cStart = k;
                while (k < L && line.charAt(k) == '`') k++;
                if (k - cStart == runLen) { closeEnd = k; break; }
            }
            if (closeEnd >= 0) {
                fill(mask, base + openStart, base + closeEnd);
                j = closeEnd;
            }
            // else: no matching closer on this line — treat backticks as literal.
        }
    }

    private static void fill(boolean[] mask, int from, int to) {
        for (int x = from; x < to && x < mask.length; x++) mask[x] = true;
    }
}
