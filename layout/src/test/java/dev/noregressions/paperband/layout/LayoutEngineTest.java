package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.*;
import dev.noregressions.paperband.render.PageSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LayoutEngineTest {

    @Nested
    @DisplayName("Engine construction")
    class EngineConstruction {

        @Test
        void should_create_default_engine() {
            LayoutEngine engine = new LayoutEngine();

            assertNotNull(engine);
            // Should not throw when rendering basic content
            assertDoesNotThrow(() -> {
                Card card = createMinimalCard("test");
                RenderContext ctx = createMinimalContext();
                engine.render(card, ctx);
            });
        }

        @Test
        void should_create_engine_with_book_root(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine(tempDir);

            assertNotNull(engine);
            assertDoesNotThrow(() -> {
                Card card = createMinimalCard("test");
                RenderContext ctx = createMinimalContext();
                engine.render(card, ctx);
            });
        }

        @Test
        void should_create_engine_with_book_root_and_theme(@TempDir Path tempDir) {
            ThemeBundle theme = ThemeBundle.NONE;
            LayoutEngine engine = new LayoutEngine(tempDir, theme);

            assertNotNull(engine);
            assertDoesNotThrow(() -> {
                Card card = createMinimalCard("test");
                RenderContext ctx = createMinimalContext();
                engine.render(card, ctx);
            });
        }

        @Test
        void should_handle_null_theme() {
            LayoutEngine engine = new LayoutEngine(null, null);

            assertNotNull(engine);
            // Should use ThemeBundle.NONE internally
        }

        @Test
        void should_use_layouts_directory_when_present(@TempDir Path tempDir) throws IOException {
            // Create layouts directory with custom template
            Path layoutsDir = Files.createDirectories(tempDir.resolve("layouts"));
            Files.writeString(layoutsDir.resolve("custom.html"), """
                <html>
                <head><title>{{ card.title }}</title></head>
                <body class="custom">{{ card.blocks[0].html | raw }}</body>
                </html>
                """);

            LayoutEngine engine = new LayoutEngine(tempDir);
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            String result = engine.render(card, ctx, "custom");

            assertTrue(result.contains("class=\"custom\""));
            assertTrue(result.contains("<title>Test Card</title>"));
        }
    }

    @Nested
    @DisplayName("Single card rendering")
    class SingleCardRendering {

        @Test
        void should_render_card_with_default_layout() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            String result = engine.render(card, ctx);

            assertNotNull(result);
            assertTrue(result.contains("Test Card")); // Card title should appear
            assertFalse(result.trim().isEmpty());
        }

        @Test
        void should_render_card_with_named_layout() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            // Use the default layout explicitly
            String result = engine.render(card, ctx, "card");

            assertNotNull(result);
            assertTrue(result.contains("Test Card"));
        }

        @Test
        void should_include_css_in_output() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            // Create context with CSS chain
            BookConfig book = new BookConfig(null, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(), Map.of(), null, "pdf", "A4"
            );

            String result = engine.render(card, ctx);

            // Default templates should include CSS somewhere in the output
            assertNotNull(result);
        }

        @Test
        void should_handle_card_with_multiple_blocks() {
            LayoutEngine engine = new LayoutEngine();

            // Create card with multiple blocks
            Block intro = new Block(
                Block.Kind.HEADING_SECTION,
                null,
                Set.of("intro"),
                null,
                0,
                "<p>Introduction content</p>",
                List.of()
            );
            Block section = new Block(
                Block.Kind.HEADING_SECTION,
                "section1",
                Set.of("section1"),
                "First Section",
                2,
                "<p>Section content</p>",
                List.of()
            );

            Frontmatter fm = new Frontmatter(Map.of());
            Card card = new Card("multi", Path.of("multi.md"), fm, "Multi Block Card", List.of(intro, section));
            RenderContext ctx = createMinimalContext();

            String result = engine.render(card, ctx);

            assertNotNull(result);
            assertFalse(result.trim().isEmpty());
            assertTrue(result.contains("Multi Block Card")); // Title should be present
        }

        @Test
        void should_throw_layout_exception_for_missing_template() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            LayoutException ex = assertThrows(LayoutException.class, () ->
                engine.render(card, ctx, "nonexistent-template"));

            assertTrue(ex.getMessage().contains("nonexistent-template"));
            assertTrue(ex.getMessage().contains("test")); // Card ID
        }
    }

    @Nested
    @DisplayName("Book rendering")
    class BookRendering {

        @Test
        void should_render_single_context_book() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(
                createMinimalCard("card1"),
                createMinimalCard("card2")
            );
            RenderContext ctx = createMinimalContext();

            String result = engine.renderBook(cards, ctx);

            assertNotNull(result);
            // Should contain both cards
            assertTrue(result.contains("Test Card")); // Both cards have same title
        }

        @Test
        void should_render_multi_context_book() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(
                createMinimalCard("card1"),
                createMinimalCard("card2")
            );
            List<RenderContext> contexts = List.of(
                createMinimalContext(),
                createMinimalContext()
            );
            RenderContext bookCtx = createMinimalContext();

            String result = engine.renderBook(cards, contexts, bookCtx);

            assertNotNull(result);
            assertFalse(result.trim().isEmpty());
        }

        @Test
        void should_render_book_with_named_layout() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(createMinimalCard("test"));
            List<RenderContext> contexts = List.of(createMinimalContext());
            RenderContext bookCtx = createMinimalContext();

            String result = engine.renderBook(cards, contexts, bookCtx, "book");

            assertNotNull(result);
        }

        @Test
        void should_throw_for_mismatched_cards_and_contexts() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(createMinimalCard("card1"), createMinimalCard("card2"));
            List<RenderContext> contexts = List.of(createMinimalContext()); // Only one context

            RenderContext bookCtx = createMinimalContext();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                engine.renderBook(cards, contexts, bookCtx));

            assertTrue(ex.getMessage().contains("size mismatch"));
            assertTrue(ex.getMessage().contains("2")); // cards size
            assertTrue(ex.getMessage().contains("1")); // contexts size
        }

        @Test
        void should_handle_empty_card_list() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of();
            List<RenderContext> contexts = List.of();
            RenderContext bookCtx = createMinimalContext();

            String result = engine.renderBook(cards, contexts, bookCtx);

            assertNotNull(result);
            // Should not throw and should produce valid output
        }
    }

    @Nested
    @DisplayName("Declared part dividers")
    class DeclaredPartDividers {

        /** A book whose sections: groups two folders under one title. */
        private BookConfig bookWithFoundationsPart(Path tempDir, String landingTemplate) {
            Section foundations = new Section(
                    "foundations", "Foundations", List.of("intro", "authoring"), landingTemplate);
            return new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null, List.of(foundations));
        }

        @Test
        void should_render_one_divider_for_a_part_spanning_several_folders(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            // Two folders, one declared part -- so one divider, not two.
            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("intro").resolve("intro.md")),
                    createCardAtPath("authoring", tempDir.resolve("authoring").resolve("authoring.md")));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(bookWithFoundationsPart(tempDir, null),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertEquals(1, countOccurrences(result, "id=\"section-divider-foundations\""),
                    "the part is one group, so it gets exactly one divider");
            assertFalse(result.contains("id=\"section-divider-intro\""),
                    "a folder claimed by a part must not also divide on its own name");
            assertFalse(result.contains("id=\"section-divider-authoring\""));
            assertTrue(result.contains("Foundations"), "divider shows the part's title");
        }

        @Test
        void should_place_the_part_divider_before_the_first_card_of_the_part(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("intro").resolve("intro.md")),
                    createCardAtPath("authoring", tempDir.resolve("authoring").resolve("authoring.md")));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(bookWithFoundationsPart(tempDir, null),
                            List.of(), Map.of(), null, "pdf", "A4"));

            int divider = result.indexOf("id=\"section-divider-foundations\"");
            int first = result.indexOf("id=\"card-intro\"");
            int second = result.indexOf("id=\"card-authoring\"");
            assertTrue(divider >= 0 && divider < first, "divider precedes the part's first card");
            assertTrue(first < second, "cards keep their walk order inside the part");
        }

        @Test
        void should_leave_unclaimed_folders_as_discovered_sections(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            // "extras" belongs to no part -- the mix of declaration and discovery.
            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("intro").resolve("intro.md")),
                    createCardAtPath("extra", tempDir.resolve("extras").resolve("extra.md")));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(bookWithFoundationsPart(tempDir, null),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertEquals(1, countOccurrences(result, "id=\"section-divider-foundations\""));
            assertEquals(1, countOccurrences(result, "id=\"section-divider-extras\""),
                    "a folder no part claims still divides on its own folder name");
            assertTrue(result.contains("Extras"), "auto-formatted label for the discovered section");
        }

        @Test
        void should_honour_a_per_part_landing_template(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("intro").resolve("intro.md")));
            List<RenderContext> contexts = List.of(createMinimalContext());

            // Same already-resolved form ConfigLoader produces for `landing: { template: minimal }`.
            String result = engine.renderBook(cards, contexts,
                    new RenderContext(bookWithFoundationsPart(tempDir, "site-section-minimal"),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertTrue(result.contains("id=\"section-divider-foundations\""));
            assertFalse(result.contains("class=\"tier-count\""),
                    "the part resolved to the minimal preset, so no count/TOC");
        }
    }

    @Nested
    @DisplayName("Pattern-declared part dividers")
    class PatternDeclaredPartDividers {

        /**
         * The shape {@code BookPlan} produces for a POM-declared
         * {@code <book><sections>}: sections that claim individual card files rather
         * than whole folders, so two of them can draw different files out of
         * one directory.
         */
        private BookConfig bookWithCardClaimingParts(Path tempDir, List<Path> traces, List<Path> notes) {
            Section tracesPart = new Section("traces", "Execution Traces", List.of(), null, traces);
            Section notesPart = new Section("notes", "Notes", List.of(), null, notes);
            return new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null,
                    List.of(tracesPart, notesPart));
        }

        @Test
        void should_group_cards_a_part_claims_by_path_whatever_folder_they_sit_in(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            // One TRACE and one NOTES card in each of two service folders.
            Path authTrace = tempDir.resolve("services").resolve("auth").resolve("TRACE.md");
            Path billingTrace = tempDir.resolve("services").resolve("billing").resolve("TRACE.md");
            Path authNotes = tempDir.resolve("services").resolve("auth").resolve("NOTES.md");
            Path billingNotes = tempDir.resolve("services").resolve("billing").resolve("NOTES.md");

            List<Card> cards = List.of(
                    createCardAtPath("auth-trace", authTrace),
                    createCardAtPath("billing-trace", billingTrace),
                    createCardAtPath("auth-notes", authNotes),
                    createCardAtPath("billing-notes", billingNotes));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext(),
                    createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(
                            bookWithCardClaimingParts(tempDir,
                                    List.of(authTrace, billingTrace),
                                    List.of(authNotes, billingNotes)),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertEquals(1, countOccurrences(result, "id=\"section-divider-traces\""),
                    "every claimed trace card is one group, however its folder is named");
            assertEquals(1, countOccurrences(result, "id=\"section-divider-notes\""));
            assertFalse(result.contains("id=\"section-divider-services\""),
                    "the shared folder name must not surface as a section of its own");
            assertTrue(result.contains("Execution Traces"), "divider shows the part's title");

            int traces = result.indexOf("id=\"section-divider-traces\"");
            int notes = result.indexOf("id=\"section-divider-notes\"");
            int firstNotesCard = result.indexOf("id=\"card-auth-notes\"");
            assertTrue(traces < notes, "sections divide in declared order");
            assertTrue(notes < firstNotesCard, "each divider precedes its part's first card");
        }

        @Test
        void should_leave_unclaimed_cards_to_folder_derived_sections(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path authTrace = tempDir.resolve("services").resolve("auth").resolve("TRACE.md");
            Path appendix = tempDir.resolve("appendix").resolve("glossary.md");

            List<Card> cards = List.of(
                    createCardAtPath("auth-trace", authTrace),
                    createCardAtPath("glossary", appendix));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(
                            bookWithCardClaimingParts(tempDir, List.of(authTrace), List.of()),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertEquals(1, countOccurrences(result, "id=\"section-divider-traces\""));
            assertEquals(1, countOccurrences(result, "id=\"section-divider-appendix\""),
                    "a card no part claims still divides on its own folder name");
        }
    }

    @Nested
    @DisplayName("Divider page geometry")
    class DividerPageGeometry {

        /**
         * A part divider is a <em>page</em>, not a rule between cards. The
         * scaffold has to say both halves of that: a forced break on each
         * side (so nothing shares the sheet) and a box standing up to the
         * full printable height (so the heading has a page to centre in).
         * Verified against a real render at every bundled page size — the
         * assertion here is the guard against a CSS edit quietly turning the
         * page back into a horizontal rule.
         */
        /**
         * A theme with a coloured ground can only reach the trim edge in a
         * full-bleed build, and then has to supply every inset itself. It can
         * only size those insets against the build's own margin if the margin
         * is actually stamped where CSS can read it.
         */
        @Test
        void the_builds_page_margins_reach_css_as_custom_properties(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("front").resolve("intro.md")));
            List<RenderContext> contexts = List.of(createMinimalContext());
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null);

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));

            // A4's standard margins are 20mm on every edge.
            assertTrue(result.contains("--pw-page-margin-top: 20"), "top margin, in mm");
            assertTrue(result.contains("--pw-page-margin-right: 20"));
            assertTrue(result.contains("--pw-page-margin-bottom: 20"));
            assertTrue(result.contains("--pw-page-margin-left: 20"));
        }

        /**
         * The text measure is the largest of the insets that make a
         * full-bleed page still look margined, and a book can't reach it
         * through its css (theme CSS is inlined after it). Declaring it puts
         * it inline on {@code <html>}, which outranks the theme's own rule.
         */
        @Test
        void a_declared_measure_is_stamped_inline_where_it_outranks_the_theme(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("front").resolve("intro.md")));
            List<RenderContext> contexts = List.of(createMinimalContext());
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null);

            String declared = engine.renderBook(cards, contexts, new RenderContext(
                    book, List.of(), Map.of("page", Map.of("measure", "58rem")),
                    null, "pdf", "A4"));
            assertTrue(declared.contains("--card-max-width: 58rem"),
                    "stamped in the style attribute, not the stylesheet");

            String undeclared = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));
            // "--card-max-width:" (with the colon) is the stamped declaration;
            // the scaffold legitimately READS the token via
            // var(--card-max-width, ...) whether or not a book declares one.
            assertFalse(undeclared.contains("--card-max-width:"),
                    "a book that says nothing leaves the theme's own measure alone");
        }

        @Test
        void a_measure_that_is_not_a_length_is_rejected(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("front").resolve("intro.md")));
            List<RenderContext> contexts = List.of(createMinimalContext());
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null);

            // It lands in a style attribute, so it is never passed through
            // unvalidated. The engine reports it like any other layout
            // failure, with the offending value named.
            LayoutException e = assertThrows(LayoutException.class, () -> engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(),
                            Map.of("page", Map.of("measure", "58rem; background: red")),
                            null, "pdf", "A4")));
            assertTrue(e.getMessage().contains("page.measure"),
                    "the message names the key at fault: " + e.getMessage());
        }

        @Test
        void the_section_divider_scaffold_makes_the_divider_its_own_centred_page(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            List<Card> cards = List.of(
                    createCardAtPath("intro", tempDir.resolve("front").resolve("intro.md")));
            List<RenderContext> contexts = List.of(createMinimalContext());
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null);

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));

            int rule = result.indexOf(".section-divider {");
            assertTrue(rule >= 0, "the scaffold styles .section-divider");
            String scaffold = result.substring(rule, result.indexOf('}', rule));
            assertTrue(scaffold.contains("break-before: page"), "a sheet of its own");
            assertTrue(scaffold.contains("break-after: page"), "and the next card starts a new one");
            assertTrue(scaffold.contains("var(--pw-content-height"),
                    "full printable height, from the build's PageSpec rather than a hardcoded page size");
            assertTrue(scaffold.contains("align-items: center") && scaffold.contains("justify-content: center"),
                    "heading centred on the page, both axes");
            assertTrue(result.contains("--pw-content-height:"),
                    "and the variable the rule reads is actually stamped on <html>");
        }
    }

    @Nested
    @DisplayName("Parts with no page of their own")
    class PageLessParts {

        /**
         * Two pattern-declared sections, the first of which opted out of a page
         * of its own — the shape {@code BookPlan} produces for
         * {@code <section><landingPage>false</landingPage></section>}.
         */
        private BookConfig bookWithPageLessFirstPart(Path tempDir, List<Path> setup, List<Path> traces) {
            Section setupPart = new Section("setup", "Introduction and Setup", List.of(), null, setup, false);
            Section tracesPart = new Section("traces", "Scenarios", List.of(), null, traces, true);
            return new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null,
                    List.of(setupPart, tracesPart));
        }

        @Test
        void should_omit_the_divider_for_a_part_that_declined_a_page(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path install = tempDir.resolve("setup").resolve("install.md");
            Path trace = tempDir.resolve("scenarios").resolve("checkout").resolve("TRACE.md");

            List<Card> cards = List.of(
                    createCardAtPath("install", install),
                    createCardAtPath("checkout-trace", trace));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(
                            bookWithPageLessFirstPart(tempDir, List.of(install), List.of(trace)),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertFalse(result.contains("id=\"section-divider-setup\""),
                    "the part declined a page, so no divider fronts its cards");
            assertFalse(result.contains("href=\"#section-divider-setup\""),
                    "and no anchor bait either -- there's no destination to name");
            assertEquals(1, countOccurrences(result, "id=\"section-divider-traces\""),
                    "a sibling part that kept its page still gets one");
            assertTrue(result.contains("id=\"card-install\""),
                    "the part's cards are still in the book, in place");
        }

        @Test
        void should_not_emit_a_site_landing_page_for_a_part_that_declined_one(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path install = tempDir.resolve("setup").resolve("install.md");
            Path trace = tempDir.resolve("scenarios").resolve("checkout").resolve("TRACE.md");

            List<Card> cards = List.of(
                    createCardAtPath("install", install),
                    createCardAtPath("checkout-trace", trace));
            List<RenderContext> contexts = List.of(createMinimalContext(), createMinimalContext());

            Map<String, String> site = engine.renderSite(cards, contexts,
                    new RenderContext(
                            bookWithPageLessFirstPart(tempDir, List.of(install), List.of(trace)),
                            List.of(), Map.of(), null, "pdf", "A4"));

            assertFalse(site.containsKey("setup.html"), "no page for the part that declined one");
            assertTrue(site.containsKey("traces.html"), "the sibling part still gets its page");
            assertTrue(site.containsKey("cards/install.html"),
                    "its cards keep their own pages regardless");

            String index = site.get("index.html");
            assertTrue(index.contains("Introduction and Setup"),
                    "the group is still labelled on the index");
            assertFalse(index.contains("href=\"setup.html\""),
                    "but nothing links to a page that was never written");
            assertTrue(index.contains("href=\"traces.html\""));
        }

        @Test
        void should_drop_the_section_back_link_on_a_page_less_sections_cards(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path install = tempDir.resolve("setup").resolve("install.md");

            List<Card> cards = List.of(createCardAtPath("install", install));
            List<RenderContext> contexts = List.of(createMinimalContext());

            Map<String, String> site = engine.renderSite(cards, contexts,
                    new RenderContext(
                            bookWithPageLessFirstPart(tempDir, List.of(install), List.of()),
                            List.of(), Map.of(), null, "pdf", "A4"));

            String card = site.get("cards/install.html");
            assertNotNull(card);
            assertFalse(card.contains("href=\"../setup.html\""),
                    "the card page cannot link back to a page that doesn't exist");
        }

        @Test
        void should_keep_the_page_by_default(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path install = tempDir.resolve("setup").resolve("install.md");
            // Same part, declared without saying anything about a page.
            Section setupPart = new Section("setup", "Introduction and Setup", List.of(), null,
                    List.of(install));
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null, List.of(setupPart));

            List<Card> cards = List.of(createCardAtPath("install", install));
            List<RenderContext> contexts = List.of(createMinimalContext());
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            assertTrue(engine.renderBook(cards, contexts, bookCtx)
                            .contains("id=\"section-divider-setup\""),
                    "generation is on unless a declaration opts out");
            assertTrue(engine.renderSite(cards, contexts, bookCtx).containsKey("setup.html"));
        }

        @Test
        void should_honour_the_opt_out_on_a_folder_claiming_part_too(@TempDir Path tempDir) {
            // The shape a yaml `sections:` entry with `landing: false` produces —
            // membership by folder, not by card pattern.
            LayoutEngine engine = new LayoutEngine();

            Path install = tempDir.resolve("setup").resolve("install.md");
            Section setupPart = new Section("setup", "Introduction and Setup",
                    List.of("setup"), null, List.of(), false);
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null, List.of(setupPart));

            List<Card> cards = List.of(createCardAtPath("install", install));
            List<RenderContext> contexts = List.of(createMinimalContext());
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            assertFalse(engine.renderBook(cards, contexts, bookCtx)
                            .contains("id=\"section-divider-setup\""),
                    "a yaml part that declined its page gets no PDF divider");
            Map<String, String> site = engine.renderSite(cards, contexts, bookCtx);
            assertFalse(site.containsKey("setup.html"), "and no site landing page");
            assertTrue(site.get("index.html").contains("Introduction and Setup"),
                    "the group label survives on the index");
        }
    }

    @Nested
    @DisplayName("Book section dividers")
    class BookSectionDividers {

        @Test
        void should_render_one_section_divider_before_first_card_of_each_plain_section(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path frontCard1 = tempDir.resolve("front").resolve("intro.md");
            Path frontCard2 = tempDir.resolve("front").resolve("preface.md");
            Path backCard = tempDir.resolve("back").resolve("conclusion.md");

            List<Card> cards = List.of(
                    createCardAtPath("intro", frontCard1),
                    createCardAtPath("preface", frontCard2),
                    createCardAtPath("conclusion", backCard));
            List<RenderContext> contexts = List.of(
                    createMinimalContext(), createMinimalContext(), createMinimalContext());

            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            String result = engine.renderBook(cards, contexts, bookCtx);

            // One divider per section, not per card.
            assertEquals(1, countOccurrences(result, "id=\"section-divider-front\""));
            assertEquals(1, countOccurrences(result, "id=\"section-divider-back\""));

            // Auto-formatted section labels (no folder paperband.yaml title override).
            assertTrue(result.contains("Front"));
            assertTrue(result.contains("Back"));

            // The front divider precedes both front cards; the back divider
            // precedes the back card and follows both front cards.
            int frontDividerPos = result.indexOf("id=\"section-divider-front\"");
            int introPos = result.indexOf("id=\"card-intro\"");
            int prefacePos = result.indexOf("id=\"card-preface\"");
            int backDividerPos = result.indexOf("id=\"section-divider-back\"");
            int conclusionPos = result.indexOf("id=\"card-conclusion\"");

            assertTrue(frontDividerPos < introPos);
            assertTrue(introPos < prefacePos);
            assertTrue(prefacePos < backDividerPos);
            assertTrue(backDividerPos < conclusionPos);
        }

        @Test
        void should_omit_count_and_toc_when_section_resolves_to_minimal_preset(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path frontCard = tempDir.resolve("front").resolve("intro.md");
            List<Card> cards = List.of(createCardAtPath("intro", frontCard));
            List<RenderContext> contexts = List.of(createMinimalContext());

            // BookConfig.sectionLandingTemplate already holds the *resolved*
            // bare template name (ConfigLoader runs the raw "minimal" preset
            // name through NamedTemplates before constructing BookConfig) --
            // "site-section-minimal" is what a real `sections: { landing:
            // { template: minimal } }` book config would have produced.
            BookConfig book = new BookConfig(
                    tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, "site-section-minimal");
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            String result = engine.renderBook(cards, contexts, bookCtx);

            assertTrue(result.contains("id=\"section-divider-front\""));
            // "tier-count"/"tier-toc" also appear in book.html's static CSS
            // scaffold regardless of whether any divider renders them, so
            // check for the rendered elements specifically, not the bare
            // substring.
            assertFalse(result.contains("class=\"tier-count\""));
            assertFalse(result.contains("class=\"tier-toc\""));
        }

        @Test
        void should_include_count_and_toc_when_section_uses_default_template(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path frontCard = tempDir.resolve("front").resolve("intro.md");
            List<Card> cards = List.of(createCardAtPath("intro", frontCard));
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            String result = engine.renderBook(cards, contexts, bookCtx);

            assertTrue(result.contains("id=\"section-divider-front\""));
            assertTrue(result.contains("class=\"tier-count\""));
        }

        @Test
        void should_not_render_section_divider_for_cards_with_an_axis_value() {
            LayoutEngine engine = new LayoutEngine();

            Axis tierAxis = new Axis("tier", "Tier", List.of(AxisValue.of(1, "Tier 1")), null);
            Card card = createCardWithFrontmatter("card1", Map.of("tier", 1));
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(null, "Test Book", List.of(tierAxis), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            String result = engine.renderBook(cards, contexts, bookCtx);

            assertFalse(result.contains("section-divider-"));
            assertTrue(result.contains("axis-divider-tier-1"));
        }

        @Test
        void should_keep_axis_labelled_cards_in_their_sections(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path intro = tempDir.resolve("route").resolve("intro.md");
            Path lab1 = tempDir.resolve("route").resolve("lab1.md");
            Path lab2 = tempDir.resolve("extras").resolve("lab2.md");

            List<Card> cards = List.of(
                    createCardAt("intro", intro, Map.of()),
                    createCardAt("lab1", lab1, Map.of("kind", "scenario")),
                    createCardAt("lab2", lab2, Map.of("kind", "scenario")));
            List<RenderContext> contexts = List.of(
                    createMinimalContext(), createMinimalContext(), createMinimalContext());

            Axis kind = new Axis("kind", "Kind", List.of(AxisValue.of("scenario", "Scenario")), null);
            Section route = new Section("route", "The Route", List.of(), null, List.of(intro, lab1));
            Section extras = new Section("extras", "Extras", List.of(), null, List.of(lab2));
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(kind), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null,
                    List.of(route, extras));

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));

            // Membership: the labelled card counts toward its section and is
            // listed in the section divider's TOC.
            assertEquals(1, countOccurrences(result, "id=\"section-divider-route\""),
                    "sections own their cards whether or not those cards carry axis values");
            int routeDivider = result.indexOf("id=\"section-divider-route\"");
            int firstCard = result.indexOf("id=\"card-intro\"");
            String routeBlock = result.substring(routeDivider, firstCard);
            assertTrue(routeBlock.contains("#card-lab1"),
                    "the labelled card is listed in its section divider's TOC");
            assertTrue(routeBlock.contains("2 cards"),
                    "the labelled card counts toward its section");

            // A section whose only card carries an axis value still gets its
            // divider, as long as no axis divider fires on that same card.
            assertEquals(1, countOccurrences(result, "id=\"section-divider-extras\""),
                    "a fully labelled section still gets a divider page");

            // The axis divider fires once, before the first labelled card;
            // divider exclusivity is per card, not per membership.
            assertEquals(1, countOccurrences(result, "id=\"axis-divider-kind-scenario\""));
        }

        @Test
        void should_let_an_axis_divider_suppress_the_section_divider_on_the_same_card(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path lab1 = tempDir.resolve("route").resolve("lab1.md");
            List<Card> cards = List.of(createCardAt("lab1", lab1, Map.of("kind", "scenario")));
            List<RenderContext> contexts = List.of(createMinimalContext());

            Axis kind = new Axis("kind", "Kind", List.of(AxisValue.of("scenario", "Scenario")), null);
            Section route = new Section("route", "The Route", List.of(), null, List.of(lab1));
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(kind), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null, List.of(route));

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));

            assertEquals(1, countOccurrences(result, "id=\"axis-divider-kind-scenario\""));
            assertFalse(result.contains("id=\"section-divider-route\""),
                    "at most one divider page precedes a card: the axis divider wins");
        }

        @Test
        void should_treat_a_dividerless_axis_as_label_only(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            Path lab1 = tempDir.resolve("route").resolve("lab1.md");
            List<Card> cards = List.of(createCardAt("lab1", lab1, Map.of("kind", "scenario")));
            List<RenderContext> contexts = List.of(createMinimalContext());

            Axis kind = new Axis("kind", "Kind",
                    List.of(AxisValue.of("scenario", "Scenario")), null, false);
            Section route = new Section("route", "The Route", List.of(), null, List.of(lab1));
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(kind), List.of(), Map.of(),
                    List.of(), null, null, null, null, null, null, null, List.of(route));

            String result = engine.renderBook(cards, contexts,
                    new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4"));

            assertFalse(result.contains("id=\"axis-divider-kind-scenario\""),
                    "a dividers:false axis emits no divider pages");
            assertEquals(1, countOccurrences(result, "id=\"section-divider-route\""),
                    "with the axis label-only, the section divider fires as if the axis were absent");
            assertTrue(result.contains("class=\"card kind-scenario\""),
                    "the axis still labels the card");
        }

    }

    @Nested
    @DisplayName("Site rendering")
    class SiteRendering {

        @Test
        void should_render_basic_site() {
            LayoutEngine engine = new LayoutEngine();

            // Create cards with tier information
            Card card1 = createCardWithFrontmatter("card1", Map.of("tier", 1));
            Card card2 = createCardWithFrontmatter("card2", Map.of("tier", 2));
            List<Card> cards = List.of(card1, card2);

            // Create contexts with tier in vars
            RenderContext ctx1 = createContextWithVars(Map.of("tier", 1));
            RenderContext ctx2 = createContextWithVars(Map.of("tier", 2));
            List<RenderContext> contexts = List.of(ctx1, ctx2);

            // Axis grouping is opt-in and explicit: only axes declared in the
            // book's axes: config get structural treatment (site pages,
            // dividers, nav). A card's own frontmatter/vars tier value alone
            // is not enough — this is what "formalizing" the tier axis means.
            Axis tierAxis = new Axis("tier", "Tier",
                    List.of(AxisValue.of(1, "Tier 1"), AxisValue.of(2, "Tier 2")), null);
            BookConfig book = new BookConfig(null, "Test Book", List.of(tierAxis), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            assertNotNull(result);
            assertTrue(result.containsKey("index.html"));
            assertTrue(result.containsKey("tier-1.html"));
            assertTrue(result.containsKey("tier-2.html"));
            assertTrue(result.containsKey("cards/card1.html"));
            assertTrue(result.containsKey("cards/card2.html"));

            // Verify content is not empty
            assertFalse(result.get("index.html").trim().isEmpty());
            assertFalse(result.get("cards/card1.html").trim().isEmpty());
        }

        @Test
        void should_handle_site_with_sections(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine();

            // Create cards in sections (no tier)
            Path frontPath = tempDir.resolve("front").resolve("intro.md");
            Path backPath = tempDir.resolve("back").resolve("conclusion.md");

            Card frontCard = createCardAtPath("intro", frontPath);
            Card backCard = createCardAtPath("conclusion", backPath);
            List<Card> cards = List.of(frontCard, backCard);

            List<RenderContext> contexts = List.of(
                createMinimalContext(),
                createMinimalContext()
            );

            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(
                book, List.of(), Map.of(), null, "pdf", "A4"
            );

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            assertTrue(result.containsKey("index.html"));
            assertTrue(result.containsKey("front.html"));
            assertTrue(result.containsKey("back.html"));
            assertTrue(result.containsKey("cards/intro.html"));
            assertTrue(result.containsKey("cards/conclusion.html"));
        }

        @Test
        void should_throw_for_mismatched_site_input() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(createMinimalCard("card1"));
            List<RenderContext> contexts = List.of(); // Empty contexts
            RenderContext bookCtx = createMinimalContext();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                engine.renderSite(cards, contexts, bookCtx));

            assertTrue(ex.getMessage().contains("size mismatch"));
        }
    }

    @Nested
    @DisplayName("Section landing template resolution")
    class SectionLandingTemplateResolution {

        @Test
        void should_use_built_in_template_when_no_override_declared(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = new LayoutEngine();

            Path cardPath = tempDir.resolve("plainSection").resolve("card1.md");
            Card card = createCardAtPath("card1", cardPath);
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            assertTrue(result.containsKey("plainSection.html"));
            // The bundled site-section.html always renders this heading class.
            assertTrue(result.get("plainSection.html").contains("cards-heading"));
        }

        @Test
        void should_use_book_default_section_template_when_declared(@TempDir Path tempDir) throws IOException {
            Path layouts = tempDir.resolve("layouts");
            Files.createDirectories(layouts);
            Path bookDefaultTemplate = layouts.resolve("custom-book-default.html");
            Files.writeString(bookDefaultTemplate, "<html>BOOK-DEFAULT-TEMPLATE: {{ section.label }}</html>");

            LayoutEngine engine = new LayoutEngine(tempDir);

            Path cardPath = tempDir.resolve("plainSection").resolve("card1.md");
            Card card = createCardAtPath("card1", cardPath);
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(
                    tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, "custom-book-default");
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            assertTrue(result.get("plainSection.html").contains("BOOK-DEFAULT-TEMPLATE"));
        }

        @Test
        void should_prefer_folder_override_over_book_default(@TempDir Path tempDir) throws IOException {
            Path layouts = tempDir.resolve("layouts");
            Files.createDirectories(layouts);
            Path bookDefaultTemplate = layouts.resolve("custom-book-default.html");
            Files.writeString(bookDefaultTemplate, "<html>BOOK-DEFAULT-TEMPLATE: {{ section.label }}</html>");
            Path folderTemplate = layouts.resolve("custom-folder-override.html");
            Files.writeString(folderTemplate, "<html>FOLDER-OVERRIDE-TEMPLATE: {{ section.label }}</html>");

            // The section folder's own paperband.yaml overrides the book default,
            // using the same `landing: { template: <path> }` shape as an axis's
            // own per-value override.
            Path sectionDir = tempDir.resolve("overriddenSection");
            Files.createDirectories(sectionDir);
            Files.writeString(sectionDir.resolve("paperband.yaml"), """
                    landing:
                      template: "layouts/custom-folder-override.html"
                    """);

            LayoutEngine engine = new LayoutEngine(tempDir);

            Path cardPath = sectionDir.resolve("card1.md");
            Card card = createCardAtPath("card1", cardPath);
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(
                    tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, "custom-book-default");
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            assertTrue(result.get("overriddenSection.html").contains("FOLDER-OVERRIDE-TEMPLATE"));
            assertFalse(result.get("overriddenSection.html").contains("BOOK-DEFAULT-TEMPLATE"));
        }

        @Test
        void should_support_named_minimal_preset_as_book_default(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = new LayoutEngine();

            Path cardPath = tempDir.resolve("plainSection").resolve("card1.md");
            Card card = createCardAtPath("card1", cardPath);
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            // BookConfig.sectionLandingTemplate is already resolved by the time it
            // gets here (ConfigLoader runs raw yaml values through NamedTemplates
            // before constructing BookConfig) -- "minimal" the preset name resolves
            // to "site-section-minimal" the bundled template, which is what a real
            // book config would have produced from `sections.landing.template: minimal`.
            BookConfig book = new BookConfig(
                    tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, "site-section-minimal");
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            String page = result.get("plainSection.html");
            assertNotNull(page);
            // site-section-minimal.html shows only the title, no count or card grid.
            // Matched as markup, not as bare class names: site-base.css is inlined
            // into every page's head and styles both classes, so the names appear
            // in a page that emits neither element.
            assertFalse(page.contains("class=\"cards-heading\""));
            assertFalse(page.contains("class=\"card-grid\""));
            assertTrue(page.contains("<h1>"));
        }

        @Test
        void should_support_named_minimal_preset_as_folder_override(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = new LayoutEngine();

            Path sectionDir = tempDir.resolve("scanners");
            Files.createDirectories(sectionDir);
            Files.writeString(sectionDir.resolve("paperband.yaml"), """
                    title: "Scanners & Blindspots"
                    landing:
                      template: "minimal"
                    """);

            Path cardPath = sectionDir.resolve("card1.md");
            Card card = createCardAtPath("card1", cardPath);
            List<Card> cards = List.of(card);
            List<RenderContext> contexts = List.of(createMinimalContext());

            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            Map<String, String> result = engine.renderSite(cards, contexts, bookCtx);

            String page = result.get("scanners.html");
            assertNotNull(page);
            assertTrue(page.contains("Scanners &amp; Blindspots") || page.contains("Scanners & Blindspots"));
            assertFalse(page.contains("class=\"cards-heading\""));
            assertFalse(page.contains("class=\"card-grid\""));
        }
    }

    @Nested
    @DisplayName("CSS composition")
    class CssComposition {

        @Test
        void should_handle_empty_css_chain() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(), Map.of(), null, "pdf", "A4" // Empty CSS chain
            );

            String result = engine.render(card, ctx);

            assertNotNull(result);
            // Should not throw with empty CSS
        }

        @Test
        void should_compose_css_from_files(@TempDir Path tempDir) throws IOException {
            // Create CSS files
            Path css1 = tempDir.resolve("base.css");
            Path css2 = tempDir.resolve("theme.css");
            Files.writeString(css1, "body { margin: 0; }");
            Files.writeString(css2, ".card { padding: 1rem; }");

            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(css1, css2), Map.of(), null, "pdf", "A4"
            );

            String result = engine.render(card, ctx);

            assertTrue(result.contains("body { margin: 0; }"));
            assertTrue(result.contains(".card { padding: 1rem; }"));
        }

        @Test
        void should_handle_missing_css_files(@TempDir Path tempDir) {
            Path missingCss = tempDir.resolve("missing.css");

            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(missingCss), Map.of(), null, "pdf", "A4"
            );

            String result = engine.render(card, ctx);

            assertTrue(result.contains("/* missing:"));
            assertTrue(result.contains("missing.css"));
        }

        @Test
        void should_include_theme_css() {
            // Create a theme with CSS
            ThemeBundle theme = new ThemeBundle("test-theme", List.of("/* theme style */"), null);
            LayoutEngine engine = new LayoutEngine(null, theme);

            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            String result = engine.render(card, ctx);

            assertTrue(result.contains("/* theme style */"));
        }
    }

    @Nested
    @DisplayName("Layout name resolution")
    class LayoutNameResolution {

        @Test
        void should_use_default_layout_when_null() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(), Map.of(), null, "pdf", "A4"
            );

            // Should use default layout without throwing
            String result = assertDoesNotThrow(() -> engine.render(card, ctx));
            assertNotNull(result);
        }

        @Test
        void should_use_specified_layout() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(), Map.of(), Path.of("custom.html"), "pdf", "A4"
            );

            // Should attempt to use custom.html (will fail since it doesn't exist)
            LayoutException ex = assertThrows(LayoutException.class, () ->
                engine.render(card, ctx));

            assertTrue(ex.getMessage().contains("custom"));
        }

        @Test
        void should_strip_extension_from_layout_filename() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            BookConfig book = new BookConfig(null, "Test", List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext ctx = new RenderContext(
                book, List.of(), Map.of(), Path.of("card.html"), "pdf", "A4"
            );

            // Should use "card" template (the default)
            String result = assertDoesNotThrow(() -> engine.render(card, ctx));
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void should_wrap_io_exceptions_in_layout_exception() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            LayoutException ex = assertThrows(LayoutException.class, () ->
                engine.render(card, ctx, "nonexistent"));

            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("nonexistent"));
            assertTrue(ex.getMessage().contains("test"));
            assertNotNull(ex.getCause());
        }

        @Test
        void should_handle_template_evaluation_errors(@TempDir Path tempDir) throws IOException {
            // Create a template with syntax errors
            Path layoutsDir = Files.createDirectories(tempDir.resolve("layouts"));
            Files.writeString(layoutsDir.resolve("broken.html"),
                "<html>{{ invalid syntax } missing closing }}</html>");

            LayoutEngine engine = new LayoutEngine(tempDir);
            Card card = createMinimalCard("test");
            RenderContext ctx = createMinimalContext();

            LayoutException ex = assertThrows(LayoutException.class, () ->
                engine.render(card, ctx, "broken"));

            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("broken"));
            assertTrue(ex.getMessage().contains("test"));
        }

        @Test
        void should_handle_book_rendering_errors() {
            LayoutEngine engine = new LayoutEngine();
            List<Card> cards = List.of(createMinimalCard("test"));
            List<RenderContext> contexts = List.of(createMinimalContext());
            RenderContext bookCtx = createMinimalContext();

            LayoutException ex = assertThrows(LayoutException.class, () ->
                engine.renderBook(cards, contexts, bookCtx, "nonexistent-book"));

            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("nonexistent-book"));
        }
    }

    @Nested
    @DisplayName("Model building")
    class ModelBuilding {

        @Test
        void should_build_card_model_with_frontmatter() {
            LayoutEngine engine = new LayoutEngine();

            Map<String, Object> frontmatterData = Map.of(
                "tier", 1,
                "effort", "M",
                "tags", List.of("spring", "database")
            );
            Card card = createCardWithFrontmatter("test", frontmatterData);
            RenderContext ctx = createMinimalContext();

            String result = engine.render(card, ctx);

            // The template should have access to frontmatter via LenientMap
            assertNotNull(result);
        }

        @Test
        void should_build_model_with_context_vars() {
            LayoutEngine engine = new LayoutEngine();
            Card card = createMinimalCard("test");

            Map<String, Object> vars = Map.of("sidebar", true, "author", "Test Author");
            RenderContext ctx = createContextWithVars(vars);

            String result = engine.render(card, ctx);

            // Template should have access to vars
            assertNotNull(result);
        }

        @Test
        void should_handle_sparse_frontmatter() {
            LayoutEngine engine = new LayoutEngine();

            // Create card with sparse frontmatter (some fields missing)
            Map<String, Object> frontmatterData = Map.of("tier", 1);
            Card card = createCardWithFrontmatter("test", frontmatterData);
            RenderContext ctx = createMinimalContext();

            // Should not throw with missing optional fields
            String result = assertDoesNotThrow(() -> engine.render(card, ctx));
            assertNotNull(result);
        }
    }

    // Helper methods

    private Card createMinimalCard(String id) {
        Block block = new Block(
            Block.Kind.HEADING_SECTION,
            null,
            Set.of("intro"),
            null,
            0,
            "<p>Test content</p>",
            List.of()
        );
        Frontmatter fm = new Frontmatter(Map.of());
        return new Card(id, Path.of(id + ".md"), fm, "Test Card", List.of(block));
    }

    private Card createCardWithFrontmatter(String id, Map<String, Object> frontmatterData) {
        Block block = new Block(
            Block.Kind.HEADING_SECTION,
            null,
            Set.of("intro"),
            null,
            0,
            "<p>Test content</p>",
            List.of()
        );
        Frontmatter fm = new Frontmatter(frontmatterData);
        return new Card(id, Path.of(id + ".md"), fm, "Test Card", List.of(block));
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private Card createCardAt(String id, Path path, Map<String, Object> frontmatterData) {
        Block block = new Block(
            Block.Kind.HEADING_SECTION,
            null,
            Set.of("intro"),
            null,
            0,
            "<p>Test content</p>",
            List.of()
        );
        return new Card(id, path, new Frontmatter(frontmatterData), "Test Card", List.of(block));
    }

    @Nested
    @DisplayName("Site watermark")
    class SiteWatermark {

        @Test
        void should_stamp_every_page_the_site_writes(@TempDir Path tempDir) {
            // A book that says DRAFT has to say it on the copy people link to,
            // not only in the PDF.
            Map<String, String> site = renderSiteWith(tempDir, Map.of("watermark", "DRAFT"));

            assertFalse(site.isEmpty());
            for (Map.Entry<String, String> page : site.entrySet()) {
                assertTrue(page.getValue().contains("pb-watermark"), page.getKey());
                assertTrue(page.getValue().contains(">DRAFT<"), page.getKey());
            }
        }

        @Test
        void should_leave_a_book_that_declares_none_untouched(@TempDir Path tempDir) {
            Map<String, String> site = renderSiteWith(tempDir, Map.of());

            for (Map.Entry<String, String> page : site.entrySet()) {
                assertFalse(page.getValue().contains("pb-watermark"), page.getKey());
            }
        }

        @Test
        void should_read_the_full_map_form(@TempDir Path tempDir) {
            Map<String, String> site = renderSiteWith(tempDir, Map.of("watermark",
                    Map.of("text", "SAMPLE", "color", "#aa0000", "opacity", 0.3, "tile", true)));

            String index = site.get("index.html");
            assertTrue(index.contains("#aa0000"), index);
            assertTrue(index.contains("opacity:0.3"), index);
            assertTrue(index.contains("pb-watermark-grid"), index);
        }

        @Test
        void should_point_an_image_watermark_at_the_right_depth(@TempDir Path tempDir) {
            // The site writes cards a directory down; a root-relative asset
            // path would 404 on exactly half the pages.
            Map<String, String> site = renderSiteWith(tempDir,
                    Map.of("watermark", Map.of("image", "brand/logo.png")));

            assertTrue(site.get("index.html").contains("src=\"assets/logo.png\""),
                    site.get("index.html"));
            assertTrue(site.get("cards/install.html").contains("src=\"../assets/logo.png\""),
                    site.get("cards/install.html"));
        }

        @Test
        void should_derive_the_url_prefix_from_the_page_key() {
            assertEquals("", LayoutEngine.urlPrefixFor("index.html"));
            assertEquals("../", LayoutEngine.urlPrefixFor("cards/install.html"));
            assertEquals("../../", LayoutEngine.urlPrefixFor("a/b/c.html"));
        }

        private Map<String, String> renderSiteWith(Path tempDir, Map<String, Object> vars) {
            LayoutEngine engine = new LayoutEngine();
            Path install = tempDir.resolve("setup").resolve("install.md");
            List<Card> cards = List.of(createCardAtPath("install", install));
            List<RenderContext> contexts = List.of(createMinimalContext());
            BookConfig book = new BookConfig(tempDir, "Test Book", List.of(), List.of(),
                    Map.of(), List.of(), null, null);
            return engine.renderSite(cards, contexts,
                    new RenderContext(book, List.of(), vars, null, "web", "A4"));
        }
    }

    @Nested
    @DisplayName("Site content assets")
    class SiteContentAssets {

        @Test
        void should_point_a_local_image_at_the_assets_copy_at_the_right_depth(@TempDir Path book)
                throws IOException {
            image(book, "diagrams/gc.png");

            Map<String, String> site = renderWith(book,
                    "<p><img src=\"diagrams/gc.png\" alt=\"GC\"></p>");

            // A card lives one directory down, the index at the root: the same
            // book-relative ref has to come out with different prefixes.
            assertTrue(site.get("cards/install.html")
                            .contains("src=\"../assets/diagrams/gc.png\""),
                    site.get("cards/install.html"));
            assertEquals(Set.of("diagrams/gc.png"), engine.siteContentAssets());
            assertTrue(engine.siteMissingAssets().isEmpty());
        }

        @Test
        void should_mirror_the_book_tree_rather_than_flattening_it(@TempDir Path book)
                throws IOException {
            // Two cards each with their own diagram.png is ordinary; flattening
            // to assets/<filename> would silently serve one for both.
            image(book, "ch1/diagram.png");
            image(book, "ch2/diagram.png");

            renderWith(book, "<p><img src=\"ch1/diagram.png\"><img src=\"ch2/diagram.png\"></p>");

            assertEquals(Set.of("ch1/diagram.png", "ch2/diagram.png"), engine.siteContentAssets());
        }

        @Test
        void should_leave_remote_and_inline_refs_alone(@TempDir Path book) {
            String html = "<p><img src=\"https://example.dev/x.png\">"
                    + "<img src=\"//cdn.example.dev/y.png\">"
                    + "<img src=\"data:image/gif;base64,R0lGOD\">"
                    + "<img src=\"/deploy-root/z.png\"></p>";

            String page = renderWith(book, html).get("cards/install.html");

            assertTrue(page.contains("src=\"https://example.dev/x.png\""), page);
            assertTrue(page.contains("src=\"//cdn.example.dev/y.png\""), page);
            assertTrue(page.contains("src=\"data:image/gif;base64,R0lGOD\""), page);
            assertTrue(page.contains("src=\"/deploy-root/z.png\""), page);
            assertTrue(engine.siteContentAssets().isEmpty());
        }

        @Test
        void should_leave_a_missing_image_as_the_author_wrote_it(@TempDir Path book) {
            // Rewriting it would point a broken image into assets/ and lose the
            // path the author actually typed — the one thing that identifies it.
            String page = renderWith(book, "<p><img src=\"diagrams/gone.png\"></p>")
                    .get("cards/install.html");

            assertTrue(page.contains("src=\"diagrams/gone.png\""), page);
            assertTrue(engine.siteContentAssets().isEmpty());
            assertEquals(Set.of("diagrams/gone.png"), engine.siteMissingAssets());
        }

        @Test
        void should_not_reach_into_an_escaped_example(@TempDir Path book) throws IOException {
            // A book documenting this very syntax shows `<img src="...">` inside
            // a fence, which is escaped text by now. A bare src= regex rewrote
            // the example; matching the tag cannot.
            image(book, "diagrams/gc.png");

            String page = renderWith(book,
                    "<pre><code>&lt;img src=\"diagrams/gc.png\"&gt;</code></pre>")
                    .get("cards/install.html");

            assertTrue(page.contains("&lt;img src=\"diagrams/gc.png\"&gt;"), page);
            assertTrue(engine.siteContentAssets().isEmpty());
        }

        @Test
        void should_not_re_prefix_a_build_managed_asset(@TempDir Path book) {
            // What the cover, back and watermark templates already emit.
            String page = renderWith(book, "<p><img src=\"../assets/logo.png\"></p>")
                    .get("cards/install.html");

            assertTrue(page.contains("src=\"../assets/logo.png\""), page);
            assertTrue(engine.siteContentAssets().isEmpty());
        }

        @Test
        void should_refuse_a_ref_that_climbs_out_of_the_book(@TempDir Path book) {
            String page = renderWith(book, "<p><img src=\"../../etc/secret.png\"></p>")
                    .get("cards/install.html");

            assertTrue(page.contains("src=\"../../etc/secret.png\""), page);
            assertTrue(engine.siteContentAssets().isEmpty());
            assertTrue(engine.siteMissingAssets().isEmpty());
        }

        @Test
        void should_report_only_what_the_latest_render_referenced(@TempDir Path book)
                throws IOException {
            image(book, "diagrams/gc.png");
            renderWith(book, "<p><img src=\"diagrams/gc.png\"></p>");
            assertEquals(Set.of("diagrams/gc.png"), engine.siteContentAssets());

            renderWith(book, "<p>no images here</p>");
            assertTrue(engine.siteContentAssets().isEmpty());
        }

        private LayoutEngine engine;

        /** Render a one-card site whose only block content is {@code html}. */
        private Map<String, String> renderWith(Path book, String html) {
            engine = new LayoutEngine();
            Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                    null, 0, html, List.of());
            Card card = new Card("install", book.resolve("setup").resolve("install.md"),
                    new Frontmatter(Map.of()), "Test Card", List.of(block));
            BookConfig cfg = new BookConfig(book, "Test Book", List.of(), List.of(),
                    Map.of(), List.of(), null, null);
            return engine.renderSite(List.of(card), List.of(createMinimalContext()),
                    new RenderContext(cfg, List.of(), Map.of(), null, "web", "A4"));
        }

        private void image(Path book, String rel) throws IOException {
            Path file = book.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[] {1, 2, 3});
        }
    }

    private Card createCardAtPath(String id, Path path) {
        Block block = new Block(
            Block.Kind.HEADING_SECTION,
            null,
            Set.of("intro"),
            null,
            0,
            "<p>Test content</p>",
            List.of()
        );
        Frontmatter fm = new Frontmatter(Map.of());
        return new Card(id, path, fm, "Test Card", List.of(block));
    }

    private RenderContext createMinimalContext() {
        BookConfig book = new BookConfig(null, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
    }

    private RenderContext createContextWithVars(Map<String, Object> vars) {
        BookConfig book = new BookConfig(null, "Test Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), vars, null, "pdf", "A4");
    }
}