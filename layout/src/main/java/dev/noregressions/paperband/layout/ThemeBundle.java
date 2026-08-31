package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.loader.Loader;

import java.util.List;

/**
 * A loaded theme: ordered stylesheet content plus an optional Pebble template
 * loader that sits ahead of the bundled defaults so themes can override
 * specific partials.
 *
 * <p>Themes live under {@code themes/&lt;name&gt;/} on the classpath (bundled
 * with layout) or under any directory passed via the plugin's
 * {@code <themeDir>} parameter. Each theme directory contains:
 * <ul>
 *   <li>{@code manifest.txt} — one stylesheet path per line (relative to the
 *       theme dir); blank lines and {@code #} comments are skipped.</li>
 *   <li>One or more {@code .css} files, listed in the manifest, inlined in
 *       declared order <em>after</em> the user's CSS chain.</li>
 *   <li>Optional {@code templates/*.html} — partial overrides resolved by
 *       Pebble before the bundled defaults.</li>
 * </ul>
 *
 * <p>Themes layer over user CSS via cascade order: paperband inlines the
 * user's css chain first, then theme stylesheets, so theme rules win on
 * specificity ties without the theme needing to bump selector weight.
 *
 * @see ThemeResolver#resolve(String, java.nio.file.Path)
 */
public final class ThemeBundle {

    /** Sentinel "no theme" bundle: empty stylesheets, no template overrides. */
    public static final ThemeBundle NONE =
            new ThemeBundle("", List.of(), List.of(), List.of(), null);

    private final String name;
    private final List<String> stylesheets;
    private final List<String> printStylesheets;
    private final List<String> siteStylesheets;
    private final Loader<?> templateLoader;

    /**
     * @param name             theme name
     * @param stylesheets      shared stylesheets, applied to every target
     * @param printStylesheets stylesheets for paged output only
     * @param siteStylesheets  stylesheets for the static site only
     * @param templateLoader   the theme's template overrides, or null
     */
    public ThemeBundle(String name, List<String> stylesheets, List<String> printStylesheets,
                       List<String> siteStylesheets, Loader<?> templateLoader) {
        this.name = (name == null) ? "" : name;
        this.stylesheets = List.copyOf(stylesheets);
        this.printStylesheets = List.copyOf(printStylesheets);
        this.siteStylesheets = List.copyOf(siteStylesheets);
        this.templateLoader = templateLoader;
    }

    /** Back-compatible constructor for a theme with no target-scoped stylesheets. */
    public ThemeBundle(String name, List<String> stylesheets, Loader<?> templateLoader) {
        this(name, stylesheets, List.of(), List.of(), templateLoader);
    }

    /** Theme name (matches the directory name); empty string for {@link #NONE}. */
    public String name() { return name; }

    /** Shared stylesheet contents in declared order, ready to inline after the user CSS chain. */
    public List<String> stylesheets() { return stylesheets; }

    /**
     * Stylesheets for one target: the shared ones, then that target's own.
     *
     * <p>Target-scoped sheets come last so they can correct the shared layer
     * rather than having to pre-empt it — a theme puts its page measure and
     * page density in {@code print:} and its grid and web type scale in
     * {@code site:}, and neither has to know about the other.
     *
     * @param target the output being rendered; null returns the shared sheets alone
     * @return the stylesheets to inline, in cascade order
     */
    public List<String> stylesheets(Target target) {
        if (target == null) return stylesheets;
        List<String> scoped = target == Target.PRINT ? printStylesheets : siteStylesheets;
        if (scoped.isEmpty()) return stylesheets;
        List<String> out = new java.util.ArrayList<>(stylesheets.size() + scoped.size());
        out.addAll(stylesheets);
        out.addAll(scoped);
        return out;
    }

    /**
     * Pebble template loader for this theme's overrides, or {@code null} if
     * the theme has no {@code templates/} directory.
     */
    public Loader<?> templateLoader() { return templateLoader; }

    /** True if this bundle adds nothing (no name, no styles, no templates). */
    public boolean isEmpty() {
        return name.isEmpty() && stylesheets.isEmpty() && printStylesheets.isEmpty()
                && siteStylesheets.isEmpty() && templateLoader == null;
    }
}
