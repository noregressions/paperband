package dev.noregressions.paperband.layout;

import java.util.List;

/**
 * Names of the theme bundles that ship inside {@code layout} on the
 * classpath. The list is the single source of truth for anything that wants
 * to enumerate the built-ins (e.g. the {@code paperband:themes} goal). Add a
 * theme directory under {@code src/main/resources/themes/} and append its
 * name here.
 *
 * <p>User themes from a {@code <themeDir>} directory are <em>not</em>
 * included; they're discovered at runtime by listing that directory.
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
