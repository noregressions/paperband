package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A single card loaded from a markdown file. Identity comes from the
 * frontmatter {@code id}, falling back to the file basename.
 *
 * <p>Cards are immutable; the body is decomposed into {@link Block}s during
 * load time and the title is hoisted out of the body's first heading by the
 * card loader.
 *
 * @param id          stable identifier; from frontmatter {@code id} if present, otherwise the source file basename
 * @param source      path to the markdown file the card was loaded from
 * @param frontmatter parsed YAML frontmatter
 * @param title       card title; the first level-one heading from the body, or null when none was found
 * @param blocks      ordered list of body blocks, in document order
 */
public record Card(
        String id,
        Path source,
        Frontmatter frontmatter,
        String title,
        List<Block> blocks
) {

    public Card {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
