package dev.noregressions.paperband.render;

import java.net.URI;
import java.util.Objects;

/**
 * Input to a {@link HtmlToPdfRenderer}.
 *
 * @param html       the full HTML document as a string
 * @param baseUri    base URI used to resolve relative URLs (CSS, images, fonts).
 *                   Use a {@code file:} URI when assets live on the local filesystem
 * @param pageSpec   page size, margins, orientation
 * @param metadata   PDF metadata (title, author, etc.)
 * @param footerHtml a self-contained HTML fragment (inline styles only — no
 *                   access to the main document's stylesheet) to repeat in a
 *                   footer margin band on every PDF page; null for no footer.
 *                   From a book root {@code footer: { template: ... }} key,
 *                   pre-rendered once by {@code LayoutEngine.renderFooter}.
 *                   Playwright is the only renderer, and Chromium's print
 *                   engine has no CSS Paged Media support at all (no
 *                   {@code @page { @bottom-center } }) — the one way to get a
 *                   running footer out of it is {@code Page.pdf()}'s own
 *                   header/footer option, a totally separate isolated
 *                   mini-document with no access to {@code html}'s
 *                   stylesheet, hence {@code footerHtml} needing to be
 *                   self-contained. Needs real page-margin space to render
 *                   into — see {@code PageSpec.margins}.
 * @param headerHtml same shape and same constraints as {@code footerHtml}
 *                   (self-contained, inline-styled, needs real top-margin
 *                   space), but for a running header band; from a book root
 *                   {@code header: { template: ... }} key, pre-rendered by
 *                   {@code LayoutEngine.renderHeader}; null for no header.
 */
public record HtmlInput(
        String html, URI baseUri, PageSpec pageSpec, PdfMetadata metadata,
        String footerHtml, String headerHtml) {

    public HtmlInput {
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(pageSpec, "pageSpec");
        Objects.requireNonNull(metadata, "metadata");
        footerHtml = (footerHtml == null || footerHtml.isBlank()) ? null : footerHtml;
        headerHtml = (headerHtml == null || headerHtml.isBlank()) ? null : headerHtml;
    }

    /** Convenience constructor for the common case of no running header/footer. */
    public HtmlInput(String html, URI baseUri, PageSpec pageSpec, PdfMetadata metadata) {
        this(html, baseUri, pageSpec, metadata, null, null);
    }
}
