package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Section;

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

    private static BookPlan.SectionSpec part(String title, String... includes) {
        return new BookPlan.SectionSpec(null, title, null, null, List.of(includes), List.of(), List.of());
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

    // ---- toc position ----

    @Test
    void tocPositionResolvesToTheCardCountTheEarlierSpecsClaimed() throws IOException {
        servicesTree();
        List<BookPlan.SectionSpec> specs = List.of(
                part("Traces", "services/*/TRACE.md"),
                part("Notes", "services/*/NOTES.md"));

        assertEquals(0, BookPlan.resolve(bookRoot, specs, 0, "pdf-a4").tocCardIndex(),
                "marker before everything: contents page up front");
        assertEquals(3, BookPlan.resolve(bookRoot, specs, 1, "pdf-a4").tocCardIndex(),
                "marker between the sections: after the three traces");
        assertEquals(6, BookPlan.resolve(bookRoot, specs, 2, "pdf-a4").tocCardIndex(),
                "marker after everything: after the last card");
        assertEquals(null, BookPlan.resolve(bookRoot, specs, "pdf-a4").tocCardIndex(),
                "no marker, no declared position");
    }

    @Test
    void tocPositionSkipsNothingForASkippedOrEmptySpec() throws IOException {
        servicesTree();
        List<BookPlan.SectionSpec> specs = List.of(
                new BookPlan.SectionSpec(null, "Web only", null, "target == 'web'",
                        List.of("services/*/TRACE.md"), List.of(), List.of()),
                part("Nothing", "missing/**/*.md"),
                part("Notes", "services/*/NOTES.md"));

        assertEquals(0, BookPlan.resolve(bookRoot, specs, 2, "pdf-a4").tocCardIndex(),
                "a where-skipped part and an empty part claim no cards, so the "
                        + "marker after them still lands before the first real card");
    }

    // ---- page positions ----

    @Test
    void pageMarkersResolveToTheCardCountTheEarlierSpecsClaimed() throws IOException {
        servicesTree();
        List<BookPlan.SectionSpec> specs = List.of(
                part("Traces", "services/*/TRACE.md"),
                part("Notes", "services/*/NOTES.md"));
        List<BookPlan.PageMarker> markers = List.of(
                new BookPlan.PageMarker(0, "front"),
                new BookPlan.PageMarker(1, "matrix"),
                new BookPlan.PageMarker(2, "appendix"));

        var pages = BookPlan.resolve(bookRoot, specs, null, markers, "pdf-a4").pages();

        assertEquals(3, pages.size());
        assertEquals(0, pages.get(0).cardIndex(), "marker before everything");
        assertEquals("front", pages.get(0).template());
        assertEquals(3, pages.get(1).cardIndex(), "between the sections: after the three traces");
        assertEquals(6, pages.get(2).cardIndex(), "trailing marker: after the last card");
    }

    @Test
    void pageMarkersAndTheTocMarkerResolveIndependently() throws IOException {
        servicesTree();
        List<BookPlan.SectionSpec> specs = List.of(
                part("Traces", "services/*/TRACE.md"),
                part("Notes", "services/*/NOTES.md"));

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, specs, 1,
                List.of(new BookPlan.PageMarker(1, "matrix")), "pdf-a4");

        assertEquals(3, plan.tocCardIndex());
        assertEquals(3, plan.pages().get(0).cardIndex(),
                "a page and the toc can share a position; each keeps its own");
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
                new BookPlan.SectionSpec(null, "Traces", null, null,
                        List.of("services/*/TRACE.md"), List.of("services/search/**"), List.of())), "pdf-a4");

        assertEquals(List.of(
                "services/auth/TRACE.md",
                "services/billing/TRACE.md"), relative(plan.cards()));
    }

    @Test
    void aPatternThatNamesAReadmeClaimsIt() throws IOException {
        // The readme rule is a discovery heuristic: walking a tree, a readme is
        // documentation about the repo. A pattern naming README.md is the
        // opposite — it means those files, and refusing them leaves a pattern
        // that matches nothing with no way to tell why.
        Files.writeString(bookRoot.resolve("paperband.yaml"), "title: Book\n");
        card("scenarios/S01/README.md", "# S01 overview\n");
        card("scenarios/S01/TRACE.md", "# S01 trace\n");
        card("scenarios/S01/frontend/node_modules/lodash/README.md", "# vendored\n");

        BookPlan.Plan named = BookPlan.resolve(bookRoot,
                List.of(part("Scenarios", "scenarios/*/README.md")), "pdf-a4");
        assertEquals(List.of("scenarios/S01/README.md"), relative(named.cards()),
                "named, so claimed — and one level, so nothing vendored");

        BookPlan.Plan swept = BookPlan.resolve(bookRoot,
                List.of(part("Scenarios", "scenarios/**/*.md")), "pdf-a4");
        assertEquals(List.of("scenarios/S01/TRACE.md"), relative(swept.cards()),
                "a wildcard sweep leaves readmes alone — that rule is what stops "
                        + "a book swallowing every readme under node_modules");
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
                new BookPlan.SectionSpec(null, "Traces", null, null,
                        List.of("*/TRACE.md"), List.of(), List.of("tier"))), "pdf-a4");

        assertEquals(List.of("b/TRACE.md", "c/TRACE.md", "a/TRACE.md"), relative(plan.cards()));

        BookPlan.Plan descending = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.SectionSpec(null, "Traces", null, null,
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

        assertEquals(2, plan.sections().get(0).cards().size());
        assertEquals(4, plan.sections().get(1).cards().size(), "auth's two cards are already claimed");
        assertEquals(6, plan.cards().size(), "and no card is emitted twice");
    }

    @Test
    void twoPartsCanDrawDifferentFilesOutOfOneFolder() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Traces", "services/*/TRACE.md"),
                part("Notes", "services/*/NOTES.md")), "pdf-a4");

        Section traces = plan.sections().get(0);
        Section notes = plan.sections().get(1);
        assertEquals("traces", traces.id());
        assertEquals("notes", notes.id());
        assertTrue(traces.folders().isEmpty(), "pattern-declared sections claim cards, not folders");
        assertTrue(traces.claims(bookRoot.resolve("services/auth/TRACE.md")));
        assertFalse(traces.claims(bookRoot.resolve("services/auth/NOTES.md")));
        assertTrue(notes.claims(bookRoot.resolve("services/auth/NOTES.md")));
    }

    // ---- sections ----

    @Test
    void anonymousSpecEmitsCardsButNoPart() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.SectionSpec(null, null, null, null,
                        List.of("services/*/TRACE.md"), List.of(), List.of())), "pdf-a4");

        assertEquals(3, plan.cards().size());
        assertTrue(plan.sections().isEmpty(), "no identity declared, so no group is claimed");
    }

    @Test
    void idIsSluggedFromTheTitleUnlessDeclared() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Execution Traces!", "services/*/TRACE.md"),
                new BookPlan.SectionSpec("notes-x", "Notes", null, null,
                        List.of("services/*/NOTES.md"), List.of(), List.of())), "pdf-a4");

        assertEquals("execution-traces", plan.sections().get(0).id());
        assertEquals("notes-x", plan.sections().get(1).id());
    }

    @Test
    void landingTemplatePresetIsResolvedToATemplateName() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.SectionSpec(null, "Traces", "minimal", null,
                        List.of("services/*/TRACE.md"), List.of(), List.of())), "pdf-a4");

        assertEquals("site-section-minimal", plan.sections().get(0).landingTemplate());
    }

    @Test
    void landingPageDefaultsToTrueAndCanBeDeclaredOff() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Traces", "services/*/TRACE.md"),
                new BookPlan.SectionSpec(null, "Notes", null, null,
                        List.of("services/*/NOTES.md"), List.of(), List.of(), false)), "pdf-a4");

        assertTrue(plan.sections().get(0).landingPage(),
                "a spec that says nothing about a page gets one");
        assertFalse(plan.sections().get(1).landingPage(),
                "and one that declares landingPage false does not");
        assertEquals(6, plan.cards().size(),
                "declining a page changes nothing about which cards the part claims");
    }

    @Test
    void whereSkipsThePartAndItsCardsEntirely() throws IOException {
        servicesTree();

        List<BookPlan.SectionSpec> specs = List.of(
                new BookPlan.SectionSpec(null, "Web Only", null, "target == 'web'",
                        List.of("services/*/NOTES.md"), List.of(), List.of()),
                part("Traces", "services/*/TRACE.md"));

        BookPlan.Plan pdf = BookPlan.resolve(bookRoot, specs, "pdf-a4");
        assertEquals(3, pdf.cards().size());
        assertEquals(1, pdf.sections().size());

        BookPlan.Plan web = BookPlan.resolve(bookRoot, specs, "web");
        assertEquals(6, web.cards().size());
        assertEquals(2, web.sections().size());
    }

    @Test
    void anExcludedPartDoesNotClaimItsCardsAwayFromALaterPart() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.SectionSpec(null, "Web Only", null, "target == 'web'",
                        List.of("services/*/TRACE.md"), List.of(), List.of()),
                part("Traces", "services/**")), "pdf-a4");

        assertEquals(6, plan.cards().size(),
                "the skipped part claims nothing, so the fallback part still sees every card");
        assertEquals(List.of("traces"), plan.sections().stream().map(Section::id).toList());
    }

    // ---- validation ----

    @Test
    void aPartThatMatchesNothingIsWarnedAboutAndOmitted() throws IOException {
        servicesTree();

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                part("Missing", "services/*/ABSENT.md"),
                part("Traces", "services/*/TRACE.md")), "pdf-a4");

        assertEquals(List.of("traces"), plan.sections().stream().map(Section::id).toList());
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

    // ---- HTML cards: opted in by the pattern's spelling ----

    @Test
    void htmlMatchesOnlyAPatternThatSaysHtml() throws IOException {
        servicesTree();
        card("pages/interstitial.html", "<h1>Between</h1>\n");

        BookPlan.Plan htmlPlan = BookPlan.resolve(bookRoot,
                List.of(part("Pages", "pages/*.html")), "pdf-a4");
        assertEquals(List.of("pages/interstitial.html"), relative(htmlPlan.cards()),
                "a pattern ending .html claims html cards");

        BookPlan.Plan sweep = BookPlan.resolve(bookRoot,
                List.of(part("Everything", "**")), "pdf-a4");
        assertFalse(relative(sweep.cards()).contains("pages/interstitial.html"),
                "a bare sweep never claims html");
        assertTrue(sweep.warnings().stream().anyMatch(w ->
                        w.contains("interstitial.html") && w.contains(".html")),
                "and says so instead of staying silent: " + sweep.warnings());
    }

    @Test
    void htmlClaimedByAnHtmlPatternDoesNotWarnOnTheSweep() throws IOException {
        servicesTree();
        card("pages/interstitial.html", "<h1>Between</h1>\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(
                new BookPlan.SectionSpec("all", "All", null, null,
                        List.of("pages/*.html", "**"), List.of(), List.of())), "pdf-a4");

        assertTrue(relative(plan.cards()).contains("pages/interstitial.html"));
        assertTrue(plan.warnings().stream().noneMatch(w -> w.contains("interstitial")),
                "claimed by the html pattern, so the sweep has nothing to warn about: "
                        + plan.warnings());
    }

    // ---- toolchain output is never a candidate ----

    @Test
    void targetAndNodeModulesAreNeverSweptUp() throws IOException {
        servicesTree();
        card("target/site/stale.md", "# stale copy\n");
        card("node_modules/dep/README2.md", "# somebody else's docs\n");

        BookPlan.Plan plan = BookPlan.resolve(bookRoot, List.of(part("All", "**/*.md")), "pdf-a4");

        List<String> cards = relative(plan.cards());
        assertFalse(cards.stream().anyMatch(c -> c.startsWith("target/")), cards.toString());
        assertFalse(cards.stream().anyMatch(c -> c.startsWith("node_modules/")), cards.toString());
        assertEquals(6, plan.cards().size(), "the six real service cards, nothing else");
    }

    @Test
    void aRootInsideAnExcludedDirStillWorks() throws IOException {
        // The escape hatch: the excluded NAME is only a filter on segments
        // BELOW the root, so pointing <root> inside target/ works.
        card("target/book/a.md", "# A\n");
        Path inner = bookRoot.resolve("target/book");

        BookPlan.Plan plan = BookPlan.resolve(inner, List.of(part("All", "*.md")), "pdf-a4");

        assertEquals(1, plan.cards().size());
    }
}
