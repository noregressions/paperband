package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Part;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookPlanTest {

    @TempDir
    Path bookRoot;

    // ---- helpers ----

    private static BookPlan.PartSpec part(String title, String... includes) {
        return new BookPlan.PartSpec(null, title, null, null, List.of(includes), List.of(), List.of());
    }

    private List<String> relative(List<Path> cards) {
        return cards.stream().map(p -> bookRoot.toAbsolutePath().normalize().relativize(p).toString()).toList();
    }

    private void card(String relativePath, String body) throws IOException {
        Path file = bookRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    /** Three sibling service folders, each with a TRACE.md and a NOTES.md. */
    private void servicesTree() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: Services\n");
        for (String service : List.of("billing", "auth", "search")) {
            card("services/" + service + "/TRACE.md", "# Trace " + service + "\n");
            card("services/" + service + "/NOTES.md", "# Notes " + service + "\n");
        }
    }

    // ---- selection ----

    @Test
    void singleStarDoesNotCrossDirectoriesAndPicksOneCardPerFolder() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(part("Traces", "services/*/TRACE.md")), "pdf-a4");

        assertEquals(List.of(
                "services/auth/TRACE.md",
                "services/billing/TRACE.md",
                "services/search/TRACE.md"), relative(plan.cards()),
                "one card per service folder, ordered by relative path");
    }

    @Test
    void doubleStarCrossesDirectories() throws IOException {
        servicesTree();
        card("services/billing/deep/nested/TRACE.md", "# Deep\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(part("Traces", "services/**/TRACE.md")), "pdf-a4");

        assertTrue(relative(plan.cards()).contains("services/billing/deep/nested/TRACE.md"));
        assertEquals(4, plan.cards().size());
    }

    @Test
    void doubleStarSegmentMatchesZeroDirectoriesTheWayEveryOtherMavenPluginReadsIt() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: Book\n");
        card("docs/overview.md", "# Overview\n");
        card("docs/api/v2/types.md", "# Types\n");
        card("top.md", "# Top\n");

        BookPlan.Plan nested = BookPlan.resolve(bookRoot, List.of(part("Docs", "docs/**/*.md")), "pdf-a4");
        assertEquals(List.of("docs/api/v2/types.md", "docs/overview.md"), relative(nested.cards()),
                "docs/**/*.md must find the card sitting directly in docs/ too");

        BookPlan.Plan leading = BookPlan.resolve(bookRoot, List.of(part("All", "**/*.md")), "pdf-a4");
        assertTrue(relative(leading.cards()).contains("top.md"),
                "a leading **/ must not force the card into a subdirectory");
        assertEquals(3, leading.cards().size());
    }

    @Test
    void excludesRemoveWhatAnIncludeMatched() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, "Traces", null, null,
                        List.of("services/*/TRACE.md"), List.of("services/search/**"), List.of())), "pdf-a4");

        assertEquals(List.of(
                "services/auth/TRACE.md",
                "services/billing/TRACE.md"), relative(plan.cards()));
    }

    @Test
    void nonCardFilesAreNeverMatchedHoweverBroadThePattern() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: Book\n");
        card("docs/README.md", "# Readme\n");
        card("docs/notes.txt", "plain\n");
        card("docs/data.yaml", "id: data\n");
        card("docs/real.md", "# Real\n");
        card(".hidden/secret.md", "# Hidden\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(part("Everything", "**")), "pdf-a4");

        assertEquals(List.of("docs/real.md"), relative(plan.cards()),
                "README.md, non-markdown, schema-less yaml and hidden dirs all stay out");
    }

    @Test
    void yamlCardsAreMatchedWhenTheBookDeclaresACardSchema() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), """
                title: Yaml Book
                cardSchema:
                  frontmatter: [id]
                """);
        card("docs/a.md", "# A\n");
        card("docs/b.yaml", "id: b\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(part("Docs", "docs/*")), "pdf-a4");

        assertEquals(List.of("docs/a.md", "docs/b.yaml"), relative(plan.cards()));
    }

    // ---- ordering ----

    @Test
    void partOrderThenPatternOrderThenPathOrder() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Notes", "services/search/NOTES.md", "services/auth/NOTES.md"),
                part("Traces", "services/*/TRACE.md")), "pdf-a4");

        assertEquals(List.of(
                "services/search/NOTES.md",   // part 1, pattern 1
                "services/auth/NOTES.md",     // part 1, pattern 2
                "services/auth/TRACE.md",     // part 2, sorted by path
                "services/billing/TRACE.md",
                "services/search/TRACE.md"), relative(plan.cards()));
    }

    @Test
    void sortOrdersMatchesByFrontmatterFieldNotPath() throws IOException {
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: Book\n");
        card("a/TRACE.md", "---\ntier: 3\n---\n# A\n");
        card("b/TRACE.md", "---\ntier: 1\n---\n# B\n");
        card("c/TRACE.md", "---\ntier: 2\n---\n# C\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, "Traces", null, null,
                        List.of("*/TRACE.md"), List.of(), List.of("tier"))), "pdf-a4");

        assertEquals(List.of("b/TRACE.md", "c/TRACE.md", "a/TRACE.md"), relative(plan.cards()));

        BookPlan.Plan descending = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, "Traces", null, null,
                        List.of("*/TRACE.md"), List.of(), List.of("-tier"))), "pdf-a4");

        assertEquals(List.of("a/TRACE.md", "c/TRACE.md", "b/TRACE.md"), relative(descending.cards()));
    }

    // ---- claiming ----

    @Test
    void firstPartToMatchAFileClaimsIt() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Auth", "services/auth/*"),
                part("Everything", "services/**")), "pdf-a4");

        assertEquals(2, plan.parts().get(0).cards().size());
        assertEquals(4, plan.parts().get(1).cards().size(), "auth's two cards are already claimed");
        assertEquals(6, plan.cards().size(), "and no card is emitted twice");
    }

    @Test
    void twoPartsCanDrawDifferentFilesOutOfOneFolder() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Traces", "services/*/TRACE.md"),
                part("Notes", "services/*/NOTES.md")), "pdf-a4");

        Part traces = plan.parts().get(0);
        Part notes = plan.parts().get(1);
        assertEquals("traces", traces.id());
        assertEquals("notes", notes.id());
        assertTrue(traces.folders().isEmpty(), "pattern-declared parts claim cards, not folders");
        assertTrue(traces.claims(bookRoot.resolve("services/auth/TRACE.md")));
        assertFalse(traces.claims(bookRoot.resolve("services/auth/NOTES.md")));
        assertTrue(notes.claims(bookRoot.resolve("services/auth/NOTES.md")));
    }

    // ---- parts ----

    @Test
    void anonymousSpecEmitsCardsButNoPart() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, null, null, null,
                        List.of("services/*/TRACE.md"), List.of(), List.of())), "pdf-a4");

        assertEquals(3, plan.cards().size());
        assertTrue(plan.parts().isEmpty(), "no identity declared, so no group is claimed");
    }

    @Test
    void idIsSluggedFromTheTitleUnlessDeclared() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Execution Traces!", "services/*/TRACE.md"),
                new BookPlan.PartSpec("notes-x", "Notes", null, null,
                        List.of("services/*/NOTES.md"), List.of(), List.of())), "pdf-a4");

        assertEquals("execution-traces", plan.parts().get(0).id());
        assertEquals("notes-x", plan.parts().get(1).id());
    }

    @Test
    void landingTemplatePresetIsResolvedToATemplateName() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, "Traces", "minimal", null,
                        List.of("services/*/TRACE.md"), List.of(), List.of())), "pdf-a4");

        assertEquals("site-section-minimal", plan.parts().get(0).landingTemplate());
    }

    @Test
    void landingPageDefaultsToTrueAndCanBeDeclaredOff() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Traces", "services/*/TRACE.md"),
                new BookPlan.PartSpec(null, "Notes", null, null,
                        List.of("services/*/NOTES.md"), List.of(), List.of(), false)), "pdf-a4");

        assertTrue(plan.parts().get(0).landingPage(),
                "a spec that says nothing about a page gets one");
        assertFalse(plan.parts().get(1).landingPage(),
                "and one that declares landingPage false does not");
        assertEquals(6, plan.cards().size(),
                "declining a page changes nothing about which cards the part claims");
    }

    @Test
    void whereSkipsThePartAndItsCardsEntirely() throws IOException {
        servicesTree();

        List<BookPlan.PartSpec> specs = List.of(
                new BookPlan.PartSpec(null, "Web Only", null, "target == 'web'",
                        List.of("services/*/NOTES.md"), List.of(), List.of()),
                part("Traces", "services/*/TRACE.md"));

        BookPlan.Plan pdf = BookPlan.resolve(bookRoot, specs, "pdf-a4");
        assertEquals(3, pdf.cards().size());
        assertEquals(1, pdf.parts().size());

        BookPlan.Plan web = BookPlan.resolve(bookRoot, specs, "web");
        assertEquals(6, web.cards().size());
        assertEquals(2, web.parts().size());
    }

    @Test
    void anExcludedPartDoesNotClaimItsCardsAwayFromALaterPart() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.PartSpec(null, "Web Only", null, "target == 'web'",
                        List.of("services/*/TRACE.md"), List.of(), List.of()),
                part("Traces", "services/**")), "pdf-a4");

        assertEquals(6, plan.cards().size(),
                "the skipped part claims nothing, so the fallback part still sees every card");
        assertEquals(List.of("traces"), plan.parts().stream().map(Part::id).toList());
    }

    // ---- validation ----

    @Test
    void aPartThatMatchesNothingIsWarnedAboutAndOmitted() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Missing", "services/*/ABSENT.md"),
                part("Traces", "services/*/TRACE.md")), "pdf-a4");

        assertEquals(List.of("traces"), plan.parts().stream().map(Part::id).toList());
        assertEquals(3, plan.cards().size());
    }

    @Test
    void rootMustBeADirectory() throws IOException {
        card("a.md", "# A\n");
        assertThrows(ConfigParseException.class,
                () -> BookPlan.resolve(bookRoot.resolve("a.md"), List.of(part("X", "**")), "pdf-a4"));
        assertThrows(ConfigParseException.class,
                () -> BookPlan.resolve(bookRoot.resolve("nope"), List.of(part("X", "**")), "pdf-a4"));
    }

    @Test
    void emptyOrUnusableSpecsAreRejected() {
        assertThrows(ConfigParseException.class,
                () -> BookPlan.resolve(bookRoot, List.of(), "pdf-a4"));
        assertThrows(ConfigParseException.class,
                () -> BookPlan.resolve(bookRoot, List.of(part("No Patterns")), "pdf-a4"));
        assertThrows(ConfigParseException.class,
                () -> BookPlan.resolve(bookRoot, List.of(part("Same", "**"), part("Same", "**")), "pdf-a4"));
    }
}
