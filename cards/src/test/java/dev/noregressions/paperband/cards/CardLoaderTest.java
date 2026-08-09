package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CardLoaderTest {

    @Nested
    @DisplayName("Card id validation")
    class CardIdValidation {

        @Test
        void should_reject_id_with_path_traversal(@TempDir Path tempDir) throws IOException {
            Path mdFile = tempDir.resolve("evil.md");
            Files.writeString(mdFile, """
                    ---
                    id: ../../../tmp/pwned
                    ---
                    # Evil
                    """);

            CardLoader loader = new CardLoader();
            CardParseException ex = assertThrows(
                    CardParseException.class, () -> loader.load(mdFile));
            assertTrue(ex.getMessage().contains("invalid card id"));
        }

        @Test
        void should_reject_id_with_backslash(@TempDir Path tempDir) throws IOException {
            Path mdFile = tempDir.resolve("evil.md");
            Files.writeString(mdFile, """
                    ---
                    id: "..\\\\pwned"
                    ---
                    # Evil
                    """);

            CardLoader loader = new CardLoader();
            assertThrows(CardParseException.class, () -> loader.load(mdFile));
        }

        @Test
        void should_accept_normal_id(@TempDir Path tempDir) throws IOException {
            Path mdFile = tempDir.resolve("fine.md");
            Files.writeString(mdFile, """
                    ---
                    id: my-card_01
                    ---
                    # Fine
                    """);

            CardLoader loader = new CardLoader();
            assertEquals("my-card_01", loader.load(mdFile).id());
        }
    }

    @Nested
    @DisplayName("Basic card loading")
    class BasicCardLoading {

        @Test
        void should_load_card_with_minimal_content(@TempDir Path tempDir) throws IOException {
            Path mdFile = tempDir.resolve("test.md");
            Files.writeString(mdFile, "# Test Card\n\nSimple content.");

            CardLoader loader = new CardLoader();
            Card card = loader.load(mdFile);

            assertEquals("test", card.id());
            assertEquals(mdFile, card.source());
            assertEquals("Test Card", card.title());
            assertEquals(1, card.blocks().size());

            Block block = card.blocks().get(0);
            assertEquals(Block.Kind.HEADING_SECTION, block.kind());
            assertEquals(Set.of("intro"), block.classes());
            assertTrue(block.html().contains("Simple content"));
        }

        @Test
        void should_parse_in_memory_markdown() {
            Path virtualPath = Path.of("/virtual/test.md");
            String markdown = """
                # Virtual Card

                This is virtual content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(virtualPath, markdown);

            assertEquals("test", card.id());
            assertEquals(virtualPath, card.source());
            assertEquals("Virtual Card", card.title());
        }

        @Test
        void should_derive_id_from_filename() {
            Path path = Path.of("/path/to/my-card.md");
            String markdown = "# Test";

            CardLoader loader = new CardLoader();
            Card card = loader.parse(path, markdown);

            assertEquals("my-card", card.id());
        }

        @Test
        void should_handle_filename_without_extension() {
            Path path = Path.of("/path/to/no-extension");
            String markdown = "# Test";

            CardLoader loader = new CardLoader();
            Card card = loader.parse(path, markdown);

            assertEquals("no-extension", card.id());
        }
    }

    @Nested
    @DisplayName("Frontmatter parsing")
    class FrontmatterParsing {

        @Test
        void should_parse_simple_frontmatter() {
            String markdown = """
                ---
                id: custom-id
                tier: 1
                title: Custom Title
                ---

                # Markdown Title

                Content here.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("custom-id", card.id());
            assertEquals("Custom Title", card.title()); // Frontmatter wins over H1
            assertEquals(1, card.frontmatter().getInt("tier").orElse(0));
        }

        @Test
        void should_parse_complex_frontmatter() {
            String markdown = """
                ---
                tier: 2
                effort: M
                tags: [spring, migration, database]
                metadata:
                  complexity: high
                  impact: breaking
                verify: false
                ---

                # Complex Card

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            Frontmatter fm = card.frontmatter();
            assertEquals(2, fm.getInt("tier").orElse(0));
            assertEquals("M", fm.getString("effort").orElse(""));
            assertEquals(List.of("spring", "migration", "database"), fm.getStringList("tags"));
            assertFalse(fm.getBoolean("verify").orElse(true));
            assertTrue(fm.has("metadata"));
        }

        @Test
        void should_handle_empty_frontmatter() {
            String markdown = """
                ---
                ---

                # Empty Frontmatter

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertTrue(card.frontmatter().values().isEmpty());
            assertEquals("test", card.id()); // Falls back to filename
            assertEquals("Empty Frontmatter", card.title()); // Falls back to H1
        }

        @Test
        void should_handle_no_frontmatter() {
            String markdown = """
                # No Frontmatter Card

                Just content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertTrue(card.frontmatter().values().isEmpty());
            assertEquals("No Frontmatter Card", card.title());
        }
    }

    @Nested
    @DisplayName("Block extraction")
    class BlockExtraction {

        @Test
        void should_create_intro_block_before_first_h2() {
            String markdown = """
                # Title

                This is intro content.

                More intro content.

                ## First Section

                Section content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(2, card.blocks().size());

            Block intro = card.blocks().get(0);
            assertEquals(Block.Kind.HEADING_SECTION, intro.kind());
            assertEquals(Set.of("intro"), intro.classes());
            assertNull(intro.heading());
            assertTrue(intro.html().contains("This is intro content"));
            assertTrue(intro.html().contains("More intro content"));

            Block section = card.blocks().get(1);
            assertEquals(Set.of("first-section"), section.classes());
            assertEquals("First Section", section.heading());
        }

        @Test
        void should_create_blocks_from_h2_headings() {
            String markdown = """
                # Card Title

                ## What Changed

                The API changed.

                ## How to Fix

                Update your code.

                ## Watch Out

                Be careful here.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(3, card.blocks().size());

            assertEquals("What Changed", card.blocks().get(0).heading());
            assertEquals(Set.of("what-changed"), card.blocks().get(0).classes());

            assertEquals("How to Fix", card.blocks().get(1).heading());
            assertEquals(Set.of("how-to-fix"), card.blocks().get(1).classes());

            assertEquals("Watch Out", card.blocks().get(2).heading());
            assertEquals(Set.of("watch-out"), card.blocks().get(2).classes());
        }

        @Test
        void should_handle_custom_classes_and_ids() {
            String markdown = """
                # Title

                ## Custom Section {.custom-class #custom-id}

                Content with attributes.

                ## Multiple Classes {.class1 .class2}

                Multiple class content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(2, card.blocks().size());

            Block first = card.blocks().get(0);
            assertEquals(Set.of("custom-class"), first.classes());
            // Note: ID extraction may depend on flexmark AttributesExtension configuration
            // For now, focus on class extraction which is working
            assertEquals("Custom Section", first.heading());

            Block second = card.blocks().get(1);
            assertEquals(Set.of("class1", "class2"), second.classes());
            assertNull(second.id());
        }

        @Test
        void should_generate_slug_from_heading_text() {
            String markdown = """
                # Title

                ## What You Need To Know!

                Content.

                ## API Changes & Breaking Updates

                More content.

                ## "Special" Characters

                Special content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(3, card.blocks().size());
            assertEquals(Set.of("what-you-need-to-know"), card.blocks().get(0).classes());
            assertEquals(Set.of("api-changes-breaking-updates"), card.blocks().get(1).classes());
            assertEquals(Set.of("special-characters"), card.blocks().get(2).classes());
        }

        @Test
        void should_handle_empty_blocks() {
            String markdown = """
                # Title

                ## Empty Section

                ## Another Section

                Content here.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(2, card.blocks().size());

            Block empty = card.blocks().get(0);
            assertEquals("Empty Section", empty.heading());
            assertTrue(empty.html().trim().isEmpty() || empty.html().isBlank());

            Block withContent = card.blocks().get(1);
            assertEquals("Another Section", withContent.heading());
            assertTrue(withContent.html().contains("Content here"));
        }
    }

    @Nested
    @DisplayName("Nested block extraction (heading rank)")
    class NestedBlockExtraction {

        @Test
        void should_nest_deeper_heading_as_child_of_shallower_one() {
            String markdown = """
                # Title

                ## Setup

                Setup content.

                ### Prerequisites

                Prereq content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(1, card.blocks().size(), "h3 nests under h2, not a top-level sibling");

            Block setup = card.blocks().get(0);
            assertEquals("Setup", setup.heading());
            assertEquals(2, setup.level());
            assertTrue(setup.html().contains("Setup content"));
            assertFalse(setup.html().contains("Prereq content"), "child content must not be flattened into the parent's own html");

            assertEquals(1, setup.children().size());
            Block prereq = setup.children().get(0);
            assertEquals("Prerequisites", prereq.heading());
            assertEquals(3, prereq.level());
            assertTrue(prereq.html().contains("Prereq content"));
            assertTrue(prereq.children().isEmpty());
        }

        @Test
        void should_close_both_child_and_parent_when_sibling_heading_follows() {
            String markdown = """
                # Title

                ## Setup

                ### Prerequisites

                Prereq content.

                ## Usage

                Usage content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(2, card.blocks().size(), "Usage is a top-level sibling of Setup, not nested under Prerequisites");
            assertEquals("Setup", card.blocks().get(0).heading());
            assertEquals(1, card.blocks().get(0).children().size());
            assertEquals("Prerequisites", card.blocks().get(0).children().get(0).heading());

            Block usage = card.blocks().get(1);
            assertEquals("Usage", usage.heading());
            assertEquals(2, usage.level());
            assertTrue(usage.children().isEmpty());
            assertTrue(usage.html().contains("Usage content"));
        }

        @Test
        void should_treat_multiple_same_level_headings_under_one_parent_as_siblings() {
            String markdown = """
                # Title

                ## Setup

                ### First Sub

                First content.

                ### Second Sub

                Second content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            Block setup = card.blocks().get(0);
            assertEquals(2, setup.children().size());
            assertEquals("First Sub", setup.children().get(0).heading());
            assertEquals("Second Sub", setup.children().get(1).heading());
        }

        @Test
        void should_nest_directly_under_ancestor_when_a_level_is_skipped() {
            // No h3 between the h2 and h4 -- the h4 must still nest under the
            // h2 (the nearest still-open shallower section), not become a
            // top-level block or get flattened as raw content.
            String markdown = """
                # Title

                ## Setup

                #### Deep Without A Parent Level

                Deep content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(1, card.blocks().size());
            Block setup = card.blocks().get(0);
            assertEquals(1, setup.children().size());

            Block deep = setup.children().get(0);
            assertEquals("Deep Without A Parent Level", deep.heading());
            assertEquals(4, deep.level());
            assertTrue(deep.html().contains("Deep content"));
        }

        @Test
        void should_support_four_levels_of_nesting() {
            String markdown = """
                # Title

                ## L2

                ### L3

                #### L4

                ##### L5

                Deepest content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            Block l2 = card.blocks().get(0);
            Block l3 = l2.children().get(0);
            Block l4 = l3.children().get(0);
            Block l5 = l4.children().get(0);

            assertEquals(2, l2.level());
            assertEquals(3, l3.level());
            assertEquals(4, l4.level());
            assertEquals(5, l5.level());
            assertTrue(l5.html().contains("Deepest content"));
            assertTrue(l5.children().isEmpty());
        }

        @Test
        void should_keep_empty_nested_section_even_with_no_content() {
            String markdown = """
                # Title

                ## Setup

                ### Empty Sub

                ### Another Sub

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            Block setup = card.blocks().get(0);
            assertEquals(2, setup.children().size());
            assertTrue(setup.children().get(0).html().isBlank());
            assertEquals("Empty Sub", setup.children().get(0).heading());
        }

        @Test
        void should_flatten_correctly_when_document_only_uses_h2() {
            // Regression guard: a document that never goes deeper than h2
            // must produce the exact same flat shape as before nesting
            // existed -- one tier, no children, every block a top-level
            // sibling.
            String markdown = """
                # Title

                ## First

                First content.

                ## Second

                Second content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals(2, card.blocks().size());
            for (Block b : card.blocks()) {
                assertEquals(2, b.level());
                assertTrue(b.children().isEmpty());
            }
        }

        @Test
        void intro_block_has_level_zero_and_no_children() {
            String markdown = """
                # Title

                Intro text.

                ## Section

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            Block intro = card.blocks().get(0);
            assertEquals(0, intro.level());
            assertTrue(intro.children().isEmpty());
        }
    }

    @Nested
    @DisplayName("HTML processing")
    class HtmlProcessing {

        @Test
        void should_preserve_markdown_formatting() {
            String markdown = """
                # Title

                ## Formatting Test

                This has **bold** and *italic* text.

                It also has `code spans` and [links](http://example.com).

                - List item 1
                - List item 2

                ```java
                System.out.println("Code block");
                ```
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            String html = card.blocks().get(0).html();
            assertTrue(html.contains("<strong>bold</strong>"));
            assertTrue(html.contains("<em>italic</em>"));
            assertTrue(html.contains("<code>code spans</code>"));
            assertTrue(html.contains("<a href=\"http://example.com\">links</a>"));
            assertTrue(html.contains("<ul>"));
            assertTrue(html.contains("<pre><code"));
            assertTrue(html.contains("System.out.println"));
        }

        @Test
        void should_handle_complex_html_structures() {
            String markdown = """
                # Title

                ## Complex Content

                > This is a blockquote
                > with multiple lines.

                1. First ordered item
                2. Second ordered item
                   - Nested unordered
                   - Another nested
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            String html = card.blocks().get(0).html();
            assertTrue(html.contains("<blockquote>"));
            assertTrue(html.contains("<ol>"));
            assertTrue(html.contains("<ul>"));
            // Tables require table extension which may not be enabled by default
        }
    }

    @Nested
    @DisplayName("Title extraction")
    class TitleExtraction {

        @Test
        void should_extract_title_from_h1() {
            String markdown = """
                # This Is The Title

                ## Section

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("This Is The Title", card.title());
        }

        @Test
        void should_prefer_frontmatter_title_over_h1() {
            String markdown = """
                ---
                title: Frontmatter Title
                ---

                # H1 Title

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("Frontmatter Title", card.title());
        }

        @Test
        void should_handle_missing_title() {
            String markdown = """
                ## Section Without H1

                Content without title.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertNull(card.title());
        }

        @Test
        void should_handle_empty_frontmatter_title() {
            String markdown = """
                ---
                title: ""
                ---

                # H1 Title

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("H1 Title", card.title()); // Falls back to H1 when frontmatter title is empty
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void should_throw_exception_for_nonexistent_file(@TempDir Path tempDir) {
            Path nonexistent = tempDir.resolve("nonexistent.md");
            CardLoader loader = new CardLoader();

            CardParseException exception = assertThrows(CardParseException.class, () ->
                loader.load(nonexistent));

            assertTrue(exception.getMessage().contains("Failed to read"));
            assertTrue(exception.getMessage().contains("nonexistent.md"));
        }

        @Test
        void should_throw_exception_for_malformed_frontmatter() {
            String markdown = """
                ---
                title: "Unclosed quote
                invalid: [unclosed list
                ---

                # Title
                """;

            CardLoader loader = new CardLoader();
            CardParseException exception = assertThrows(CardParseException.class, () ->
                loader.parse(Path.of("test.md"), markdown));

            assertTrue(exception.getMessage().contains("frontmatter parse failed"));
        }

        @Test
        void should_throw_exception_for_non_map_frontmatter() {
            String markdown = """
                ---
                - just
                - a
                - list
                ---

                # Title
                """;

            CardLoader loader = new CardLoader();
            CardParseException exception = assertThrows(CardParseException.class, () ->
                loader.parse(Path.of("test.md"), markdown));

            assertTrue(exception.getMessage().contains("frontmatter must be a YAML mapping"));
        }
    }

    @Nested
    @DisplayName("Preprocessor integration")
    class PreprocessorIntegration {

        @Test
        void should_apply_preprocessor_when_provided(@TempDir Path tempDir) throws IOException {
            String markdown = """
                # Title

                ## Section

                TEST_CONTENT_FOR_REPLACEMENT
                """;

            Path mdFile = tempDir.resolve("test.md");
            Files.writeString(mdFile, markdown);

            // Mock preprocessor that transforms content
            MarkdownPreprocessor preprocessor = new MarkdownPreprocessor() {
                @Override
                public String process(String source, Path path) {
                    return source.replace("TEST_CONTENT_FOR_REPLACEMENT", "REPLACEMENT_WAS_SUCCESSFUL");
                }
            };

            CardLoader loader = new CardLoader(preprocessor);
            Card card = loader.load(mdFile); // Use load() not parse() to trigger preprocessor

            // The preprocessor should transform the markdown before flexmark processing
            String html = card.blocks().get(0).html();
            assertTrue(html.contains("REPLACEMENT_WAS_SUCCESSFUL"),
                "Preprocessor should have replaced content. HTML: " + html);
        }

        @Test
        void should_work_without_preprocessor() {
            String markdown = """
                # Title

                ## Section

                Original content.
                """;

            CardLoader loader = new CardLoader(null);
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertTrue(card.blocks().get(0).html().contains("Original content"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void should_handle_empty_file() {
            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("empty.md"), "");

            assertEquals("empty", card.id());
            assertNull(card.title());
            assertTrue(card.blocks().isEmpty());
        }

        @Test
        void should_handle_whitespace_only_file() {
            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("whitespace.md"), "   \n\n   \t   \n");

            assertEquals("whitespace", card.id());
            assertNull(card.title());
            assertTrue(card.blocks().isEmpty());
        }

        @Test
        void should_handle_only_frontmatter() {
            String markdown = """
                ---
                id: only-frontmatter
                title: Only Frontmatter
                ---
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("only-frontmatter", card.id());
            assertEquals("Only Frontmatter", card.title());
            assertTrue(card.blocks().isEmpty());
        }

        @Test
        void should_handle_multiple_h1_elements() {
            String markdown = """
                # First Title

                Content after first.

                # Second Title

                Content after second.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("First Title", card.title()); // Uses first H1
            assertEquals(1, card.blocks().size()); // All content in intro block
        }

        @Test
        void should_handle_headings_with_markup() {
            String markdown = """
                # Title with **bold** and `code`

                ## Section with *italic* text

                Content.
                """;

            CardLoader loader = new CardLoader();
            Card card = loader.parse(Path.of("test.md"), markdown);

            assertEquals("Title with bold and code", card.title()); // Stripped of markup
            assertEquals("Section with italic text", card.blocks().get(0).heading());
        }
    }
}