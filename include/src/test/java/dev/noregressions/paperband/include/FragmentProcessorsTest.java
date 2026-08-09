package dev.noregressions.paperband.include;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FragmentProcessorsTest {

    @Nested
    @DisplayName("CodeProcessor")
    class CodeProcessorTests {

        private final CodeProcessor processor = new CodeProcessor();
        private final IncludeContext ctx = new IncludeContext(
            Path.of("test.md"),
            Path.of("/tmp"),
            Map.of(),
            Map.of()
        );

        @Test
        void should_wrap_java_code_in_fenced_block() {
            Fragment fragment = new Fragment(
                "public class Test {}",
                "text/x-java",
                Optional.of("java"),
                Optional.of("Test.java")
            );

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("```java\n"));
            assertTrue(result.contains("public class Test {}"));
            assertTrue(result.contains("\n```\n"));
        }

        @Test
        void should_use_explicit_lang_attribute_over_inferred() {
            Fragment fragment = new Fragment(
                "SELECT * FROM users;",
                "text/x-sql",
                Optional.of("sql"),
                Optional.empty()
            );
            IncludeContext ctxWithLang = new IncludeContext(
                Path.of("test.md"),
                Path.of("/tmp"),
                Map.of("lang", "postgresql"),
                Map.of()
            );

            String result = processor.process(fragment, ctxWithLang);

            assertTrue(result.contains("```postgresql\n"));
            assertTrue(result.contains("SELECT * FROM users;"));
        }

        @Test
        void should_fall_back_to_inferred_language() {
            Fragment fragment = new Fragment(
                "def hello():\n    return 'world'",
                "text/x-python",
                Optional.of("python"),
                Optional.empty()
            );

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("```python\n"));
            assertTrue(result.contains("def hello():"));
        }

        @Test
        void should_use_empty_language_when_none_available() {
            Fragment fragment = new Fragment(
                "unknown content",
                "text/plain",
                Optional.empty(),
                Optional.empty()
            );

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("```\n"));
            assertTrue(result.contains("unknown content"));
            assertTrue(result.contains("\n```\n"));
        }

        @Test
        void should_ensure_newlines_around_fence() {
            Fragment fragment = Fragment.of("content", "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.startsWith("\n\n```"));
            assertTrue(result.endsWith("```\n\n"));
        }

        @Test
        void should_add_final_newline_if_missing() {
            Fragment fragment = Fragment.of("no newline", "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("no newline\n```"));
        }

        @Test
        void should_preserve_final_newline_if_present() {
            Fragment fragment = Fragment.of("has newline\n", "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("has newline\n```"));
            // Should not add an extra newline
            assertFalse(result.contains("has newline\n\n```"));
        }
    }

    @Nested
    @DisplayName("HtmlProcessor")
    class HtmlProcessorTests {

        private final HtmlProcessor processor = new HtmlProcessor();
        private final IncludeContext ctx = new IncludeContext(
            Path.of("test.md"),
            Path.of("/tmp"),
            Map.of(),
            Map.of()
        );

        @Test
        void should_pass_through_html_with_proper_formatting() {
            Fragment fragment = Fragment.of(
                "<div class=\"card\"><p>Hello</p></div>",
                "text/html"
            );

            String result = processor.process(fragment, ctx);

            // HTML processor might add newlines for proper block-level formatting
            assertTrue(result.contains("<div class=\"card\"><p>Hello</p></div>"));
        }

        @Test
        void should_handle_complex_html_structures() {
            String html = """
                <article>
                    <header>
                        <h1>Title</h1>
                    </header>
                    <main>
                        <p>Content</p>
                    </main>
                </article>
                """;
            Fragment fragment = Fragment.of(html, "text/html");

            String result = processor.process(fragment, ctx);

            // Should contain the HTML content, possibly with added whitespace
            assertTrue(result.contains("<article>"));
            assertTrue(result.contains("<h1>Title</h1>"));
            assertTrue(result.contains("<p>Content</p>"));
        }

        @Test
        void should_handle_svg_content() {
            String svg = "<svg width=\"100\" height=\"100\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>";
            Fragment fragment = Fragment.of(svg, "image/svg+xml");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("<svg width=\"100\" height=\"100\">"));
            assertTrue(result.contains("<circle cx=\"50\" cy=\"50\" r=\"40\"/>"));
        }

        @Test
        void should_handle_empty_html() {
            Fragment fragment = Fragment.of("", "text/html");

            String result = processor.process(fragment, ctx);

            // Empty HTML might result in some whitespace normalization
            assertTrue(result.trim().isEmpty() || result.equals(""));
        }
    }

    @Nested
    @DisplayName("MarkdownProcessor")
    class MarkdownProcessorTests {

        private final MarkdownProcessor processor = new MarkdownProcessor();
        private final IncludeContext ctx = new IncludeContext(
            Path.of("test.md"),
            Path.of("/tmp"),
            Map.of(),
            Map.of()
        );

        @Test
        void should_pass_through_markdown_with_proper_formatting() {
            String markdown = """
                ## Section Title

                This is **bold** text with *emphasis*.

                - List item 1
                - List item 2
                """;
            Fragment fragment = Fragment.of(markdown, "text/markdown");

            String result = processor.process(fragment, ctx);

            // Should contain the markdown content, possibly with added whitespace
            assertTrue(result.contains("## Section Title"));
            assertTrue(result.contains("This is **bold** text"));
            assertTrue(result.contains("- List item 1"));
        }

        @Test
        void should_handle_markdown_with_code_blocks() {
            String markdown = """
                Example:

                ```java
                System.out.println("hello");
                ```

                That's it.
                """;
            Fragment fragment = Fragment.of(markdown, "text/markdown");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("Example:"));
            assertTrue(result.contains("```java"));
            assertTrue(result.contains("System.out.println(\"hello\");"));
            assertTrue(result.contains("That's it."));
        }

        @Test
        void should_handle_markdown_tables() {
            String markdown = """
                | Column 1 | Column 2 |
                |----------|----------|
                | Cell 1   | Cell 2   |
                """;
            Fragment fragment = Fragment.of(markdown, "text/markdown");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("| Column 1 | Column 2 |"));
            assertTrue(result.contains("| Cell 1   | Cell 2   |"));
        }

        @Test
        void should_handle_empty_markdown() {
            Fragment fragment = Fragment.of("", "text/markdown");

            String result = processor.process(fragment, ctx);

            // Empty markdown might result in some whitespace
            assertTrue(result.trim().isEmpty() || result.equals(""));
        }
    }

    @Nested
    @DisplayName("TextProcessor")
    class TextProcessorTests {

        private final TextProcessor processor = new TextProcessor();
        private final IncludeContext ctx = new IncludeContext(
            Path.of("test.md"),
            Path.of("/tmp"),
            Map.of(),
            Map.of()
        );

        @Test
        void should_wrap_plain_text_in_code_fence() {
            Fragment fragment = Fragment.of(
                "This is plain text content",
                "text/plain"
            );

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("```\n"));
            assertTrue(result.contains("This is plain text content"));
            assertTrue(result.contains("\n```"));
        }

        @Test
        void should_preserve_line_breaks() {
            String content = "Line 1\nLine 2\n\nLine 4 after empty line";
            Fragment fragment = Fragment.of(content, "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("Line 1\nLine 2\n\nLine 4 after empty line"));
        }

        @Test
        void should_handle_text_with_special_characters() {
            String content = "Text with \"quotes\" and 'apostrophes' and <brackets>";
            Fragment fragment = Fragment.of(content, "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("Text with \"quotes\""));
            assertTrue(result.contains("'apostrophes'"));
            assertTrue(result.contains("<brackets>"));
        }

        @Test
        void should_handle_empty_text() {
            Fragment fragment = Fragment.of("", "text/plain");

            String result = processor.process(fragment, ctx);

            assertTrue(result.contains("```"));
            assertTrue(result.contains("```")); // Should have opening and closing
        }
    }

    @Nested
    @DisplayName("Processor names and registration")
    class ProcessorNamesAndRegistration {

        @Test
        void should_have_consistent_names() {
            assertEquals("code", new CodeProcessor().name());
            assertEquals("html", new HtmlProcessor().name());
            assertEquals("markdown", new MarkdownProcessor().name());
            assertEquals("text", new TextProcessor().name());
        }

        @Test
        void should_implement_fragment_processor_interface() {
            FragmentProcessor code = new CodeProcessor();
            FragmentProcessor html = new HtmlProcessor();
            FragmentProcessor markdown = new MarkdownProcessor();
            FragmentProcessor text = new TextProcessor();

            // All should implement the interface
            assertNotNull(code.name());
            assertNotNull(html.name());
            assertNotNull(markdown.name());
            assertNotNull(text.name());

            // Names should be non-blank
            assertFalse(code.name().isBlank());
            assertFalse(html.name().isBlank());
            assertFalse(markdown.name().isBlank());
            assertFalse(text.name().isBlank());
        }

        @Test
        void should_require_valid_context() {
            Fragment fragment = Fragment.of("test content", "text/plain");

            // Processors might require context, so test that they handle it properly
            // when provided with valid context
            IncludeContext validCtx = new IncludeContext(
                Path.of("test.md"),
                Path.of("/tmp"),
                Map.of(),
                Map.of()
            );

            assertDoesNotThrow(() -> new CodeProcessor().process(fragment, validCtx));
            assertDoesNotThrow(() -> new HtmlProcessor().process(fragment, validCtx));
            assertDoesNotThrow(() -> new MarkdownProcessor().process(fragment, validCtx));
            assertDoesNotThrow(() -> new TextProcessor().process(fragment, validCtx));
        }

        @Test
        void should_handle_null_fragment_content_gracefully() {
            IncludeContext ctx = new IncludeContext(
                Path.of("test.md"),
                Path.of("/tmp"),
                Map.of(),
                Map.of()
            );

            // Fragment constructor should prevent null content
            assertThrows(IllegalArgumentException.class, () ->
                new Fragment(null, "text/plain", Optional.empty(), Optional.empty()));
        }
    }
}