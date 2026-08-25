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
 *   &lt;text&gt;true&lt;/text&gt;                &lt;!-- title/subtitle/series/author over it --&gt;
 * &lt;/cover&gt;
 * &lt;footer&gt;
 *   &lt;template&gt;layouts/footer.html&lt;/template&gt;
 * &lt;/footer&gt;
 * </pre>
 *
 * <p>Mirrors {@link PageMatter}: a full-page image, a template rendered with
 * the book's context, and/or the cover's own text lines. The text elements
 * ({@code <title>}, {@code <subtitle>}, {@code <series>}, {@code <author>})
 * each fall back to the book's own value when unset, so {@code <text>true</text>}
 * alone overlays the standard text block on the artwork, while a declared
 * element overrides just that line. Declaring nothing at all is a mistake
 * worth reporting rather than a page that silently doesn't appear.
 */
public class PageMatterConfig {

    /** Image path, relative to the book root. */
    private String image;

    /** Template path, relative to the book root, or a built-in template name. */
    private String template;

    /** Cover title line, overriding the book's title. */
    private String title;

    /** Cover subtitle line, overriding {@code vars.subtitle}. */
    private String subtitle;

    /** Cover series line, overriding {@code vars.series}. */
    private String series;

    /** Cover author line, overriding the declared authorship. */
    private String author;

    /** Render the text block even over an image; implied by any text element above. */
    private Boolean text;

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
     * @throws IllegalArgumentException when the element declares nothing at all
     */
    PageMatter toPageMatter(Path bookRoot, String element) {
        PageMatter matter = new PageMatter(
                trimmed(image),
                template == null || template.isBlank()
                        ? null : NamedTemplates.templateName(template.trim()),
                trimmed(title), trimmed(subtitle), trimmed(series), trimmed(author),
                Boolean.TRUE.equals(text));
        if (matter.isEmpty()) {
            throw new IllegalArgumentException(
                    "<" + element + "> declares neither <image>, <template> nor any text element");
        }
        return matter;
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
