package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.Map;

/**
 * Built-in, named template presets an author can reference by short name
 * instead of a file path in a {@code landing: { template: <name-or-path> } }
 * config — e.g. {@code template: minimal} instead of
 * {@code template: layouts/my-section.html}.
 *
 * <p>Keeping this in {@code pagewright-core} lets both {@code pagewright-config}
 * (which parses the book-wide {@code sections.landing.template} default) and
 * {@code pagewright-layout} (which reads a section folder's own per-folder
 * override directly, since section grouping happens after every card in the
 * book is already loaded) resolve the same names the same way, without either
 * module needing to depend on the other.
 */
public final class NamedTemplates {

    private NamedTemplates() {}

    /**
     * Named presets for a "section" (a folder of cards with no value on any
     * declared axis) landing page. {@code "default"} is the bundled
     * {@code site-section} template (title, card count, card grid);
     * {@code "minimal"} is {@code site-section-minimal} (title only).
     */
    private static final Map<String, String> SECTION_PRESETS = Map.of(
            "default", "site-section",
            "minimal", "site-section-minimal"
    );

    /**
     * The resolved bare template name produced by the {@code "minimal"} preset.
     * Exposed so callers outside the site-rendering path (namely the PDF/book
     * section-divider, which has no HTML template of its own to dispatch on)
     * can still tell whether a section resolved to the minimal preset, and
     * scale their own output down to match (title only, no count/TOC) without
     * hardcoding the bundled template name a second time.
     */
    public static final String MINIMAL_SECTION_TEMPLATE = SECTION_PRESETS.get("minimal");

    /**
     * Resolve a {@code landing.template} yaml value for a section landing
     * page. If {@code raw} matches a known named preset, returns the
     * preset's bundled template name directly (no file lookup). Otherwise
     * treats {@code raw} as a path relative to {@code bookRoot} and returns
     * the bare Pebble template name (filename, extension stripped) that the
     * engine's template loader chain should resolve.
     *
     * @return the resolved bare template name, or null if {@code raw} is null
     */
    public static String resolveSectionTemplate(Path bookRoot, String raw) {
        if (raw == null) return null;
        String preset = SECTION_PRESETS.get(raw);
        if (preset != null) return preset;
        Path resolved = bookRoot == null ? Path.of(raw) : bookRoot.resolve(raw);
        return bareTemplateName(resolved);
    }

    /** Strip a template file path down to the bare name Pebble's loader expects (no extension). */
    public static String bareTemplateName(Path templatePath) {
        String filename = templatePath.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
