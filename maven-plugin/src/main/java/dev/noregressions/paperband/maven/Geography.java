package dev.noregressions.paperband.maven;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The book's resolved geography: where its pieces live on disk. The POM is
 * the sole authority — explicit parameters win, the convention fills what's
 * unsaid, and {@code paperband.yaml} never moves a root (it declares what the
 * book <em>is</em>; the POM declares <em>where</em> it is).
 *
 * <pre>
 * &lt;home&gt;src/main/paperband&lt;/home&gt;       default; moves everything below at once
 * &lt;content&gt;docs&lt;/content&gt;               default ${home}/content
 * &lt;layouts&gt;book/templates&lt;/layouts&gt;     default ${home}/layouts
 * </pre>
 *
 * <p>{@code paperband.yaml} is expected at {@code ${home}/paperband.yaml}
 * (falling back to the content root's own for self-contained legacy books —
 * see {@code ConfigLoader}), and the css chain's relative paths resolve
 * against the yaml that declares them, so {@code styles/} moves with home
 * without needing a knob of its own.
 *
 * @param home    the book home, or null when neither declared nor present —
 *                a legacy book that carries everything in its content root
 * @param content the content root, or null when nothing declared it (the
 *                goal then falls back to {@code <input>}/{@code <book>}, or
 *                fails)
 * @param layouts the templates directory, or null to keep the legacy
 *                derivation ({@code <contentRoot>/layouts})
 */
record Geography(Path home, Path content, Path layouts) {

    /** The conventional home, relative to the module basedir. */
    static final String CONVENTIONAL_HOME = "src/main/paperband";

    /**
     * Resolve the geography from the POM's parameters and the convention.
     *
     * <p>Explicit parameters are taken as declared (whether or not the
     * directory exists yet — a declared path that's missing should fail
     * loudly downstream, not silently fall back). Defaults only fill in when
     * the conventional directory actually exists, so a project without
     * {@code src/main/paperband} resolves to an all-null geography and the
     * legacy {@code <input>}/{@code <book>} behavior is untouched.
     *
     * @param basedir the module basedir
     * @param home    the {@code <home>} parameter, or null
     * @param content the {@code <content>} parameter, or null
     * @param layouts the {@code <layouts>} parameter, or null
     * @return the resolved geography
     */
    static Geography resolve(Path basedir, Path home, Path content, Path layouts) {
        Path effectiveHome = home;
        if (effectiveHome == null) {
            Path conventional = basedir.resolve(CONVENTIONAL_HOME);
            if (Files.isDirectory(conventional)) effectiveHome = conventional;
        }
        Path effectiveContent = content;
        if (effectiveContent == null && effectiveHome != null) {
            Path conventional = effectiveHome.resolve("content");
            if (Files.isDirectory(conventional)) effectiveContent = conventional;
        }
        Path effectiveLayouts = layouts;
        if (effectiveLayouts == null && effectiveHome != null) {
            Path conventional = effectiveHome.resolve("layouts");
            if (Files.isDirectory(conventional)) effectiveLayouts = conventional;
        }
        return new Geography(
                normalize(effectiveHome), normalize(effectiveContent), normalize(effectiveLayouts));
    }

    private static Path normalize(Path p) {
        return p == null ? null : p.toAbsolutePath().normalize();
    }

    /** One log line saying where everything resolved — the whole decision, visible. */
    String describe() {
        return "book geography: home=" + (home == null ? "(none)" : home)
                + ", content=" + (content == null ? "(none)" : content)
                + ", layouts=" + (layouts == null ? "(derived)" : layouts);
    }
}
