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
        Files.writeString(root.resolve("paperband.yaml"), "title: Plain Book\n");
        Files.writeString(root.resolve("a.md"), "# A\n");
        Files.writeString(root.resolve("b.yaml"), "id: b\n");

        assertEquals(List.of("a.md"), walkNames(root),
                "books without cardSchema must not pick up stray yaml files");
    }

    @Test
    void yamlFilesAreCardsWhenBookRootDeclaresCardSchema() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
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
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
                cardSchema:
                  frontmatter: [id]
                """);
        Path sub = Files.createDirectory(bookRoot.resolve("sub"));
        Files.writeString(sub.resolve("paperband.yaml"), "title: Sub\n");
        Files.writeString(sub.resolve("card.yaml"), "id: card\n");

        assertEquals(List.of("card.yaml"), walkNames(bookRoot),
                "paperband.yaml config files must never be emitted as cards");
    }

    @Test
    void schemaIsDiscoveredFromAncestorBookRootWhenWalkingASubfolder() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
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
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
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
        Files.writeString(root.resolve("paperband.yaml"), """
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
        Files.writeString(root.resolve("paperband.yaml"), "sort: [tier, id]\n");
        Files.writeString(root.resolve("zz.md"), "---\ntier: 1\n---\n# ZZ\n");
        Files.writeString(root.resolve("aa.md"), "---\ntier: 2\n---\n# AA\n");
        // No id in frontmatter → id falls back to basename ("aa" < "zz").
        Files.writeString(root.resolve("mm.md"), "---\ntier: 1\n---\n# MM\n");

        assertEquals(List.of("mm.md", "zz.md", "aa.md"), walkNames(root));
    }

    @Test
    void sortPutsCardsMissingTheFieldLast(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "sort: [tier]\n");
        Files.writeString(root.resolve("aaa-no-tier.md"), "# No tier\n");
        Files.writeString(root.resolve("zzz-tier1.md"), "---\ntier: 1\n---\n# Z\n");

        assertEquals(List.of("zzz-tier1.md", "aaa-no-tier.md"), walkNames(root),
                "missing sort field sorts after present ones, despite alphabetical order");
    }

    @Test
    void sortSupportsDescendingPrefix(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "sort: [-tier, id]\n");
        Files.writeString(root.resolve("a.md"), "---\ntier: 1\n---\n# A\n");
        Files.writeString(root.resolve("b.md"), "---\ntier: 3\n---\n# B\n");
        Files.writeString(root.resolve("c.md"), "---\ntier: 2\n---\n# C\n");

        assertEquals(List.of("b.md", "c.md", "a.md"), walkNames(root));
    }

    @Test
    void orderEntriesComeFirstThenSortAppliesToTheRest(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
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
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
                cardSchema:
                  frontmatter: [id]
                """);
        Path card = bookRoot.resolve("only.yaml");
        Files.writeString(card, "id: only\n");

        assertTrue(walkNames(card).contains("only.yaml"));
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: no schema\n");
        assertEquals(List.of(), walkNames(card),
                "single yaml file is not a card once the schema is gone");
    }

    // ---- include: exclusive declaration ----

    @Test
    void includeEmitsOnlyListedCardsInListedOrder(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                include:
                  - 02-second
                  - 01-first
                """);
        Files.writeString(root.resolve("01-first.md"), "# First\n");
        Files.writeString(root.resolve("02-second.md"), "# Second\n");
        Files.writeString(root.resolve("99-draft.md"), "# Draft\n");

        assertEquals(List.of("02-second.md", "01-first.md"), walkNames(root),
                "include: is exclusive — the unlisted draft must stay out of the book");
    }

    @Test
    void includeKeepsNewFilesOutUntilTheyAreListed(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "include: [a]\n");
        Files.writeString(root.resolve("a.md"), "# A\n");
        assertEquals(List.of("a.md"), walkNames(root));

        // The whole point of the exclusive form: dropping a file in the folder
        // does not silently change the book.
        Files.writeString(root.resolve("b.md"), "# B\n");
        assertEquals(List.of("a.md"), walkNames(root));

        Files.writeString(root.resolve("paperband.yaml"), "include: [a, b]\n");
        assertEquals(List.of("a.md", "b.md"), walkNames(root));
    }

    @Test
    void includeResolvesSubdirectoriesAndRecursesIntoThem(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), "include: [chapter]\n");
        Path chapter = Files.createDirectory(root.resolve("chapter"));
        Files.writeString(chapter.resolve("one.md"), "# One\n");
        Files.writeString(root.resolve("loose.md"), "# Loose\n");

        assertEquals(List.of("one.md"), walkNames(root));
    }

    @Test
    void includeWinsOverOrderAtTheSameLevel(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                include: [a]
                order: [b]
                """);
        Files.writeString(root.resolve("a.md"), "# A\n");
        Files.writeString(root.resolve("b.md"), "# B\n");

        assertEquals(List.of("a.md"), walkNames(root),
                "the two keys answer the same question; include: wins and order: is ignored");
    }

    @Test
    void includeIgnoresSortBecauseItIsAlreadyAnExactOrder(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                include: [b, a]
                sort: [tier]
                """);
        Files.writeString(root.resolve("a.md"), "---\ntier: 1\n---\n# A\n");
        Files.writeString(root.resolve("b.md"), "---\ntier: 2\n---\n# B\n");

        assertEquals(List.of("b.md", "a.md"), walkNames(root));
    }

    @Test
    void folderCanDeclareIncludeWhileItsSiblingIsDiscovered(@TempDir Path root) throws IOException {
        // The mixed case: one folder pins an exact list, the other just lets
        // whatever is on disk be found.
        Files.writeString(root.resolve("paperband.yaml"), "order: [declared, discovered]\n");
        Path declared = Files.createDirectory(root.resolve("declared"));
        Files.writeString(declared.resolve("paperband.yaml"), "include: [keep]\n");
        Files.writeString(declared.resolve("keep.md"), "# Keep\n");
        Files.writeString(declared.resolve("skip.md"), "# Skip\n");
        Path discovered = Files.createDirectory(root.resolve("discovered"));
        Files.writeString(discovered.resolve("x.md"), "# X\n");
        Files.writeString(discovered.resolve("y.md"), "# Y\n");

        assertEquals(List.of("keep.md", "x.md", "y.md"), walkNames(root));
    }

    // ---- parts: declared groups of folders ----

    @Test
    void partsEmitTheirFoldersInDeclaredOrder(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Second Part"
                    folders: [beta]
                  - title: "First Part"
                    folders: [alpha]
                """);
        Path alpha = Files.createDirectory(root.resolve("alpha"));
        Files.writeString(alpha.resolve("a.md"), "# A\n");
        Path beta = Files.createDirectory(root.resolve("beta"));
        Files.writeString(beta.resolve("b.md"), "# B\n");

        assertEquals(List.of("b.md", "a.md"), walkNames(root),
                "part order drives folder order, overriding alphabetical discovery");
    }

    @Test
    void partsAppendUnclaimedContentAfterDeclaredFolders(@TempDir Path root) throws IOException {
        // Declaration and discovery mixed at one level: the part is declared,
        // the leftover folder is discovered.
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Main"
                    folders: [zzz-declared]
                """);
        Path declared = Files.createDirectory(root.resolve("zzz-declared"));
        Files.writeString(declared.resolve("d.md"), "# D\n");
        Path extra = Files.createDirectory(root.resolve("aaa-extra"));
        Files.writeString(extra.resolve("e.md"), "# E\n");

        assertEquals(List.of("d.md", "e.md"), walkNames(root),
                "declared folders come first even when discovery would sort them earlier");
    }

    @Test
    void partsSpanSeveralFoldersInOneGroup(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Foundations"
                    folders: [intro, authoring]
                """);
        Path intro = Files.createDirectory(root.resolve("intro"));
        Files.writeString(intro.resolve("i.md"), "# I\n");
        Path authoring = Files.createDirectory(root.resolve("authoring"));
        Files.writeString(authoring.resolve("a.md"), "# A\n");

        assertEquals(List.of("i.md", "a.md"), walkNames(root));
    }

    @Test
    void partsWinOverOrderAtTheSameLevel(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Only"
                    folders: [beta]
                order: [alpha]
                """);
        Path alpha = Files.createDirectory(root.resolve("alpha"));
        Files.writeString(alpha.resolve("a.md"), "# A\n");
        Path beta = Files.createDirectory(root.resolve("beta"));
        Files.writeString(beta.resolve("b.md"), "# B\n");

        // parts: sequences the declared folder first; alpha is still emitted,
        // but as discovered content rather than by the ignored order: key.
        assertEquals(List.of("b.md", "a.md"), walkNames(root));
    }

    @Test
    void partFoldersCombineWithPerFolderInclude(@TempDir Path root) throws IOException {
        // The shape this feature exists for: the root declares the parts, each
        // folder declares exactly which cards it contributes.
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Foundations"
                    folders: [getting-started]
                """);
        Path gs = Files.createDirectory(root.resolve("getting-started"));
        Files.writeString(gs.resolve("paperband.yaml"), """
                title: "Getting Started"
                include:
                  - 02-quickstart
                  - 01-introduction
                """);
        Files.writeString(gs.resolve("01-introduction.md"), "# Intro\n");
        Files.writeString(gs.resolve("02-quickstart.md"), "# Quick\n");
        Files.writeString(gs.resolve("notes-to-self.md"), "# Notes\n");

        assertEquals(List.of("02-quickstart.md", "01-introduction.md"), walkNames(root));
    }

    @Test
    void partWherePredicateSkipsEveryFolderItClaims(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("paperband.yaml"), """
                parts:
                  - title: "Web Only"
                    where: "target == 'web'"
                    folders: [extras]
                  - title: "Always"
                    folders: [core]
                """);
        Path extras = Files.createDirectory(root.resolve("extras"));
        Files.writeString(extras.resolve("x.md"), "# X\n");
        Path core = Files.createDirectory(root.resolve("core"));
        Files.writeString(core.resolve("c.md"), "# C\n");

        List<String> pdf = new BookWalker("pdf-a4").walk(root).stream()
                .map(q -> q.getFileName().toString()).toList();
        assertEquals(List.of("c.md"), pdf,
                "an excluded part must not reappear via the discovery pass");

        List<String> web = new BookWalker("web").walk(root).stream()
                .map(q -> q.getFileName().toString()).toList();
        assertEquals(List.of("x.md", "c.md"), web);
    }
}
