package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Top-level book configuration parsed from the {@code paperband.yaml} at the
 * book root. Folder-level configs are merged into per-card {@link RenderContext}
 * objects and don't appear here.
 *
 * @param bookRoot  absolute path of the book root directory; resolves relative paths inside the config
 * @param title     book title used in metadata and as a fallback for cover and index pages
 * @param axes      categorical axes declared for the book; drives tier dividers, landing pages and the site grouping
 * @param parts     declared parts of the book from a root {@code parts:} key — each a titled
 *        group of top-level folders (see {@link Part}). Empty when the book declares none,
 *        in which case top-level grouping stays fully discovered: every top-level folder is
 *        its own "section". Parts and discovered sections share one id namespace and one
 *        rendering path, and folders no part claims remain discovered sections, so declaring
 *        parts is additive rather than a mode switch.
 * @param globalCss CSS files applied to every card before any folder-level or per-card stylesheets
 * @param vars      free-form variables exposed to templates as {@code vars}
 * @param targets   names of declared build targets (e.g. {@code pdf-a4}, {@code pdf-6x9}, {@code web})
 * @param theme     default theme name for the book; the CLI {@code --theme} flag overrides this when supplied
 * @param sectionLandingTemplate  book-wide default bare Pebble template name for a
 *        "section" (a folder of cards with no value on any declared axis) landing page,
 *        from a root {@code sections: { landing: { template: <name-or-path> } }} key;
 *        null if not declared, in which case the built-in {@code site-section} template
 *        is used. Already resolved by {@link NamedTemplates#resolveSectionTemplate} —
 *        either a built-in preset name (e.g. {@code "minimal"} → {@code site-section-minimal})
 *        or the bare filename (extension stripped) of a custom template path. A section's
 *        own folder {@code paperband.yaml} can override this per-folder with its own
 *        {@code landing: { template: <name-or-path> } } key (highest priority); see
 *        {@code LayoutEngine.buildSectionMetas}.
 * @param cardSchema  mapping that lets pure-YAML card files ({@code *.yaml}) be
 *        transpiled into markdown cards at load time, from a root
 *        {@code cardSchema:} key; null when the book doesn't declare one, in
 *        which case only {@code *.md} files are treated as cards. See
 *        {@link CardSchema} for the shape and semantics.
 * @param cover  front-cover declaration from a root {@code cover:} key (image
 *        and/or custom template — see {@link PageMatter}); null when not
 *        declared, in which case the built-in text cover renders (title,
 *        subtitle, series, author via {@code vars}) as before.
 * @param back   back-page declaration from a root {@code back:} key, same
 *        shape as {@code cover}; null when not declared, in which case the
 *        book simply ends after the last card as before.
 * @param footer running page-footer declaration from a root
 *        {@code footer: { template: ... }} key, same {@link PageMatter}
 *        shape as {@code cover}/{@code back} ({@code image} is meaningless/
 *        unused here — a footer is always a small custom template, never a
 *        bare image); null when not declared, in which case no running
 *        footer renders. Unlike cover/back, the declared template is also
 *        pre-rendered standalone (see {@code LayoutEngine.renderFooter}) and
 *        handed to the renderer out-of-band via {@code HtmlInput.footerHtml}
 *        — Playwright's {@code Page.pdf()} header/footer option is the only
 *        way Chromium's print engine can repeat content on every page (no
 *        CSS Paged Media support at all), and that option is a totally
 *        separate mini-document with no access to the main page's
 *        stylesheet. Needs real page-margin space to render into — see
 *        {@code vars.page.margins} ({@link dev.noregressions.paperband.render.PageConfigResolver}).
 * @param header running page-header declaration from a root
 *        {@code header: { template: ... }} key — same shape, same
 *        pre-rendering path ({@code LayoutEngine.renderHeader} ->
 *        {@code HtmlInput.headerHtml}), and the same real top-margin-space
 *        requirement as {@code footer}; null when not declared, in which
 *        case no running header renders.
 */
public record BookConfig(
        Path bookRoot,
        String title,
        List<Axis> axes,
        List<Path> globalCss,
        Map<String, Object> vars,
        List<String> targets,
        String theme,
        String sectionLandingTemplate,
        CardSchema cardSchema,
        PageMatter cover,
        PageMatter back,
        PageMatter footer,
        PageMatter header,
        List<Part> parts
) {

    public BookConfig {
        axes      = axes      == null ? List.of() : List.copyOf(axes);
        globalCss = globalCss == null ? List.of() : List.copyOf(globalCss);
        vars      = vars      == null ? Map.of()  : Map.copyOf(vars);
        targets   = targets   == null ? List.of() : List.copyOf(targets);
        parts     = parts     == null ? List.of() : List.copyOf(parts);
        theme     = (theme == null || theme.isBlank()) ? null : theme.trim();
    }

    /** Convenience constructor for books with no {@code cardSchema:}, {@code cover:}, {@code back:}, {@code footer:} or {@code header:} (the common case). */
    public BookConfig(
            Path bookRoot,
            String title,
            List<Axis> axes,
            List<Path> globalCss,
            Map<String, Object> vars,
            List<String> targets,
            String theme,
            String sectionLandingTemplate) {
        this(bookRoot, title, axes, globalCss, vars, targets, theme, sectionLandingTemplate,
                null, null, null, null, null, List.of());
    }

    /** Convenience constructor for books with a {@code cardSchema:} but no {@code cover:}/{@code back:}/{@code footer:}/{@code header:}. */
    public BookConfig(
            Path bookRoot,
            String title,
            List<Axis> axes,
            List<Path> globalCss,
            Map<String, Object> vars,
            List<String> targets,
            String theme,
            String sectionLandingTemplate,
            CardSchema cardSchema) {
        this(bookRoot, title, axes, globalCss, vars, targets, theme, sectionLandingTemplate,
                cardSchema, null, null, null, null, List.of());
    }

    /**
     * Convenience constructor for books that declare no {@code parts:} — the
     * shape of the canonical constructor before parts existed, kept so
     * existing callers stay source-compatible.
     */
    public BookConfig(
            Path bookRoot,
            String title,
            List<Axis> axes,
            List<Path> globalCss,
            Map<String, Object> vars,
            List<String> targets,
            String theme,
            String sectionLandingTemplate,
            CardSchema cardSchema,
            PageMatter cover,
            PageMatter back,
            PageMatter footer,
            PageMatter header) {
        this(bookRoot, title, axes, globalCss, vars, targets, theme, sectionLandingTemplate,
                cardSchema, cover, back, footer, header, List.of());
    }

    /**
     * A copy of this config with its {@code parts} replaced. Used when a
     * book's top-level structure is declared somewhere other than the root
     * {@code paperband.yaml} — the Maven plugin's {@code <book><parts>}
     * element resolves globs into {@link Part}s and injects them here, so the
     * rest of the pipeline sees a book that declares parts and can't tell the
     * difference.
     *
     * @param newParts the parts to declare; null or empty clears them
     * @return a copy carrying {@code newParts}
     */
    public BookConfig withParts(List<Part> newParts) {
        return new BookConfig(bookRoot, title, axes, globalCss, vars, targets, theme,
                sectionLandingTemplate, cardSchema, cover, back, footer, header, newParts);
    }

    public static BookConfig empty(Path bookRoot) {
        return new BookConfig(bookRoot, null, List.of(), List.of(), Map.of(), List.of(),
                null, null, null, null, null, null, null, List.of());
    }
}
