package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.Watermark;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code <watermark>} element: a stamp declared as a block, mirroring the
 * {@code vars.watermark} map in a book's yaml.
 *
 * <pre>
 * &lt;watermark&gt;
 *   &lt;text&gt;REVIEW COPY&lt;/text&gt;
 *   &lt;color&gt;#aa0000&lt;/color&gt;
 *   &lt;opacity&gt;0.15&lt;/opacity&gt;
 *   &lt;angle&gt;-45&lt;/angle&gt;
 *   &lt;tile&gt;true&lt;/tile&gt;
 * &lt;/watermark&gt;
 * </pre>
 *
 * <p>The element names are the yaml keys, so a declaration can be moved between
 * the two by hand without a translation table. The one difference Maven forces
 * is {@code <fontSize>} for yaml's {@code font_size}: element names bind to
 * field names, and a field can't be called {@code font_size} without reading
 * like it came from somewhere else.
 *
 * <p>The flat {@code <watermarkColor>} family still exists alongside this, and
 * still wins — those carry the {@code -Dpaperband.watermark*} properties, so a
 * command line can retune a stamp the POM declares without editing it. The
 * block is the declaration; the flat parameters are the overrides.
 */
public class WatermarkConfig {

    /** The text to stamp. Exactly one of {@code <text>} / {@code <image>}. */
    private String text;

    /** A book-root-relative image to stamp instead of text. */
    private String image;

    /** TrueType font to embed, for text Helvetica can't set. PDF only. */
    private String font;

    /** Fill colour as {@code #RRGGBB}. */
    private String color;

    /** Fill alpha, 0 to 1. */
    private Float opacity;

    /** Rotation in degrees. */
    private Float angle;

    /** Font size in points; a ceiling unless {@code <fit>} is false. Yaml spells it {@code font_size}. */
    private Integer fontSize;

    /** Helvetica-Bold rather than Helvetica. */
    private Boolean bold;

    /** For an image, its width as a fraction of the page width. */
    private Float scale;

    /** Shrink the stamp until it fits the page. */
    private Boolean fit;

    /** Draw underneath the page content rather than over it. */
    private Boolean behind;

    /** Repeat across the page rather than centring one stamp. */
    private Boolean tile;

    /** {@code all}, {@code first}, or {@code except-cover}. PDF only. */
    private String pages;

    /**
     * The bare-string shorthand: {@code <watermark>DRAFT</watermark>}, the
     * POM's spelling of yaml's {@code watermark: "DRAFT"}.
     *
     * <p>Maven's configurator calls a method named exactly {@code set} when an
     * element bound to a bean carries text instead of children — without it the
     * shorthand fails with "Cannot find default setter in class
     * WatermarkConfig", which tells an author nothing about what to write
     * instead. Both spellings of the same declaration therefore work, as they
     * do in the yaml.
     *
     * @param value the text to stamp
     */
    public void set(String value) {
        this.text = value;
    }

    /**
     * True when this block names something to stamp. A block carrying only
     * knobs — {@code <watermark><opacity>0.3</opacity></watermark>} — declares
     * no watermark, exactly as a yaml map with no {@code text:} declares none;
     * it can still retune one the book's yaml declared.
     *
     * @return whether the block has a subject
     */
    boolean hasSubject() {
        return (text != null && !text.isBlank()) || (image != null && !image.isBlank());
    }

    /**
     * What this block stamps, with no knobs applied — the knobs travel
     * separately via {@link #knobs()} so that a block, the flat parameters and
     * the book's yaml can all contribute to one spec in a fixed order.
     *
     * @return the bare spec, or null when the block names no text and no image
     * @throws MojoExecutionException if the block declares both a text and an image
     */
    Watermark subject() throws MojoExecutionException {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isBlank();
        if (hasText && hasImage) {
            throw new MojoExecutionException(
                    "<watermark> declares both <text> and <image> — pick one "
                            + "(a logo with wording in it is an image).");
        }
        if (hasText) return Watermark.withDefaults(text);
        if (hasImage) return Watermark.imageWithDefaults(image);
        return null;
    }

    /**
     * This block's knobs alone, for layering over a spec the book's yaml
     * declared — the {@code <watermark><opacity>} case above.
     *
     * @return the overrides
     * @throws MojoExecutionException if {@code <pages>} names no known selection
     */
    Watermark.Overrides knobs() throws MojoExecutionException {
        return Watermarks.overrides(color, opacity, angle, fontSize, bold,
                scale, fit, behind, tile, pages, font);
    }

    @Override
    public String toString() {
        return "<watermark>" + (hasSubject() ? (text != null ? text : image) : "(knobs only)")
                + "</watermark>";
    }
}
