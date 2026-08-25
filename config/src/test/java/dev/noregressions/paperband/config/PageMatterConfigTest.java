package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.model.RenderContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code cover:} / {@code back:} parsing through the {@link ConfigLoader}. */
class PageMatterConfigTest {

    @TempDir
    Path bookRoot;

    private RenderContext load(String bookYaml) throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), bookYaml);
        Path card = bookRoot.resolve("card.md");
        Files.writeString(card, "# Card\n");
        return new ConfigLoader().load(card, "pdf-a4", "A4");
    }

    @Test
    void mapFormParsesImageAndTemplate() throws IOException {
        RenderContext ctx = load("""
                title: T
                cover:
                  image: images/front.png
                back:
                  template: layouts/cta.html
                """);
        PageMatter cover = ctx.book().cover();
        assertEquals("images/front.png", cover.image());
        assertNull(cover.template());

        PageMatter back = ctx.book().back();
        assertNull(back.image());
        assertEquals("cta", back.template(), "template stored as bare Pebble loader name");
    }

    @Test
    void bareStringIsImageShorthand() throws IOException {
        RenderContext ctx = load("cover: images/front.png\n");
        assertEquals("images/front.png", ctx.book().cover().image());
        assertNull(ctx.book().back());
    }

    @Test
    void absentKeysAreNull() throws IOException {
        RenderContext ctx = load("title: T\n");
        assertNull(ctx.book().cover());
        assertNull(ctx.book().back());
    }

    @Test
    void emptyMapIsRejected() {
        assertThrows(ConfigParseException.class, () -> load("cover: {}\n"));
    }

    @Test
    void coverTextFieldsParse() throws IOException {
        RenderContext ctx = load("""
                title: T
                cover:
                  image: images/front.png
                  text: true
                  subtitle: "2026 edition"
                """);
        PageMatter cover = ctx.book().cover();
        assertEquals("2026 edition", cover.subtitle());
        assertEquals(true, cover.text());
        assertEquals(true, cover.hasText());
    }

    @Test
    void unknownCoverKeyIsRejected() {
        assertThrows(ConfigParseException.class,
                () -> load("cover:\n  image: x.png\n  subtile: typo\n"),
                "a typo'd key must fail, not silently vanish");
    }

    @Test
    void fullPageParsesOnCover() throws IOException {
        RenderContext ctx = load("""
                title: T
                cover:
                  image: images/front.png
                  fullPage: true
                """);
        assertEquals(true, ctx.book().cover().fullPage());
    }

    @Test
    void fullPageOnBackIsRejected() {
        // CSS can address the first page (:first) but not the last.
        ConfigParseException e = assertThrows(ConfigParseException.class,
                () -> load("back:\n  image: x.png\n  fullPage: true\n"));
        assertEquals(true, e.getMessage().contains("fullPage"), e.getMessage());
    }
}
