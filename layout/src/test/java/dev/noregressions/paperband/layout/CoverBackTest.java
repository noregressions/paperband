package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.PageMatter;
import dev.noregressions.paperband.model.RenderContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code cover:} / {@code back:} rendering through {@code book.html}. */
class CoverBackTest {

    private static Card card(String id) {
        Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                null, 0, "<p>body</p>", List.of());
        return new Card(id, Path.of(id + ".md"), Frontmatter.empty(), "Card " + id, List.of(block));
    }

    private static RenderContext ctx(Path bookRoot, PageMatter cover, PageMatter back) {
        BookConfig book = new BookConfig(bookRoot, "Test Book", List.of(), List.of(),
                Map.of(), List.of(), null, null, null, cover, back, null);
        return new RenderContext(book, List.of(), Map.of(), null, "pdf-a4", "A4");
    }

    private static String render(Path bookRoot, PageMatter cover, PageMatter back) {
        LayoutEngine engine = new LayoutEngine(bookRoot);
        RenderContext c = ctx(bookRoot, cover, back);
        return engine.renderBook(List.of(card("one")), List.of(c), c);
    }

    @Test
    void noCoverOrBackKeepsExistingBehaviour(@TempDir Path root) {
        String html = render(root, null, null);
        assertTrue(html.contains("id=\"book-cover\""), "text cover still renders from title");
        assertTrue(html.contains("Test Book"));
        assertFalse(html.contains("id=\"book-back\""), "no back page unless declared");
        // the class name appears in the scaffold CSS; the element must not
        assertFalse(html.contains("<img class=\"book-cover-image\""));
    }

    @Test
    void coverImageRendersFullPageImageAndSuppressesTextCover(@TempDir Path root) {
        String html = render(root, new PageMatter("images/front.png", null), null);
        assertTrue(html.contains("class=\"book-cover book-cover-has-image\""));
        assertTrue(html.contains("<img class=\"book-cover-image\""));
        assertTrue(html.contains(root.resolve("images/front.png").toUri().toString()),
                "image resolved to an absolute file: URI against the book root");
        assertFalse(html.contains("book-cover-inner"), "text block suppressed by image");
    }

    @Test
    void backImageRendersAfterLastCardWithAnchor(@TempDir Path root) {
        String html = render(root, null, new PageMatter("images/back.png", null));
        int backIdx = html.indexOf("id=\"book-back\"");
        int cardIdx = html.indexOf("Card one");
        assertTrue(backIdx > cardIdx && cardIdx > 0, "back page comes after the last card");
        assertTrue(html.contains("<img class=\"book-back-image\""));
        assertTrue(html.contains("href=\"#book-back\""), "named-destination anchor emitted");
    }

    @Test
    void customTemplatesReplaceBuiltins(@TempDir Path root) throws IOException {
        Path layouts = Files.createDirectory(root.resolve("layouts"));
        Files.writeString(layouts.resolve("my-cover.html"),
                "<section class=\"book-cover\" id=\"book-cover\">CUSTOM COVER "
                        + "{{ book.cover.image }}</section>\n");
        Files.writeString(layouts.resolve("my-back.html"),
                "<section class=\"book-back\" id=\"book-back\">CUSTOM BACK</section>\n");

        String html = render(root,
                new PageMatter("images/front.png", "my-cover"),
                new PageMatter(null, "my-back"));

        assertTrue(html.contains("CUSTOM COVER"), "cover template override wins");
        assertTrue(html.contains(root.resolve("images/front.png").toUri().toString()),
                "declared image still available to the custom template");
        assertFalse(html.contains("book-cover-inner"), "built-in cover not also rendered");
        assertTrue(html.contains("CUSTOM BACK"), "back template override wins");
    }

    @Test
    void scaffoldCssGivesCoverAndBackTheirOwnSheets(@TempDir Path root) {
        String html = render(root,
                new PageMatter("front.png", null), new PageMatter("back.png", null));
        assertTrue(html.contains(".book-cover { page-break-after: always; }"));
        assertTrue(html.contains(".book-back { page-break-before: always; }"));
    }
}
