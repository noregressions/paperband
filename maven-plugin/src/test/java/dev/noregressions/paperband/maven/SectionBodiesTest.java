package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.layout.SectionBody;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Section;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** A declared section over one folder that has a body, with and without the folder's name as its id. */
    private static List<String> warnings(Path root, String id) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "title: T\n");
        Path folder = Files.createDirectories(root.resolve("04-workshop"));
        Files.writeString(folder.resolve("_section.md"), "# Workshop\n\nThree sessions.\n");
        Path card = Files.writeString(folder.resolve("01-prepare.md"), "# Prepare\n\nText.\n");
        RenderContext ctx = new ConfigLoader().load(card, "pdf-a4", "a4");
        ctx = ctx.withBook(ctx.book().withSections(List.of(
                new Section(id, "Workshop", List.of(), null, List.of(card)))));
        List<String> warned = new ArrayList<>();
        SectionBodies.render(ctx, null, Map.of(), List.of(), "print", "pdf", null, new SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warned.add(content.toString());
            }
        });
        return warned;
    }

    @Test
    @DisplayName("warn when a declared section misses its folder's body")
    void declaredSectionMissingItsFoldersBody(@TempDir Path root) throws IOException {
        List<String> warned = warnings(root, "workshop");
        assertEquals(1, warned.size(), warned.toString());
        assertTrue(warned.get(0).contains("Section 'workshop'"), warned.get(0));
        assertTrue(warned.get(0).contains("<id>04-workshop</id>"), warned.get(0));
    }

    @Test
    @DisplayName("stay quiet when the section's id is the folder's name")
    void declaredSectionWithTheFoldersId(@TempDir Path root) throws IOException {
        assertEquals(List.of(), warnings(root, "04-workshop"));
    }

    /** The numbering a section body with {@code frontmatter} declares. */
    private static dev.noregressions.paperband.number.SectionNumbering numbering(Path root, String frontmatter)
            throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "title: T\n");
        Path folder = Files.createDirectories(root.resolve("scenarios"));
        Files.writeString(folder.resolve("_section.md"), "---\n" + frontmatter + "\n---\n# Scenarios\n");
        Path card = Files.writeString(folder.resolve("login.md"), "# Login\n\nText.\n");
        RenderContext ctx = new ConfigLoader().load(card, "pdf-a4", "a4");
        return SectionBodies.render(ctx, null, Map.of(), List.of(), "print", "pdf", null, null)
                .get("scenarios").numbering();
    }

    @Test
    @DisplayName("read a numbering format")
    void readsANumberingFormat(@TempDir Path root) throws IOException {
        var n = numbering(root, "numbering: \"Scenario {n}\"");
        assertEquals("Scenario {n}", n.format());
        assertTrue(n.numbered());
    }

    @Test
    @DisplayName("refuse a format with no {n}")
    void refusesAFormatWithoutTheNumber(@TempDir Path root) {
        var e = assertThrows(IllegalStateException.class, () -> numbering(root, "numbering: Scenario"));
        assertTrue(e.getMessage().contains("has no {n}"), e.getMessage());
    }

    @Test
    @DisplayName("refuse a format on an unnumbered section")
    void refusesAFormatOnAnUnnumberedSection(@TempDir Path root) {
        var e = assertThrows(IllegalStateException.class,
                () -> numbering(root, "numbered: false\nnumbering: \"Scenario {n}\""));
        assertTrue(e.getMessage().contains("both `numbered: false`"), e.getMessage());
    }

    @Test
    @DisplayName("refuse {!number}, which a section body has none of")
    void refusesTheCardsOwnNumber(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "title: T\n");
        Path folder = Files.createDirectories(root.resolve("scenarios"));
        Files.writeString(folder.resolve("_section.md"), "# Scenarios\n\nThis is part {!number}.\n");
        Path card = Files.writeString(folder.resolve("login.md"), "# Login\n\nText.\n");
        RenderContext ctx = new ConfigLoader().load(card, "pdf-a4", "a4");
        var e = assertThrows(IllegalStateException.class,
                () -> SectionBodies.render(ctx, null, Map.of(), List.of(), "print", "pdf", null, null));
        assertTrue(e.getMessage().contains("a section body has none"), e.getMessage());
    }
}
