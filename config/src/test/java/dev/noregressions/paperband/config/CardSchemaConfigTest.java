package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.CardSchema;
import dev.noregressions.paperband.model.RenderContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code cardSchema:} parsing through the {@link ConfigLoader} cascade. */
class CardSchemaConfigTest {

    @TempDir
    Path bookRoot;

    @Test
    void cardSchemaParsesFromBookRoot() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                title: Yaml Book
                cardSchema:
                  frontmatter: [id, tier, title]
                  sections:
                    - field: oneliner
                    - field: diffs
                      heading: "Fix"
                      fence: diff-card
                """);
        Path card = bookRoot.resolve("card.yaml");
        Files.writeString(card, "id: card\n");

        RenderContext ctx = new ConfigLoader().load(card, "pdf-a4", "A4");
        CardSchema schema = ctx.book().cardSchema();
        assertEquals(java.util.List.of("id", "tier", "title"), schema.frontmatterFields());
        assertEquals("diff-card", schema.sections().get(1).fence());
    }

    @Test
    void absentCardSchemaIsNull() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), "title: Plain\n");
        Path card = bookRoot.resolve("card.md");
        Files.writeString(card, "# Card\n");

        RenderContext ctx = new ConfigLoader().load(card, "pdf-a4", "A4");
        assertNull(ctx.book().cardSchema());
    }

    @Test
    void malformedCardSchemaFailsWithFileInMessage() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                cardSchema: not-a-mapping
                """);
        Path card = bookRoot.resolve("card.md");
        Files.writeString(card, "# Card\n");

        ConfigParseException e = assertThrows(ConfigParseException.class,
                () -> new ConfigLoader().load(card, "pdf-a4", "A4"));
        assertEquals(true, e.getMessage().contains("pagewright.yaml"));
    }
}
