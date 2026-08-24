package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.Map;

/**
 * Built-in, named template presets an author can reference by short name
 * instead of a file path in a {@code landing: { template: <name-or-path> } }
 * config — e.g. {@code template: minimal} instead of
 * {@code template: layouts/my-section.html}.
 *
 * <p>Keeping this in {@code core} lets both {@code config}
 * (which parses the book-wide {@code sections.landing.template} default) and
 * {@code layout} (which reads a section folder's own per-folder
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

    /** The directory a book's own templates live in, and what declared paths are relative to. */
    public static final String LAYOUTS_DIR = "layouts";

    /**
     * Resolve a {@code landing.template} value for a section landing page. A
     * known preset name resolves to its bundled template with no file lookup;
     * anything else is a template path, resolved by {@link #templateName}.
     *
     * @return the resolved template name, or null if {@code raw} is null
     */
    public static String resolveSectionTemplate(Path bookRoot, String raw) {
        if (raw == null) return null;
        String preset = SECTION_PRESETS.get(raw);
        if (preset != null) return preset;
        return templateName(raw);
    }

    /**
     * The Pebble template name for a declared template path.
     *
     * <p>Paperband loads a book's own templates through a loader rooted at the
     * book's {@code layouts/} directory, so a template is addressed by its path
     * <em>relative to that directory</em>, without the extension:
     *
     * <pre>
     * layouts/footer.html         &rarr; footer
     * layouts/covers/front.html   &rarr; covers/front       (subdirectories work)
     * footer.html                 &rarr; footer
     * minimal                     &rarr; minimal            (a preset or bundled name)
     * </pre>
     *
     * <p>A leading {@code layouts/} is accepted and dropped, because that's the
     * form every book and doc writes — it names the file as it sits on disk.
     *
     * <p>This used to keep only the filename, which meant the directories in a
     * declared path were silently discarded: {@code covers/front.html} looked
     * for {@code front}, so it failed when the file was exactly where it said,
     * and "worked" when it wasn't. Keeping the relative path makes what's
     * written mean what it says, and makes subdirectories under
     * {@code layouts/} addressable at all.
     *
     * @param raw the declared path; may be null
     * @return the template name, or null when {@code raw} is null or blank
     */
    public static String templateName(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        if (value.isEmpty()) return null;

        // An absolute path can't be addressed through the layouts-rooted
        // loader, and neither can one climbing out of it. Fall back to the
        // filename, which at least resolves when the file was copied into
        // layouts/ -- and fails with a message naming where we looked when not.
        if (value.startsWith("/") || value.contains("..")) {
            value = value.substring(value.lastIndexOf('/') + 1);
        }
        if (value.startsWith(LAYOUTS_DIR + "/")) {
            value = value.substring(LAYOUTS_DIR.length() + 1);
        }
        return stripExtension(value);
    }

    /**
     * The template name for an already-resolved absolute path, expressed
     * relative to the book's {@code layouts/} directory where it lies inside
     * it. For the config that stores a resolved {@link Path} rather than the
     * string the author wrote (an axis's {@code landingTemplate}).
     *
     * @param bookRoot     the book root, or null when unknown
     * @param templatePath the resolved template path
     * @return the template name
     */
    public static String templateName(Path bookRoot, Path templatePath) {
        Path layouts = bookRoot == null ? null : bookRoot.resolve(LAYOUTS_DIR).toAbsolutePath().normalize();
        Path resolved = templatePath.toAbsolutePath().normalize();
        if (layouts != null && resolved.startsWith(layouts)) {
            return stripExtension(layouts.relativize(resolved).toString().replace('\\', '/'));
        }
        return bareTemplateName(templatePath);
    }

    /** Strip a template file path down to the bare name Pebble's loader expects (no extension). */
    public static String bareTemplateName(Path templatePath) {
        return stripExtension(templatePath.getFileName().toString());
    }

    /** Drop a trailing {@code .ext}, leaving any directories in place. */
    private static String stripExtension(String value) {
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        return dot > slash + 1 ? value.substring(0, dot) : value;
    }
}
