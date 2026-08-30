package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.NamedTemplates;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The two keys a section folder's own {@code paperband.yaml} contributes to
 * the site's section landing pages: its {@code title} and its
 * {@code landing.template} override.
 *
 * <p>Why this is read on its own rather than through {@link ConfigLoader}'s
 * cascade: section grouping happens <em>after</em> the whole book's cards are
 * loaded, so there is no card whose parent chain could carry it. The cascade
 * answers "what applies to this card"; this answers "what does this folder
 * call itself", and only the folder can say.
 *
 * <p>It lives here, next to the cascade, because it reads {@code paperband.yaml}
 * — and every component that reads a paperband.yaml belongs in one module, so
 * the file format has one reader and one set of rules rather than a second
 * private copy inside the layout engine.
 *
 * @param title           the folder's declared title, or null
 * @param landingTemplate the folder's resolved landing template, or null
 */
public record SectionFolderConfig(String title, String landingTemplate) {

    /** Nothing declared — the caller falls back to an auto-formatted label and the default template. */
    public static final SectionFolderConfig EMPTY = new SectionFolderConfig(null, null);

    /**
     * Read section {@code sectionId}'s folder yaml.
     *
     * <p>Looks in {@code <contentRoot>/<id>/} first. When the caller's root is
     * the book <em>home</em> rather than its content root — the legacy
     * geography, where the two are the same directory and nothing said
     * otherwise — a {@code content/} subdirectory is tried as well, so a book
     * laid out the conventional way resolves either way round.
     *
     * @param contentRoot the directory section folders sit in; null returns {@link #EMPTY}
     * @param sectionId   the section's folder name
     * @return the folder's config, or {@link #EMPTY} when absent or unreadable
     */
    public static SectionFolderConfig read(Path contentRoot, String sectionId) {
        if (contentRoot == null || sectionId == null) return EMPTY;
        SectionFolderConfig info = readFile(
                contentRoot, contentRoot.resolve(sectionId).resolve("paperband.yaml"));
        if (info != null) return info;
        info = readFile(
                contentRoot, contentRoot.resolve("content").resolve(sectionId).resolve("paperband.yaml"));
        return info != null ? info : EMPTY;
    }

    /** Parse one folder yaml, or null when it's absent, unreadable, or declares neither key. */
    private static SectionFolderConfig readFile(Path root, Path yamlFile) {
        if (yamlFile == null || !Files.isRegularFile(yamlFile)) return null;
        try (Reader r = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
            Object data = new Yaml().load(r);
            if (!(data instanceof Map<?, ?> map)) return null;
            Object titleNode = map.get("title");
            String title = titleNode == null ? null : titleNode.toString();
            String landingTemplate = null;
            if (map.get("landing") instanceof Map<?, ?> lm) {
                Object t = lm.get("template");
                if (t != null) landingTemplate = NamedTemplates.resolveSectionTemplate(root, t.toString());
            }
            return (title == null && landingTemplate == null)
                    ? null
                    : new SectionFolderConfig(title, landingTemplate);
        } catch (IOException | RuntimeException ignored) {
            // Malformed or unreadable folder yaml shouldn't break the whole site
            // build — fall back to the auto-formatted label and default template.
            return null;
        }
    }
}
