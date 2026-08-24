package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.ThemeResolver;

import java.io.IOException;
import java.nio.file.Path;

/** Theme resolution, including the one name the build can use to mean "no theme". */
final class Themes {

    /**
     * The reserved name that turns theming off, overriding whatever the book's
     * yaml asked for. A theme directory containing a bundle actually called
     * {@code none} can't be selected — the trade for having a spelling for
     * "plain".
     */
    static final String NONE = "none";

    private Themes() {}

    /**
     * Resolve the theme for a build.
     *
     * <p>Precedence: the build's own name wins; {@link #NONE} means no theme at
     * all; unset falls back to the book's {@code theme:}. That last fallback is
     * why {@code none} has to exist — without it the build can replace the
     * book's theme with a different theme but never with nothing.
     *
     * @param declared  the name the build declared; may be null or blank
     * @param bookTheme the theme the book's yaml declared; may be null
     * @param themeDir  user theme directory searched before the built-ins
     * @return the resolved bundle, {@link ThemeBundle#NONE} when theming is off
     */
    static ThemeBundle resolve(String declared, String bookTheme, Path themeDir) throws IOException {
        String name = (declared != null && !declared.isBlank()) ? declared.trim() : bookTheme;
        if (name != null && NONE.equalsIgnoreCase(name.trim())) return ThemeBundle.NONE;
        return ThemeResolver.resolve(name, themeDir);
    }
}
