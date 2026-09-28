package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.layout.SectionBody;
import dev.noregressions.paperband.model.RenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A section body is loaded the way a card is.
 *
 * <p>It used to be parsed with a bare {@code CardLoader}, so a book's own
 * {@code layouts/blocks/<type>.html} never reached it: the same fence rendered
 * as the book's figure in a card and as a plain code listing in
 * {@code _section.md}.
 */
@DisplayName("Section bodies")
class SectionBodiesTest {

    @Test
    @DisplayName("render ```type fences through the book's block templates")
    void bookBlockTemplatesApply(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "title: T\n");
        Files.createDirectories(root.resolve("layouts/blocks"));
        Files.writeString(root.resolve("layouts/blocks/tree.html"),
                "<figure class=\"tree\">{{ content }}</figure>");
        Files.writeString(root.resolve("_section.md"), "# Book\n\n```tree\na/b\n```\n");
        Files.writeString(root.resolve("card.md"), "# Card\n\nText.\n");

        RenderContext ctx = new ConfigLoader().load(root.resolve("card.md"), "pdf-a4", "a4");
        BlockTemplates templates = new BlockTemplates(null, root.resolve("layouts"), null, root);

        Map<String, SectionBody> bodies = SectionBodies.render(
                ctx, root.resolve("layouts"), Map.of(), List.of(), "site", "web", templates, null);

        String html = bodies.get(SectionBodies.BOOK).html();
        assertTrue(html.contains("<figure class=\"tree\">"), html);
    }
}
