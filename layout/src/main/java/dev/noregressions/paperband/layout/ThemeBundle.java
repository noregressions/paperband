package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.loader.Loader;

import java.util.List;

/**
 * A loaded theme: ordered stylesheet content plus an optional Pebble template
 * loader that sits ahead of the bundled defaults so themes can override
 * specific partials.
 *
 * <p>Themes live under {@code themes/&lt;name&gt;/} on the classpath (bundled
 * with pagewright-layout) or under any directory passed via
 * {@code --theme-dir}. Each theme directory contains:
 * <ul>
 *   <li>{@code manifest.txt} — one stylesheet path per line (relative to the
 *       theme dir); blank lines and {@code #} comments are skipped.</li>
 *   <li>One or more {@code .css} files, listed in the manifest, inlined in
 *       declared order <em>after</em> the user's CSS chain.</li>
 *   <li>Optional {@code templates/*.html} — partial overrides resolved by
 *       Pebble before the bundled defaults.</li>
 * </ul>
 *
 * <p>Themes layer over user CSS via cascade order: pagewright inlines the
 * user's css chain first, then theme stylesheets, so theme rules win on
 * specificity ties without the theme needing to bump selector weight.
 *
 * @see ThemeResolver#resolve(String, java.nio.file.Path)
 */
public final class ThemeBundle {

    /** Sentinel "no theme" bundle: empty stylesheets, no template overrides. */
    public static final ThemeBundle NONE = new ThemeBundle("", List.of(), null);

    private final String name;
    private final List<String> stylesheets;
    private final Loader<?> templateLoader;

    public ThemeBundle(String name, List<String> stylesheets, Loader<?> templateLoader) {
        this.name = (name == null) ? "" : name;
        this.stylesheets = List.copyOf(stylesheets);
        this.templateLoader = templateLoader;
    }

    /** Theme name (matches the directory name); empty string for {@link #NONE}. */
    public String name() { return name; }

    /** Stylesheet contents in declared order, ready to inline after the user CSS chain. */
    public List<String> stylesheets() { return stylesheets; }

    /**
     * Pebble template loader for this theme's overrides, or {@code null} if
     * the theme has no {@code templates/} directory.
     */
    public Loader<?> templateLoader() { return templateLoader; }

    /** True if this bundle adds nothing (no name, no styles, no templates). */
    public boolean isEmpty() {
        return name.isEmpty() && stylesheets.isEmpty() && templateLoader == null;
    }
}
