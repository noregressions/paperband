package dev.noregressions.paperband.block.plantuml;

import dev.noregressions.paperband.block.BlockRenderException;
import dev.noregressions.paperband.block.BlockRendererRegistry;
import dev.noregressions.paperband.block.BlockRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantumlBlockRendererTest {

    private final PlantumlBlockRenderer renderer = new PlantumlBlockRenderer();

    private String render(String content, Map<String, Object> config) {
        return renderer.render(new BlockRequest("plantuml", content, List.of(), null,
                Map.of(), config, null, Path.of("card.md")));
    }

    private String render(String content) {
        return render(content, Map.of());
    }

    @Test
    void aDiagramBecomesInlineSvg() {
        String html = render("""
                @startuml
                Alice -> Bob: hello
                @enduml""");

        assertTrue(html.startsWith("<figure class=\"plantuml\">"), html);
        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("Alice"), html);
        // Inline, not a script and not a link: the PDF renderer must not have
        // to wait for anything, and an offline build must not have to fetch.
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("http://") && html.contains("src="), html);
    }

    @Test
    void theStartMarkersAreOptionalInsideAFence() {
        // The fence already says "this is a diagram"; making an author repeat
        // it in two syntaxes is ceremony.
        String html = render("Alice -> Bob: hello");

        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("Alice"), html);
    }

    @Test
    void anotherStartDirectiveIsLeftAlone() {
        // @startmindmap is not @startuml; wrapping it would break it.
        String html = render("""
                @startmindmap
                * root
                ** child
                @endmindmap""");

        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("root"), html);
    }

    @Test
    void theSvgKeepsItsSizeAndCanStillShrink() {
        String html = render("Alice -> Bob: hello");
        String openTag = html.substring(html.indexOf("<svg"), html.indexOf('>', html.indexOf("<svg")));

        // Drawn size on the attributes, so a diagram prints as it was drawn.
        assertTrue(openTag.contains("width=\""), openTag);
        assertTrue(openTag.contains("viewBox="), openTag);
        // ...and nothing in an inline style to beat the height:auto that lets
        // a too-wide diagram shrink to the column instead of overflowing it.
        assertFalse(openTag.contains("style="), openTag);
        // Shrinking must scale the drawing, not squash it. PlantUML says none.
        assertTrue(openTag.contains("preserveAspectRatio=\"xMidYMid meet\""), openTag);
    }

    @Test
    void theSvgIsCleanEnoughToSpliceIntoHtml() {
        String html = render("Alice -> Bob: hello");

        // An XML prolog or processing instruction becomes a stray comment
        // once an HTML parser has been at it.
        assertFalse(html.contains("<?xml"), html);
        assertFalse(html.contains("<?plantuml"), html);
        // Transparent by default: the page's own paper shows through.
        assertFalse(html.contains("background:#FFFFFF"), html);
    }

    @Test
    void aBackgroundCanBeAskedForBack() {
        String html = render("Alice -> Bob: hello", Map.of("background", "#FFFFFF"));

        assertTrue(html.contains("#FFFFFF"), html);
    }

    @Test
    void classesAndIdFromTheInfoLineRideAlong() {
        String html = renderer.render(new BlockRequest("puml", "Alice -> Bob: hi",
                List.of("wide"), "handshake", Map.of(), Map.of(), null, null));

        assertTrue(html.startsWith("<figure class=\"plantuml wide\" id=\"handshake\">"), html);
    }

    @Test
    void pngFormatEmbedsAsADataUri() {
        String html = render("Alice -> Bob: hello", Map.of("format", "png"));

        assertTrue(html.contains("<img alt=\"PlantUML diagram\" src=\"data:image/png;base64,"), html);
    }

    @Test
    void anUnknownFormatSaysWhatTheChoicesAre() {
        BlockRenderException e = assertThrows(BlockRenderException.class,
                () -> render("Alice -> Bob: hi", Map.of("format", "webp")));

        assertTrue(e.getMessage().contains("svg or png"), e.getMessage());
    }

    @Test
    void aThemeSettingReachesTheDiagram() {
        // Rendering with a theme must still produce a diagram -- the directive
        // lands inside the @startuml, where PlantUML will read it.
        String plain = render("Alice -> Bob: hello");
        String themed = render("Alice -> Bob: hello", Map.of("theme", "plain"));

        assertTrue(themed.contains("<svg"), themed);
        assertFalse(themed.equals(plain), "the theme should change the drawing");
    }

    @Test
    void anInlineStyleReachesTheDrawing() {
        // The only way to theme a PlantUML diagram: it bakes colours into each
        // shape and emits no classes, so CSS has nothing to select.
        String html = render("Alice -> Bob: hello", Map.of(
                "style", "<style>\nroot { FontName \"IBM Plex Sans\"; FontColor #333333 }\n</style>"));

        assertTrue(html.contains("IBM Plex Sans"), html);
        assertTrue(html.contains("#333333") || html.contains("#333"), html);
    }

    @Test
    void aStyleFileIsResolvedAgainstTheBookRoot(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("styles"));
        Files.writeString(book.resolve("styles/diagrams.puml"),
                "<style>\nroot { FontColor #AA0000 }\n</style>");

        String html = renderer.render(new BlockRequest("plantuml", "Alice -> Bob: hello",
                List.of(), null, Map.of(), Map.of("styleFile", "styles/diagrams.puml"),
                book, book.resolve("content/card.md")));

        // PlantUML shortens a hex triple on the way out: #AA0000 becomes #A00.
        assertTrue(html.contains("#AA0000") || html.contains("#A00"), html);
    }

    @Test
    void aStyleFileThatIsNotThere_saysWhereItLooked(@TempDir Path book) {
        // Silently ignoring it would mean a book whose diagrams quietly lost
        // their styling when someone moved a directory.
        BlockRenderException e = assertThrows(BlockRenderException.class,
                () -> renderer.render(new BlockRequest("plantuml", "Alice -> Bob: hi",
                        List.of(), null, Map.of(), Map.of("styleFile", "styles/gone.puml"),
                        book, null)));

        assertTrue(e.getMessage().contains("styleFile"), e.getMessage());
        assertTrue(e.getMessage().contains("styles/gone.puml"), e.getMessage());
        assertTrue(e.getMessage().contains(book.toString()), e.getMessage());
    }

    @Test
    void oneCardsStyleWinsOverTheBooksFile(@TempDir Path book) throws IOException {
        Files.writeString(book.resolve("base.puml"), "<style>\nroot { FontColor #AA0000 }\n</style>");

        String html = renderer.render(new BlockRequest("plantuml", "Alice -> Bob: hello",
                List.of(), null, Map.of(),
                Map.of("styleFile", "base.puml",
                       "style", "<style>\nroot { FontColor #0000BB }\n</style>"),
                book, null));

        // Broadest first, narrowest last: the inline block is the later
        // declaration, so it is the one that lands.
        assertTrue(html.contains("#0000BB") || html.contains("#00B"), html);
        assertFalse(html.contains("#AA0000") || html.contains("#A00"), html);
    }

    @Test
    void aBrokenDiagramFailsTheBuildInsteadOfDrawingTheError() {
        // PlantUML's own behaviour is to render a picture of the error, which
        // in a book means a page that builds green and prints a stack trace.
        BlockRenderException e = assertThrows(BlockRenderException.class,
                () -> render("""
                        @startuml
                        this is not ~~~ plantuml !!
                        @enduml"""));

        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void anUnclosedStartDirectiveSaysSo() {
        BlockRenderException e = assertThrows(BlockRenderException.class,
                () -> render("@startuml\nAlice -> Bob: hi"));

        assertTrue(e.getMessage().contains("@start"), e.getMessage());
    }

    @Test
    void theModuleRegistersItselfOnTheClasspath() {
        // The ServiceLoader file is the whole installation story: if this
        // fails, adding the jar to a build would do nothing at all.
        BlockRendererRegistry registry = BlockRendererRegistry.discover();

        assertEquals("plantuml", registry.forType("plantuml").orElseThrow().name());
        assertEquals("plantuml", registry.forType("puml").orElseThrow().name());
        assertEquals("plantuml", registry.forType("uml").orElseThrow().name());
    }
}
