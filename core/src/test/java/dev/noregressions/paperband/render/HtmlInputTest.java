package dev.noregressions.paperband.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class HtmlInputTest {

    private static final String VALID_HTML = "<html><body>Test</body></html>";
    private static final URI VALID_BASE_URI = URI.create("file:///tmp/test/");
    private static final PageSpec VALID_PAGE_SPEC = PageSpec.a4();
    private static final PdfMetadata VALID_METADATA = PdfMetadata.empty();

    @Nested
    @DisplayName("Valid construction")
    class ValidConstruction {

        @Test
        void should_create_html_input_with_all_valid_fields() {
            HtmlInput input = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals(VALID_HTML, input.html());
            assertEquals(VALID_BASE_URI, input.baseUri());
            assertEquals(VALID_PAGE_SPEC, input.pageSpec());
            assertEquals(VALID_METADATA, input.metadata());
        }

        @Test
        void should_handle_different_uri_schemes() {
            URI httpUri = URI.create("http://example.com/base/");
            HtmlInput input = new HtmlInput(VALID_HTML, httpUri, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals(httpUri, input.baseUri());
        }

        @Test
        void should_handle_complex_html_content() {
            String complexHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Complex Document</title>
                    <style>body { margin: 0; }</style>
                </head>
                <body>
                    <h1>Title with "quotes" and 'apostrophes'</h1>
                    <p>Content with &lt;entities&gt;</p>
                </body>
                </html>
                """;
            HtmlInput input = new HtmlInput(complexHtml, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals(complexHtml, input.html());
        }
    }

    @Nested
    @DisplayName("Validation failures")
    class ValidationFailures {

        @Test
        void should_throw_when_html_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new HtmlInput(null, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA));

            assertEquals("html", exception.getMessage());
        }

        @Test
        void should_throw_when_base_uri_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new HtmlInput(VALID_HTML, null, VALID_PAGE_SPEC, VALID_METADATA));

            assertEquals("baseUri", exception.getMessage());
        }

        @Test
        void should_throw_when_page_spec_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new HtmlInput(VALID_HTML, VALID_BASE_URI, null, VALID_METADATA));

            assertEquals("pageSpec", exception.getMessage());
        }

        @Test
        void should_throw_when_metadata_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, null));

            assertEquals("metadata", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void should_handle_empty_html_string() {
            HtmlInput input = new HtmlInput("", VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals("", input.html());
        }

        @Test
        void should_handle_uri_with_query_parameters() {
            URI uriWithQuery = URI.create("file:///path/to/base/?param=value");
            HtmlInput input = new HtmlInput(VALID_HTML, uriWithQuery, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals(uriWithQuery, input.baseUri());
        }

        @Test
        void should_handle_different_page_specs() {
            PageSpec letterSpec = PageSpec.letter();
            PageSpec bookletSpec = PageSpec.booklet6x9();

            HtmlInput letterInput = new HtmlInput(VALID_HTML, VALID_BASE_URI, letterSpec, VALID_METADATA);
            HtmlInput bookletInput = new HtmlInput(VALID_HTML, VALID_BASE_URI, bookletSpec, VALID_METADATA);

            assertEquals(letterSpec, letterInput.pageSpec());
            assertEquals(bookletSpec, bookletInput.pageSpec());
        }

        @Test
        void should_handle_metadata_with_content() {
            PdfMetadata richMetadata = new PdfMetadata(
                "Test Document",
                "Test Author",
                "Test Subject",
                java.util.List.of("test", "document", "pdf")
            );

            HtmlInput input = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, richMetadata);

            assertEquals(richMetadata, input.metadata());
        }
    }

    @Nested
    @DisplayName("Record behavior")
    class RecordBehavior {

        @Test
        void should_implement_equals_correctly() {
            HtmlInput input1 = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);
            HtmlInput input2 = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);

            assertEquals(input1, input2);
            assertEquals(input1.hashCode(), input2.hashCode());
        }

        @Test
        void should_implement_equals_correctly_with_different_values() {
            HtmlInput input1 = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);
            HtmlInput input2 = new HtmlInput("different", VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);

            assertNotEquals(input1, input2);
        }

        @Test
        void should_have_useful_to_string() {
            HtmlInput input = new HtmlInput(VALID_HTML, VALID_BASE_URI, VALID_PAGE_SPEC, VALID_METADATA);
            String toString = input.toString();

            assertTrue(toString.contains("HtmlInput"));
            assertTrue(toString.contains(VALID_HTML));
            assertTrue(toString.contains(VALID_BASE_URI.toString()));
        }
    }
}