package dev.noregressions.paperband.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookWalkerTest {

    @TempDir
    Path bookRoot;

    private List<String> walkNames(Path start) {
        return new BookWalker().walk(start).stream()
                .map(p -> p.getFileName().toString())
                .toList();
    }

    @Test
    void yamlFilesAreNotCardsWithoutCardSchema(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), "title: Plain Book\n");
        Files.writeString(root.resolve("a.md"), "# A\n");
        Files.writeString(root.resolve("b.yaml"), "id: b\n");

        assertEquals(List.of("a.md"), walkNames(root),
                "books without cardSchema must not pick up stray yaml files");
    }

    @Test
    void yamlFilesAreCardsWhenBookRootDeclaresCardSchema() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                title: Yaml Book
                cardSchema:
                  frontmatter: [id, title]
                  sections:
                    - field: oneliner
                """);
        Files.writeString(bookRoot.resolve("a.md"), "# A\n");
        Files.writeString(bookRoot.resolve("b.yaml"), "id: b\n");
        Files.writeString(bookRoot.resolve("c.yml"), "id: c\n");

        List<String> names = walkNames(bookRoot);
        assertEquals(List.of("a.md", "b.yaml", "c.yml"), names);
    }

    @Test
    void configFilesAreNeverCards() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                cardSchema:
                  frontmatter: [id]
                """);
        Path sub = Files.createDirectory(bookRoot.resolve("sub"));
        Files.writeString(sub.resolve("pagewright.yaml"), "title: Sub\n");
        Files.writeString(sub.resolve("card.yaml"), "id: card\n");

        assertEquals(List.of("card.yaml"), walkNames(bookRoot),
                "pagewright.yaml config files must never be emitted as cards");
    }

    @Test
    void schemaIsDiscoveredFromAncestorBookRootWhenWalkingASubfolder() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                cardSchema:
                  frontmatter: [id]
                """);
        Path sub = Files.createDirectory(bookRoot.resolve("cards"));
        Files.writeString(sub.resolve("b.yaml"), "id: b\n");

        assertEquals(List.of("b.yaml"), walkNames(sub),
                "walking a subfolder still honours the book root's cardSchema");
    }

    @Test
    void orderEntriesResolveYamlCards() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                cardSchema:
                  frontmatter: [id]
                order:
                  - second
                  - first
                """);
        Files.writeString(bookRoot.resolve("first.yaml"), "id: first\n");
        Files.writeString(bookRoot.resolve("second.yaml"), "id: second\n");

        assertEquals(List.of("second.yaml", "first.yaml"), walkNames(bookRoot),
                "order: entries resolve to .yaml cards when the schema is declared");
    }

    @Test
    void sortOrdersCardsByFrontmatterFields(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), """
                cardSchema:
                  frontmatter: [id]
                sort: [tier, id]
                """);
        // Alphabetical order would interleave tiers; sort must group them.
        Files.writeString(root.resolve("aaa-tier2.yaml"), "id: aaa-tier2\ntier: 2\n");
        Files.writeString(root.resolve("bbb-tier1.yaml"), "id: bbb-tier1\ntier: 1\n");
        Files.writeString(root.resolve("ccc-tier1.yaml"), "id: ccc-tier1\ntier: 1\n");
        Files.writeString(root.resolve("ddd-tier3.yaml"), "id: ddd-tier3\ntier: 3\n");

        assertEquals(
                List.of("bbb-tier1.yaml", "ccc-tier1.yaml", "aaa-tier2.yaml", "ddd-tier3.yaml"),
                walkNames(root));
    }

    @Test
    void sortReadsMarkdownFrontmatterAndFallsBackToBasenameForId(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), "sort: [tier, id]\n");
        Files.writeString(root.resolve("zz.md"), "---\ntier: 1\n---\n# ZZ\n");
        Files.writeString(root.resolve("aa.md"), "---\ntier: 2\n---\n# AA\n");
        // No id in frontmatter → id falls back to basename ("aa" < "zz").
        Files.writeString(root.resolve("mm.md"), "---\ntier: 1\n---\n# MM\n");

        assertEquals(List.of("mm.md", "zz.md", "aa.md"), walkNames(root));
    }

    @Test
    void sortPutsCardsMissingTheFieldLast(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), "sort: [tier]\n");
        Files.writeString(root.resolve("aaa-no-tier.md"), "# No tier\n");
        Files.writeString(root.resolve("zzz-tier1.md"), "---\ntier: 1\n---\n# Z\n");

        assertEquals(List.of("zzz-tier1.md", "aaa-no-tier.md"), walkNames(root),
                "missing sort field sorts after present ones, despite alphabetical order");
    }

    @Test
    void sortSupportsDescendingPrefix(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), "sort: [-tier, id]\n");
        Files.writeString(root.resolve("a.md"), "---\ntier: 1\n---\n# A\n");
        Files.writeString(root.resolve("b.md"), "---\ntier: 3\n---\n# B\n");
        Files.writeString(root.resolve("c.md"), "---\ntier: 2\n---\n# C\n");

        assertEquals(List.of("b.md", "c.md", "a.md"), walkNames(root));
    }

    @Test
    void orderEntriesComeFirstThenSortAppliesToTheRest(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pagewright.yaml"), """
                order: [intro]
                sort: [tier]
                """);
        Files.writeString(root.resolve("intro.md"), "---\ntier: 3\n---\n# Intro\n");
        Files.writeString(root.resolve("x.md"), "---\ntier: 2\n---\n# X\n");
        Files.writeString(root.resolve("y.md"), "---\ntier: 1\n---\n# Y\n");

        assertEquals(List.of("intro.md", "y.md", "x.md"), walkNames(root),
                "order: wins for listed entries; sort: governs the remainder");
    }

    @Test
    void singleYamlFileWalkHonoursAncestorSchema() throws IOException {
        Files.writeString(bookRoot.resolve("pagewright.yaml"), """
                cardSchema:
                  frontmatter: [id]
                """);
        Path card = bookRoot.resolve("only.yaml");
        Files.writeString(card, "id: only\n");

        assertTrue(walkNames(card).contains("only.yaml"));
        Files.writeString(bookRoot.resolve("pagewright.yaml"), "title: no schema\n");
        assertEquals(List.of(), walkNames(card),
                "single yaml file is not a card once the schema is gone");
    }
}
