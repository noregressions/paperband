package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.NamedTemplates;
import dev.noregressions.paperband.model.PageMatter;

import java.nio.file.Path;

/**
 * One of a book's standalone pages or running fixtures — {@code <cover>},
 * {@code <back>}, {@code <header>}, {@code <footer>} — declared in the POM
 * instead of the book's yaml.
 *
 * <pre>
 * &lt;cover&gt;
 *   &lt;image&gt;covers/front.png&lt;/image&gt;
 * &lt;/cover&gt;
 * &lt;footer&gt;
 *   &lt;template&gt;layouts/footer.html&lt;/template&gt;
 * &lt;/footer&gt;
 * </pre>
 *
 * <p>Exactly the two things a {@link PageMatter} carries: a full-page image, or
 * a template rendered with the book's context. Declare one or the other — both
 * empty is a mistake worth reporting rather than a page that silently doesn't
 * appear.
 */
public class PageMatterConfig {

    /** Image path, relative to the book root. */
    private String image;

    /** Template path, relative to the book root, or a built-in template name. */
    private String template;

    /** @return the image path, or null */
    public String getImage() {
        return image;
    }

    /** @return the template path, or null */
    public String getTemplate() {
        return template;
    }

    /**
     * Translate into the model's {@link PageMatter}, resolving a template path
     * to the bare loader name the same way a yaml-declared one is.
     *
     * @param bookRoot the book root templates resolve against
     * @param element  the element name, for error messages
     * @return the page matter
     * @throws IllegalArgumentException when neither image nor template is declared
     */
    PageMatter toPageMatter(Path bookRoot, String element) {
        boolean hasImage = image != null && !image.isBlank();
        boolean hasTemplate = template != null && !template.isBlank();
        if (!hasImage && !hasTemplate) {
            throw new IllegalArgumentException(
                    "<" + element + "> declares neither <image> nor <template>");
        }
        return new PageMatter(
                hasImage ? image.trim() : null,
                hasTemplate ? NamedTemplates.templateName(template.trim()) : null);
    }
}
