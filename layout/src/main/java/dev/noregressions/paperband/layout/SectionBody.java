package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.number.SectionNumbering;

/**
 * A section's landing-page content, written as markdown rather than as a
 * template.
 *
 * <p>A landing template is layout; what a section has to say for itself is
 * writing. Making an author express the second as the first — a Pebble HTML
 * file, escaped and indented — is the wrong tool, and it is why "customise the
 * section page" used to mean writing HTML for what is a few paragraphs.
 *
 * <p>Declaring one <em>replaces</em> the landing page's default content: the
 * card grid is the fallback for a section that says nothing, not a fixture the
 * prose is squeezed in above. A section that wants both asks for it with
 * {@code cards: true} in the markdown's frontmatter, which is the rarer case
 * and reads as the deliberate choice it is.
 *
 * <p>The markdown goes through the same pipeline a card does, so
 * {@code {{ vars.x }}}, {@code {% if %}}, {@code {% include %}} and
 * {@code {% fragment %}} all work in it. The rendering happens in the build
 * (which owns the card loader); the layout only places the result.
 *
 * @param html      the rendered body, ready to emit; never null
 * @param title     the {@code # Heading} the markdown hoisted, or null — used as
 *                  the section's label when its folder declares no
 *                  {@code title:}, so one file can describe the section
 *                  completely instead of the heading silently vanishing
 * @param withCards true when the markdown's frontmatter asked for the default
 *                  card list to follow the prose
 * @param numbering how this section's cards are numbered, read from the same
 *                  frontmatter — {@code part:} to share a numbering group with
 *                  sibling sections, {@code numbered: false} to opt out
 *                  entirely. Never null: a section that says nothing about
 *                  numbering gets {@link SectionNumbering#discovered()}
 * @param partTitle the name of the part this section belongs to, from
 *                  {@code part_title:}, or null. Declared on any one section of
 *                  the part; it titles the part's own divider page, which is
 *                  emitted only where a part spans more than one section — a
 *                  part of one section is already announced by that section's
 *                  own divider, and a second page saying nearly the same thing
 *                  is worse than none
 */
public record SectionBody(
        String html, String title, boolean withCards, SectionNumbering numbering,
        String partTitle) {

    public SectionBody {
        numbering = numbering == null ? SectionNumbering.discovered() : numbering;
        partTitle = (partTitle == null || partTitle.isBlank()) ? null : partTitle.trim();
    }
}
