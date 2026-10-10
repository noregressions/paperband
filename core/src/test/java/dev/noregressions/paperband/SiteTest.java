package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiteTest {

    private static Site site() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("docs/a.md"), """
                ---
                title: A
                tags:
                  - x
                  - y
                ---
                # Top

                Intro *text*.

                ## Items

                - one `code`
                - two
                  - nested

                ### Deeper

                1. deep

                ## Items

                ```java
                int x;
                ```
                """);
        ast.add(Path.of("b.md"), "# Only\n\nHello.\n");
        return new Site(ast);
    }

    @Test
    void findsFilesByPathOrSuffix() throws IOException {
        Site site = site();
        assertEquals("docs/a.md", site.file("docs/a.md").getPath());
        assertEquals("docs/a.md", site.file("a.md").getPath());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> site.file("c.md"));
        assertTrue(e.getMessage().contains("no file matches \"c.md\""), e.getMessage());
    }

    @Test
    void exposesFrontMatterUnwrapped() throws IOException {
        Site.FileView a = site().file("a.md");
        assertEquals("A", a.getPage().get("title"));
        assertEquals(List.of("x", "y"), a.getPage().get("tags"));
    }

    @Test
    void sectionsRunToNextHeadingOfSameOrHigherLevel() throws IOException {
        Site.FileView a = site().file("a.md");
        assertEquals(List.of("top", "items", "deeper", "items-1"),
                a.getSections().stream().map(Site.Section::getId).toList());

        Site.Section items = a.section("items");
        // Includes the list under the nested "Deeper" subsection.
        assertEquals(List.of(false, true), items.getLists().stream().map(Site.ListView::isOrdered).toList());
        assertEquals(1, a.section("deeper").getLists().size());
        assertEquals("Intro text.", a.section("Top").getParagraphs().getFirst());
        assertEquals("java", a.section("items-1").getCode().getFirst().getLanguage());
    }

    @Test
    void listItemsPrintAsTheirOwnText() throws IOException {
        Site.ListView list = site().file("a.md").section("items").getList();
        assertFalse(list.isOrdered());
        List<Site.Item> items = list.getItems();
        assertEquals("one code", items.get(0).toString());
        assertEquals("two", items.get(1).getText());
        assertEquals("nested", items.get(1).getLists().getFirst().getItems().getFirst().getText());
    }

    @Test
    void ambiguousFileNameIsAnError() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("one/index.md"), "x\n");
        ast.add(Path.of("two/index.md"), "y\n");
        Site site = new Site(ast);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> site.file("index.md"));
        assertTrue(e.getMessage().contains("several files match \"index.md\""), e.getMessage());
        assertEquals("y", site.file("two/index.md").getText());
    }

    @Test
    void slugsDropPunctuationAndKeepLetters() {
        assertEquals("whats-new-in-java-21", Site.slug("What's New in Java 21?"));
        assertEquals("café--crème", Site.slug("Café & Crème"));
        assertEquals("snake_case-and-kebab-case", Site.slug("snake_case and kebab-case"));
    }

    @Test
    void indentedCodeHasNoLanguage() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), "Para.\n\n    indented\n");
        Site.CodeView code = new Site(ast).file("a.md").getCode().getFirst();
        assertEquals("", code.getLanguage());
        assertEquals("indented\n", code.toString());
    }

    @Test
    void multiParagraphItemTextIsJoined() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), """
                - first para
                  continued

                  second para
                - next
                """);
        List<Site.Item> items = new Site(ast).file("a.md").getList().getItems();
        assertEquals("first para continued second para", items.getFirst().getText());
    }

    @Test
    void wholeFileContentSkipsFrontMatter() throws IOException {
        Site.FileView a = site().file("a.md");
        assertEquals(List.of("Intro text."), a.getParagraphs());
        assertFalse(a.getText().contains("title"), a.getText());
        assertTrue(a.getText().startsWith("Top\nIntro text."), a.getText());
        assertEquals(2, a.getLists().size());
    }

    @Test
    void prePassSiteHasFrontMatterButNoContent() {
        java.util.Map<Path, java.util.Map<String, Object>> fm = new java.util.LinkedHashMap<>();
        fm.put(Path.of("a.md"), java.util.Map.of("n", 1));
        fm.put(Path.of("b.md"), java.util.Map.of());
        Site site = Site.ofFrontMatter(fm);

        assertEquals(List.of("a.md", "b.md"), site.getFiles().stream().map(Site.FileView::getPath).toList());
        Site.FileView a = site.file("a.md");
        assertEquals(1, a.getPage().get("n"));
        assertEquals("", a.toString(), "printing a file without content is harmless");
        for (org.junit.jupiter.api.function.Executable content : List.<org.junit.jupiter.api.function.Executable>of(
                a::getText, a::getLists, a::getList, a::getParagraphs, a::getCode, a::getSections,
                () -> a.section("x"))) {
            IllegalStateException e = assertThrows(IllegalStateException.class, content);
            assertEquals(Site.NO_CONTENT_YET, e.getMessage());
        }
    }

    @Test
    void sequenceIsTheOutputOrderAndLeavesOutOtherSources() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), "# A\n");
        ast.add(Path.of("b.md"), "# B\n");
        ast.add(Path.of("hidden.md"), "# H\n");

        Site site = new Site(ast, List.of(Path.of("b.md"), Path.of("a.md")));
        assertEquals(List.of("b.md", "a.md"), site.getSequence().stream().map(Site.FileView::getPath).toList());
        assertEquals(3, site.getFiles().size(), "files still has every source");
        assertEquals(List.of("a.md", "b.md", "hidden.md"),
                new Site(ast).getSequence().stream().map(Site.FileView::getPath).toList(), "no sequence means all files");

        Site pre = Site.ofFrontMatter(new java.util.LinkedHashMap<>(java.util.Map.of(Path.of("a.md"), java.util.Map.of())),
                List.of(Path.of("a.md")));
        assertEquals(List.of("a.md"), pre.getSequence().stream().map(Site.FileView::getPath).toList());
    }

    @Test
    void missingSectionNamesTheAlternatives() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> site().file("b.md").section("nope"));
        assertTrue(e.getMessage().contains("[only]"), e.getMessage());
    }
}
