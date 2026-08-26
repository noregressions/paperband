package dev.noregressions.paperband.cards;

import java.util.Locale;

/**
 * What happens to presentation markup found in card content — inline
 * {@code style=}, {@code <style>}/{@code <script>} blocks, presentational
 * tags and attributes (see {@link ContentSanitizer} for the exact list).
 *
 * <p>The tool's contract is that content carries <em>structure</em> and the
 * theme owns <em>appearance</em> — that's what lets a reader pick a theme and
 * have it actually apply. Raw HTML in markdown is a legitimate structural
 * escape hatch (a complex table, {@code <kbd>}, {@code <details>}); styling
 * smuggled through it is what breaks theme-swapping, so the two are treated
 * differently: structure passes, presentation is policy.
 *
 * <p>Declared per book (or per folder — it rides the {@code vars} cascade) as
 * {@code vars: { contentPolicy: allow | clean | strict } }, in yaml or through
 * the POM's {@code <vars>}. The default is {@link #CLEAN}.
 */
public enum ContentPolicy {

    /** Leave content HTML exactly as written — today's escape hatch. */
    ALLOW,

    /**
     * Strip presentation from content and report each removal — the default.
     * The build always emits themeable output; authors are told what went and
     * pointed at the sanctioned route (classes via {@code {.name}} plus the
     * book css chain / theme).
     */
    CLEAN,

    /**
     * Fail the build on any presentation in content, naming the card and the
     * findings — for teams that want the source fixed, not laundered.
     */
    STRICT;

    /**
     * Parse a declared policy value, case-insensitively.
     *
     * @param value the declared value; null falls back to {@code CLEAN}
     * @return the policy
     * @throws IllegalArgumentException naming the valid spellings when
     *         {@code value} is none of them
     */
    public static ContentPolicy parse(Object value) {
        if (value == null) return CLEAN;
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "", "clean" -> CLEAN;
            case "allow" -> ALLOW;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException(
                    "contentPolicy must be allow, clean or strict — got '" + value + "'");
        };
    }
}
