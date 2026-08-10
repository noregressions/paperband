package dev.noregressions.paperband.render;

import java.util.Objects;

/**
 * Page geometry hint for a render. Renderers should honour this where possible
 * but may ignore parts of it (commonly margins, when the renderer relies on
 * {@code @page} CSS rules instead). Renderers must document any such limitation
 * in {@link HtmlToPdfRenderer#description()}.
 */
public record PageSpec(PageSize size, Margins margins, Orientation orientation) {

    public PageSpec {
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(margins, "margins");
        Objects.requireNonNull(orientation, "orientation");
    }

    public static PageSpec a4() {
        return new PageSpec(PageSize.A4, Margins.standard(), Orientation.PORTRAIT);
    }

    public static PageSpec letter() {
        return new PageSpec(PageSize.LETTER, Margins.standard(), Orientation.PORTRAIT);
    }

    /**
     * A5 with zero page margins: the intended use is print card/booklet
     * themes (each card one 148×210 sheet) whose bands run full-bleed and
     * carry their own internal padding. Chromium's Page.pdf() margins
     * override any {@code @page} margin CSS, so a nonzero default here
     * would make full-bleed designs impossible; zero lets the theme decide.
     */
    public static PageSpec a5() {
        return new PageSpec(PageSize.A5, Margins.uniform(0, Unit.MM), Orientation.PORTRAIT);
    }

    public static PageSpec booklet6x9() {
        return new PageSpec(
                PageSize.of(6, 9, Unit.INCH),
                Margins.uniform(15, Unit.MM),
                Orientation.PORTRAIT);
    }

    /**
     * A compact tech-book trim — 7.5&times;9.25in (190.5&times;235mm) — the
     * size found on Packt Publishing's printed paperbacks (confirmed against
     * two Packt titles' listed dimensions: 23.5&times;19.1cm). Noticeably
     * "stubbier" than a 6x9 novel trim: nearly as wide as A4 but much
     * shorter, so code samples get more horizontal room per line without
     * the page reading as oversized. Margins split the difference between
     * {@link #booklet6x9()}'s 15mm and A4's 20mm, proportional to the
     * trim's in-between width.
     */
    public static PageSpec packt() {
        return new PageSpec(
                PageSize.of(7.5, 9.25, Unit.INCH),
                Margins.uniform(18, Unit.MM),
                Orientation.PORTRAIT);
    }

    /**
     * Resolve one of the CLI's page-size slugs ({@code a4}, {@code a5},
     * {@code letter}, {@code 6x9}, {@code packt} — case-insensitive) to its factory preset.
     * Shared by {@code ConfigLoader} (to seed the base a {@code page:} yaml
     * override layers on top of) and the CLI commands, so both sides agree
     * on the same slug-to-preset mapping without duplicating the switch.
     */
    public static PageSpec forSizeName(String slug) {
        if (slug == null) return a4();
        return switch (slug.trim().toLowerCase()) {
            case "a4"     -> a4();
            case "a5"     -> a5();
            case "letter" -> letter();
            case "6x9"    -> booklet6x9();
            case "packt", "7.5x9.25" -> packt();
            default -> throw new IllegalArgumentException("Unknown page size slug: " + slug);
        };
    }

    /**
     * The printable content-box height in millimetres: the resolved page's
     * vertical dimension (width/height swap under {@link Orientation#LANDSCAPE},
     * matching how {@code Page.pdf()}'s {@code setLandscape} rotates the page)
     * minus top and bottom margins.
     *
     * <p>This is the single source of truth a theme's CSS should reference
     * (via the {@code --pw-content-height} custom property {@code LayoutEngine}
     * stamps onto {@code <html>}) instead of hardcoding a full-page height —
     * a theme that assumes zero margins by hardcoding e.g. {@code height: 209mm}
     * for a full-bleed A5 divider/cover page silently overflows onto a second
     * page the moment a book's {@code vars.page.margins} adds real top/bottom
     * margins on top of that theme's own internal padding, since the actual
     * printable area shrank but the CSS didn't know to shrink with it.
     */
    public double contentHeightMm() {
        double pageHeightMm = orientation() == Orientation.LANDSCAPE
                ? size().unit().toMillimetres(size().width())
                : size().unit().toMillimetres(size().height());
        double marginsMm = margins().unit().toMillimetres(margins().top())
                + margins().unit().toMillimetres(margins().bottom());
        return pageHeightMm - marginsMm;
    }
}
