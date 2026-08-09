package dev.noregressions.paperband.model;

/**
 * Declares a book's front-cover or back-page content, from a {@code cover:}
 * or {@code back:} key in the book root {@code paperband.yaml}:
 *
 * <pre>
 * cover:
 *   image: images/front.png        # rendered full-page by _book-cover
 * back:
 *   template: layouts/cta.html    # custom Pebble template instead
 * </pre>
 *
 * <p>A bare string value is shorthand for {@code image:}
 * ({@code cover: images/front.png}).
 *
 * <p>Semantics (implemented by {@code book.html} in {@code layout}):
 * {@code template:} wins when both are set — it replaces the built-in
 * {@code _book-cover} / {@code _book-back} include entirely (the {@code book}
 * model, including {@code book.cover.image} / {@code book.back.image}, is
 * still passed in, so a custom template can use the declared image too).
 * With only {@code image:} the built-in template renders the image full-page.
 * Everything stays in the single rendered HTML document — one pass through
 * the ordinary {@code HtmlToPdfRenderer}, no post-processing — so the image
 * sits inside the page margins defined by the build's {@code PageSpec};
 * themes or book CSS can tighten that with {@code @page :first} rules where
 * the renderer supports them.
 *
 * @param image    image path relative to the book root; null when only a template is declared
 * @param template custom Pebble template, stored as the bare loader name
 *                 (resolved from a path relative to the book root, extension
 *                 stripped — same convention as section landing templates);
 *                 null to use the built-in
 */
public record PageMatter(String image, String template) {

    /** True when neither an image nor a template was declared. */
    public boolean isEmpty() {
        return (image == null || image.isBlank())
                && (template == null || template.isBlank());
    }
}
