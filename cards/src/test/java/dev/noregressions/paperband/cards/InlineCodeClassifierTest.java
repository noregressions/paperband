package dev.noregressions.paperband.cards;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InlineCodeClassifierTest {

    // ---- classify(String) per category --------------------------------------

    @Test
    void classify_annotations() {
        assertEquals("cls-annotation", InlineCodeClassifier.classify("@Bean"));
        assertEquals("cls-annotation", InlineCodeClassifier.classify("@MockBean"));
        assertEquals("cls-annotation", InlineCodeClassifier.classify("@AutoConfigureObservability"));
    }

    @Test
    void classify_keywordsAndLiterals() {
        assertEquals("cls-keyword", InlineCodeClassifier.classify("true"));
        assertEquals("cls-keyword", InlineCodeClassifier.classify("false"));
        assertEquals("cls-keyword", InlineCodeClassifier.classify("null"));
        assertEquals("cls-keyword", InlineCodeClassifier.classify("void"));
        assertEquals("cls-keyword", InlineCodeClassifier.classify("this"));
        assertEquals("cls-keyword", InlineCodeClassifier.classify("super"));
    }

    @Test
    void classify_constants() {
        assertEquals("cls-constant", InlineCodeClassifier.classify("STANDARD"));
        assertEquals("cls-constant", InlineCodeClassifier.classify("JAVA_LEGACY"));
        assertEquals("cls-constant", InlineCodeClassifier.classify("HTTP_1_1"));
    }

    @Test
    void classify_methods() {
        assertEquals("cls-method", InlineCodeClassifier.classify("merge()"));
        assertEquals("cls-method", InlineCodeClassifier.classify("setOrder()"));
        assertEquals("cls-method", InlineCodeClassifier.classify("doStuff(x, y)"));
    }

    @Test
    void classify_types() {
        assertEquals("cls-type", InlineCodeClassifier.classify("RestClient"));
        assertEquals("cls-type", InlineCodeClassifier.classify("HttpHeaders"));
        // Digits inside camel case allowed.
        assertEquals("cls-type", InlineCodeClassifier.classify("Jackson2ObjectMapperBuilderCustomizer"));
    }

    @Test
    void classify_paths() {
        assertEquals("cls-path", InlineCodeClassifier.classify("/actuator/health"));
        assertEquals("cls-path", InlineCodeClassifier.classify("/api/v1/users"));
    }

    @Test
    void classify_propertiesAndArtifacts() {
        assertEquals("cls-property", InlineCodeClassifier.classify("application.properties"));
        assertEquals("cls-property", InlineCodeClassifier.classify("application.yml"));
        assertEquals("cls-property", InlineCodeClassifier.classify("spring.session.store-type"));
        assertEquals("cls-property", InlineCodeClassifier.classify("spring.jackson.serialization.*"));
        // Maven artifact-id with hyphens.
        assertEquals("cls-property", InlineCodeClassifier.classify("spring-boot-starter-actuator"));
        assertEquals("cls-property", InlineCodeClassifier.classify("jackson2-compat-shim"));
    }

    // ---- classify(String) edge cases ----------------------------------------

    @Test
    void classify_nullAndEmpty() {
        assertNull(InlineCodeClassifier.classify(null));
        assertNull(InlineCodeClassifier.classify(""));
        assertNull(InlineCodeClassifier.classify("   "));
    }

    @Test
    void classify_plainIdentifierUnclassified() {
        // No distinguishing shape — should fall through to default styling.
        assertNull(InlineCodeClassifier.classify("id"));
        assertNull(InlineCodeClassifier.classify("name"));
        assertNull(InlineCodeClassifier.classify("type"));
    }

    @Test
    void classify_singleCharLowercaseUnclassified() {
        // Lone lowercase letter (Maven groupId fragment, generic placeholder, etc).
        assertNull(InlineCodeClassifier.classify("a"));
        assertNull(InlineCodeClassifier.classify("x"));
    }

    @Test
    void classify_singleUppercaseLetterIsNotType() {
        // P_TYPE requires at least 2 chars to avoid catching `T` / `U` generic params.
        assertNull(InlineCodeClassifier.classify("T"));
        assertNull(InlineCodeClassifier.classify("E"));
    }

    @Test
    void classify_methodReferenceUnclassified() {
        // `RestClient::create` has neither parens nor pure UpperCamelCase shape.
        // Falls through to default styling — acceptable.
        assertNull(InlineCodeClassifier.classify("RestClient::create"));
    }

    @Test
    void classify_genericTypeUnclassified() {
        // `List<String>` contains angle brackets — falls through.
        assertNull(InlineCodeClassifier.classify("List<String>"));
    }

    @Test
    void classify_priorityOrderAnnotationBeatsType() {
        // `@SomeType` matches both annotation and (after stripping @) type,
        // but annotation fires first.
        assertEquals("cls-annotation", InlineCodeClassifier.classify("@Inject"));
    }

    @Test
    void classify_priorityOrderKeywordBeatsConstant() {
        // `void` matches keyword set; if not handled first, P_CONSTANT regex
        // would NOT match it anyway (mixed case). But `NULL` would. Test the
        // keyword set covers the common literals before constant regex.
        assertEquals("cls-keyword", InlineCodeClassifier.classify("null"));
    }

    @Test
    void classify_constantPriorityForAllCaps() {
        // ALL_CAPS_SNAKE that isn't in keyword set → constant.
        assertEquals("cls-constant", InlineCodeClassifier.classify("STANDARD"));
    }

    // ---- process(Element) ---------------------------------------------------

    @Test
    void process_addsClassesToInlineCode() {
        Element body = parseFragment(
                "<p>Use <code>@Bean</code> on <code>RestClient</code> in " +
                "<code>application.properties</code> by calling <code>merge()</code> " +
                "for <code>/actuator/health</code> with <code>JAVA_LEGACY</code> if " +
                "value is <code>null</code>.</p>");

        InlineCodeClassifier.process(body);

        assertEquals("cls-annotation", classOf(body, "@Bean"));
        assertEquals("cls-type",       classOf(body, "RestClient"));
        assertEquals("cls-property",   classOf(body, "application.properties"));
        assertEquals("cls-method",     classOf(body, "merge()"));
        assertEquals("cls-path",       classOf(body, "/actuator/health"));
        assertEquals("cls-constant",   classOf(body, "JAVA_LEGACY"));
        assertEquals("cls-keyword",    classOf(body, "null"));
    }

    @Test
    void process_unclassifiedInlineCodeStaysClassless() {
        Element body = parseFragment("<p>See <code>RestClient::create</code>.</p>");
        InlineCodeClassifier.process(body);
        Element code = body.selectFirst("code");
        assertEquals("", code.className());
    }

    @Test
    void process_codeInsidePreIsUntouched() {
        // Prism handles block code — inline classifier must not stomp on it.
        Element body = parseFragment(
                "<pre><code class=\"language-java\">RestClient client = new RestClient();</code></pre>");
        InlineCodeClassifier.process(body);
        Element code = body.selectFirst("pre > code");
        assertEquals("language-java", code.className());
    }

    @Test
    void process_isIdempotent() {
        // Running twice should not double-add classes.
        Element body = parseFragment("<p><code>RestClient</code></p>");
        InlineCodeClassifier.process(body);
        InlineCodeClassifier.process(body);
        Element code = body.selectFirst("code");
        assertEquals("cls-type", code.className());
    }

    @Test
    void process_preservesExistingClasses() {
        // If a code element already has classes (e.g. attached via AttributesExtension
        // or a prior pass), the classifier adds to them rather than overwriting.
        Element body = parseFragment("<p><code class=\"existing-tag\">RestClient</code></p>");
        InlineCodeClassifier.process(body);
        Element code = body.selectFirst("code");
        assertTrue(code.hasClass("existing-tag"));
        assertTrue(code.hasClass("cls-type"));
    }

    @Test
    void process_skipsCodeAlreadyClassified() {
        // If an element already carries a cls-* class (manual override),
        // don't reclassify it.
        Element body = parseFragment("<p><code class=\"cls-keyword\">RestClient</code></p>");
        InlineCodeClassifier.process(body);
        Element code = body.selectFirst("code");
        assertEquals("cls-keyword", code.className(),
                "manual cls-* should win over heuristic");
    }

    // ---- helpers ------------------------------------------------------------

    private static Element parseFragment(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        return doc.body();
    }

    /** Find the first <code> whose text equals {@code expected}, return its className. */
    private static String classOf(Element body, String expected) {
        for (Element c : body.select("code")) {
            if (c.wholeText().equals(expected)) return c.className();
        }
        throw new AssertionError("No <code> with text: " + expected);
    }
}
