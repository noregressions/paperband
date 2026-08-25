package dev.noregressions.paperband.model;

/**
 * Declares a book's front-cover or back-page content, from a {@code cover:}
 * or {@code back:} key in the book root {@code paperband.yaml}:
 *
 * <pre>
 * cover:
 *   image: images/front.png        # rendered full-page by _book-cover
 *   text: true                     # book title/subtitle/series/author over it
 * back:
 *   template: layouts/cta.html    # custom Pebble template instead
 * </pre>
 *
 * <p>A bare string value is shorthand for {@code image:}
 * ({@code cover: images/front.png}).
 *
 * <p>The text fields put words on the cover without a custom template. Each
 * falls back to the book's own value ({@code title:}, {@code vars.subtitle},
 * {@code vars.series}, {@code vars.author}) when unset, so
 * {@code text: true} alone overlays the standard text block on the artwork,
 * while a declared field overrides just that line:
 *
 * <pre>
 * cover:
 *   image: images/front.png
 *   subtitle: "2026 edition"       # this line differs from vars.subtitle
 * </pre>
 *
 * <p>Semantics (implemented by {@code book.html} in {@code layout}):
 * {@code template:} wins when both are set — it replaces the built-in
 * {@code _book-cover} / {@code _book-back} include entirely (the {@code book}
 * model, including every field here, is still passed in, so a custom template
 * can use them too). With an {@code image:} the built-in renders it
 * full-page, overlaying the text block when {@code text: true} or any text
 * field is declared; with no image the text block <em>is</em> the cover.
 * Everything stays in the single rendered HTML document — one pass through
 * the ordinary {@code HtmlToPdfRenderer}, no post-processing — so the image
 * sits inside the page margins defined by the build's {@code PageSpec};
 * themes or book CSS can tighten that with {@code @page :first} rules where
 * the renderer supports them.
 *
 * @param image    image path relative to the book root; null when not declared
 * @param template custom Pebble template, stored as the bare loader name
 *                 (resolved from a path relative to the book root, extension
 *                 stripped — same convention as section landing templates);
 *                 null to use the built-in
 * @param title    cover title line, overriding the book's {@code title:}; null to inherit
 * @param subtitle cover subtitle line, overriding {@code vars.subtitle}; null to inherit
 * @param series   cover series line, overriding {@code vars.series}; null to inherit
 * @param author   cover author line, overriding {@code vars.author}; null to inherit
 * @param text     render the text block even over an image; implied true when
 *                 any text field above is declared, or when there's no image
 * @param fullPage cover only: fill the entire sheet, trim edge to trim edge.
 *                 Emits {@code @page :first { margin: 0 }}, which Chromium
 *                 honours over the build's page margins for the first page
 *                 alone — and since Chromium draws running headers/footers
 *                 inside the margin boxes, they're suppressed on the cover
 *                 page automatically. The image scales to cover the sheet
 *                 ({@code object-fit: cover}). First-page-only is exactly
 *                 what CSS can express ({@code :first} exists, {@code :last}
 *                 doesn't), so {@code back:} can't take this flag.
 */
public record PageMatter(String image, String template,
                         String title, String subtitle, String series, String author,
                         boolean text, boolean fullPage) {

    /** Image and/or template only — no cover-level text. */
    public PageMatter(String image, String template) {
        this(image, template, null, null, null, null, false, false);
    }

    /** Text-capable form without the full-page flag. */
    public PageMatter(String image, String template,
                      String title, String subtitle, String series, String author,
                      boolean text) {
        this(image, template, title, subtitle, series, author, text, false);
    }

    /** True when nothing at all was declared. */
    public boolean isEmpty() {
        return blank(image) && blank(template) && !hasText();
    }

    /** True when this declaration asks for a text block or overrides any of its lines. */
    public boolean hasText() {
        return text || !blank(title) || !blank(subtitle) || !blank(series) || !blank(author);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
