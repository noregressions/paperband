package dev.noregressions.paperband.layout;

/**
 * Which output a render is for, when that changes the CSS a theme contributes.
 *
 * <p>A theme describes one design across two very different media. Most of it
 * is genuinely shared — colour, type family and ratios, code styling, the
 * meaning of a warning block — but some of it is not: a printed page has a
 * fixed trim, a measure chosen for paper and a density tuned to it, while a
 * site has a viewport, a sidebar and room for a table to be wider than the
 * prose beside it. Rules written for one leak into the other, which is how a
 * site ends up rendering at a 10.5pt page scale.
 *
 * <p>A theme's {@code manifest.txt} names which target each stylesheet is for;
 * unprefixed entries are shared. See {@link ThemeResolver}.
 */
public enum Target {

    /** Paged output: the PDF build. */
    PRINT,

    /** The static site. */
    SITE
}
