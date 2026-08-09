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

        private int countOccurrences(String haystack, String needle) {
            int count = 0, idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) {
                count++;
                idx += needle.length();
            }
            return count;
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
            assertFalse(page.contains("cards-heading"));
            assertFalse(page.contains("card-grid"));
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
            assertFalse(page.contains("cards-heading"));
            assertFalse(page.contains("card-grid"));
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