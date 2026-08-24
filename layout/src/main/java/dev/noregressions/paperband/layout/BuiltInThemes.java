package dev.noregressions.paperband.layout;

import java.util.List;

/**
 * Names of the theme bundles that ship inside {@code layout} on the
 * classpath. The list is the single source of truth used by both
 * {@code --theme} help text and any UI that wants to enumerate the built-ins
 * (e.g. {@code paperband themes}). Add a theme directory under
 * {@code src/main/resources/themes/} and append its name here.
 *
 * <p>User themes from {@code --theme-dir} are <em>not</em> included; they're
 * discovered at runtime by listing that directory.
 */
public final class BuiltInThemes {

    /** Built-in theme names in display order. */
    public static final List<String> NAMES = List.of(
            "editorial",
            "classical",
            "fieldguide",
            "dark",
            "blueprint",
            "carded",
            "herodevs",
            "editorial-gold",
            "workshop"
    );

    private BuiltInThemes() {}
}
