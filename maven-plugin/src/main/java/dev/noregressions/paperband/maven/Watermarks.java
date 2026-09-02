package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.Watermark;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Turns a goal's flat watermark parameters into a {@link Watermark}.
 *
 * <p>Three goals declare the same knobs — {@code build}, {@code site} and
 * {@code render} — because Maven binds parameters per mojo and a shared base
 * class would hang {@code <watermarkAngle>} off {@code paperband:themes} too.
 * The declarations have to be repeated; the meaning of them does not, and that
 * is what lives here. A build that stamped {@code except-cover} and a site that
 * quietly ignored the same word would be worse than either goal not supporting
 * it at all.
 */
final class Watermarks {

    private Watermarks() {}

    /**
     * The base spec a goal's parameters declare, before any book yaml is
     * consulted: the flat pair if it names anything, else the {@code <watermark>}
     * block's subject.
     *
     * <p>The flat pair wins because it is the command line's channel —
     * {@code -Dpaperband.watermark="REVIEW COPY"} has to be able to restamp a
     * book whose POM declares a block, the same way the block restamps a book
     * whose yaml declares one.
     *
     * @param block the {@code <watermark>} element, or null
     * @param text  {@code <watermarkText>} / {@code -Dpaperband.watermark}
     * @param image {@code <watermarkImage>}
     * @return the spec, or null when nothing named a subject
     * @throws MojoExecutionException if a declaration is unusable
     */
    static Watermark base(WatermarkConfig block, String text, String image)
            throws MojoExecutionException {
        Watermark flat = base(text, image);
        if (flat != null) return flat;
        return block == null ? null : block.subject();
    }

    /**
     * The knobs a goal's parameters declare, block first and the flat
     * parameters over the top — so a POM block sets the house style and a
     * {@code -D} retunes it for one run.
     *
     * @param block the {@code <watermark>} element, or null
     * @return the merged overrides
     * @throws MojoExecutionException if a value can't be used
     */
    static Watermark.Overrides overrides(
            WatermarkConfig block, String color, Float opacity, Float angle, Integer fontSize,
            Boolean bold, Float scale, Boolean fit, Boolean behind, Boolean tile, String pages,
            String font) throws MojoExecutionException {
        Watermark.Overrides flat = overrides(color, opacity, angle, fontSize, bold,
                scale, fit, behind, tile, pages, font);
        return block == null ? flat : block.knobs().then(flat);
    }

    /**
     * The base spec the flat parameters alone declare, before any book yaml is
     * consulted.
     *
     * @param text  {@code <watermark>}
     * @param image {@code <watermarkImage>}
     * @return the spec, or null when the goal named neither
     * @throws MojoExecutionException if both were named
     */
    static Watermark base(String text, String image) throws MojoExecutionException {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isBlank();
        if (hasText && hasImage) {
            throw new MojoExecutionException(
                    "Configure either <watermark> or <watermarkImage>, not both.");
        }
        if (hasText) return Watermark.withDefaults(text);
        if (hasImage) return Watermark.imageWithDefaults(image);
        return null;
    }

    /**
     * The per-knob overrides, as one bundle to layer over whichever base won.
     *
     * @param pages the {@code <watermarkPages>} spelling, validated here so the
     *              build fails at configuration rather than after rendering
     * @return the overrides; unset parameters stay null and change nothing
     * @throws MojoExecutionException if a value can't be used
     */
    static Watermark.Overrides overrides(
            String color, Float opacity, Float angle, Integer fontSize, Boolean bold,
            Float scale, Boolean fit, Boolean behind, Boolean tile, String pages, String font)
            throws MojoExecutionException {
        try {
            Watermark.Pages selection = pages == null || pages.isBlank()
                    ? null : Watermark.Pages.parse(pages);
            return new Watermark.Overrides(color, opacity, angle, fontSize, bold,
                    scale, fit, behind, tile, selection, font);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
