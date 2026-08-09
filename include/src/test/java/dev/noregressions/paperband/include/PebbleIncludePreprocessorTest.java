package dev.noregressions.paperband.include;

import dev.noregressions.paperband.cards.MarkdownPreprocessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests of the {@code {% fragment %}} mechanism: real Pebble
 * parsing via {@link FragmentTokenParser}/{@link FragmentNode}/
 * {@link FragmentExtension}, wired together by
 * {@link PebbleIncludePreprocessor}, exercised through its public entry
 * point ({@link MarkdownPreprocessor#process}) with the real bundled
 * {@link FileContentProvider} and processors.
 *
 * <p>These stand in for the deleted {@code IncludePreprocessorTest} and
 * {@code EnhancedIncludePreprocessorTest} coverage of the old regex directive
 * — same behavioural contract (fragment resolution, masking, error surfacing),
 * new syntax and parsing layer. The masking-vs-real-call scenarios mirror the
 * actual bug history recorded in {@code SECURITY_TODO.md} and the real
 * collision in {@code guide/guide/02-authoring/03-includes.md}.
 */
class PebbleIncludePreprocessorTest {

    private final MarkdownPreprocessor preprocessor =
            Includes.defaultPreprocessor(null, Map.of(), Map.of());

    // ---- passthrough ----

    @Test
    void nullMarkdown_returnedAsIs() {
        assertNull(preprocessor.process(null, Path.of("card.md")));
    }

    @Test
    void emptyMarkdown_returnedAsIs() {
        assertEquals("", preprocessor.process("", Path.of("card.md")));
    }

    @Test
    void markdownWithNoFragmentTags_returnedUnchanged() {
        String md = "# Title\n\nJust prose, no directives here.\n";
        assertEquals(md, preprocessor.process(md, Path.of("card.md")));
    }

    // ---- real resolution ----

    @Test
    void wholeFileFragment_resolves(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "Foo.java", "public class Foo {}\n");

        String md = "# Card\n\n{% fragment \"Foo.java\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("```java"));
        assertTrue(out.contains("public class Foo {}"));
    }

    @Test
    void anchorFragment_resolves(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "pom.xml", """
                <project>
                <!-- ANCHOR: java-version -->
                <java.version>21</java.version>
                <!-- ANCHOR_END: java-version -->
                </project>
                """);

        String md = "{% fragment \"pom.xml:java-version\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("<java.version>21</java.version>"));
        assertFalse(out.contains("ANCHOR"));
    }

    @Test
    void lineRangeFragment_resolves(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "lines.txt", "one\ntwo\nthree\nfour\n");

        String md = "{% fragment \"lines.txt:2:3\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("two\nthree"));
        assertFalse(out.contains("one"));
        assertFalse(out.contains("four"));
    }

    @Test
    void asAttribute_overridesInferredProcessor(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        // A .md fragment defaults to the "markdown" processor, which splices the
        // content verbatim with no fence. Forcing "code" must actually change that
        // output shape, not just happen to look the same.
        writeFile(tmp, "snippet.md", "some *markdown* content\n");

        String defaultOut = preprocessor.process("{% fragment \"snippet.md\" %}\n", source);
        assertFalse(defaultOut.contains("```"), "default markdown processor should not fence");

        String forcedOut = preprocessor.process("{% fragment \"snippet.md\", as=\"code\" %}\n", source);
        assertTrue(forcedOut.contains("```"), "as=\"code\" override should force a fence");
        assertTrue(forcedOut.contains("some *markdown* content"));
    }

    @Test
    void langAttribute_forwardedToCodeProcessor(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "query.txt", "SELECT 1;\n");

        String md = "{% fragment \"query.txt\", as=\"code\", lang=\"sql\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("```sql"));
    }

    @Test
    void markerAttribute_forwardedToFileProvider(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "Foo.java", """
                // BEGIN: greeting
                String hi = "hi";
                // BEGIN_END: greeting
                """);

        String md = "{% fragment \"Foo.java:greeting\", marker=\"BEGIN\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("String hi = \"hi\";"));
    }

    @Test
    void multipleFragmentsInOneDocument_bothResolve(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "a.txt", "AAA\n");
        writeFile(tmp, "b.txt", "BBB\n");

        String md = "{% fragment \"a.txt\" %}\n\n{% fragment \"b.txt\" %}\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("AAA"));
        assertTrue(out.contains("BBB"));
    }

    // ---- masking: the real bug this mechanism exists to prevent ----

    @Test
    void literalExampleInFencedBlock_notEvaluated(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "pom.xml", """
                <!-- ANCHOR: v -->
                <version>1.0</version>
                <!-- ANCHOR_END: v -->
                """);

        String md = """
                ## Real
                {% fragment "pom.xml:v" %}

                ## Literal
                ```
                {% fragment "pom.xml:v" %}
                ```
                """;
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("<version>1.0</version>"), "real call should resolve");
        assertTrue(out.contains("{% fragment \"pom.xml:v\" %}"), "literal example should survive verbatim");
    }

    @Test
    void literalExampleInInlineCode_notEvaluated(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "a.txt", "AAA\n");

        String md = "{% fragment \"a.txt\" %}\n\nThe tag looks like `{% fragment \"path:tag\" %}` in prose.\n";
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("AAA"));
        assertTrue(out.contains("{% fragment \"path:tag\" %}"));
    }

    @Test
    void frontmatterLookingLikeATag_notEvaluated(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "a.txt", "AAA\n");

        String md = """
                ---
                oneliner: "Shows {% fragment \\"x\\" %} usage"
                ---
                {% fragment "a.txt" %}
                """;
        String out = preprocessor.process(md, source);

        assertTrue(out.contains("oneliner: \"Shows {% fragment \\\"x\\\" %} usage\""));
        assertTrue(out.contains("AAA"));
    }

    // ---- error surfacing ----

    @Test
    void blankReference_throwsIncludeException(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        String md = "{% fragment \"\" %}\n";

        IncludeException ex = assertThrows(IncludeException.class,
                () -> preprocessor.process(md, source));
        assertTrue(ex.getMessage().contains("must not be blank"));
    }

    @Test
    void unknownProviderScheme_throwsIncludeException(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        String md = "{% fragment \"git://samples:path\" %}\n";

        IncludeException ex = assertThrows(IncludeException.class,
                () -> preprocessor.process(md, source));
        assertTrue(ex.getMessage().contains("Unknown content provider 'git'"));
    }

    @Test
    void unknownReturnType_throwsIncludeException(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "a.txt", "AAA\n");
        String md = "{% fragment \"a.txt\", as=\"bogus\" %}\n";

        IncludeException ex = assertThrows(IncludeException.class,
                () -> preprocessor.process(md, source));
        assertTrue(ex.getMessage().contains("Unknown return type 'bogus'"));
    }

    @Test
    void missingFile_throwsIncludeExceptionWrappingResolutionFailure(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        String md = "{% fragment \"ghost.txt\" %}\n";

        IncludeException ex = assertThrows(IncludeException.class,
                () -> preprocessor.process(md, source));
        assertTrue(ex.getMessage().contains("Include failed"));
        assertNotNull(ex.getCause());
    }

    @Test
    void malformedPebbleSyntax_outsideMaskedRegion_throwsWithVerbatimHint(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        // Unmasked (not fenced, not inline code, not frontmatter) and not valid
        // Pebble -- this must fail loudly rather than being silently ignored the
        // way the old regex scanner would have left it alone.
        String md = "prose with a stray {% not a real tag %} in it\n";

        IncludeException ex = assertThrows(IncludeException.class,
                () -> preprocessor.process(md, source));
        assertTrue(ex.getMessage().toLowerCase().contains("verbatim")
                || ex.getMessage().contains("fenced code block"));
    }

    // ---- custom providers (scheme dispatch) ----

    @Test
    void schemePrefix_dispatchesToNamedProviderWithSchemeStripped(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");

        FakeProvider fake = new FakeProvider();
        MarkdownPreprocessor withFake = Includes.buildPreprocessor(
                List.of(new FileContentProvider(), fake),
                Includes.defaultProcessors(),
                null, Map.of(), Map.of());

        String md = "{% fragment \"git://samples:path/to/foo.java#tag\" %}\n";
        String out = withFake.process(md, source);

        assertEquals("samples:path/to/foo.java#tag", fake.lastReference);
        assertTrue(out.contains("FAKE-CONTENT"));
    }

    /** Minimal {@link ContentProvider} test double that records what it was asked to fetch. */
    private static final class FakeProvider implements ContentProvider {
        String lastReference;

        @Override
        public String name() {
            return "git";
        }

        @Override
        public Fragment fetch(String reference, IncludeContext ctx) {
            this.lastReference = reference;
            return Fragment.of("FAKE-CONTENT", "text/plain");
        }
    }

    // ---- vars + conditionals (whole-body pass merged with fragment resolution) ----
    //
    // Regression coverage for the bug found in the vars/conditionals feature
    // work: an earlier design ran fragment resolution and vars/conditionals as
    // two SEPARATE Pebble passes. The first pass (fragments only, no context
    // map) still parsed and evaluated any {{ }}/{% %} it found, including
    // {{ vars.x }} and {% if vars.x %} meant for the second pass — silently
    // resolving them to null/false before the real pass ever ran. The fix
    // merges both concerns into one masking pass + one Pebble evaluate call
    // with both extensions and the vars context registered together. These
    // tests exercise that merged behavior directly, especially the combined
    // fragment+conditional case that the old two-pass design got wrong.

    @Test
    void varInterpolation_substitutesRealValue(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("product_name", "Paperband"));

        String out = withVars.process("# {{ vars.product_name }}\n", source);

        assertTrue(out.contains("# Paperband"));
    }

    @Test
    void ifConditional_trueVar_keepsGuardedContent(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("show_advanced", true));

        String md = "{% if vars.show_advanced %}\nAdvanced section\n{% endif %}\n";
        String out = withVars.process(md, source);

        assertTrue(out.contains("Advanced section"));
    }

    @Test
    void ifConditional_falseVar_removesGuardedContent(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("show_advanced", false));

        String md = "{% if vars.show_advanced %}\nAdvanced section\n{% endif %}\n";
        String out = withVars.process(md, source);

        assertFalse(out.contains("Advanced section"));
    }

    @Test
    void ifConditional_undeclaredVar_treatedAsFalseWithoutThrowing(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        // No "never_declared" key at all -- must resolve leniently to null/false,
        // not throw AttributeNotFoundException, since vars is a LenientMap.
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("product_name", "Paperband"));

        String md = "{% if vars.never_declared %}\nShould never appear\n{% endif %}\n";
        String out = withVars.process(md, source);

        assertFalse(out.contains("Should never appear"));
    }

    @Test
    void literalVarsAndIfExamplesInFencedBlock_notEvaluated(@TempDir Path tmp) throws IOException {
        Path source = writeFile(tmp, "card.md", "unused");
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("product_name", "Paperband"));

        String md = """
                Real: {{ vars.product_name }}

                Literal example:
                ```
                {{ vars.product_name }}
                {% if vars.show_advanced %}...{% endif %}
                ```
                """;
        String out = withVars.process(md, source);

        assertTrue(out.contains("Real: Paperband"));
        assertTrue(out.contains("{{ vars.product_name }}"), "literal fenced example should survive verbatim");
        assertTrue(out.contains("{% if vars.show_advanced %}...{% endif %}"));
    }

    @Test
    void fragmentAndConditional_inSameDocument_bothResolveCorrectly(@TempDir Path tmp) throws IOException {
        // This is the exact scenario the old two-pass design got wrong: a real
        // {% fragment %} call and a real {% if vars.x %} conditional in the
        // same document. The fragment-only first pass (no vars context) used
        // to evaluate {% if vars.show_advanced %} prematurely against an
        // undefined root "vars" and silently drop the guarded content, even
        // though show_advanced was true.
        Path source = writeFile(tmp, "card.md", "unused");
        writeFile(tmp, "Foo.java", "public class Foo {}\n");
        MarkdownPreprocessor withVars = Includes.defaultPreprocessor(
                null, Map.of(), Map.of("product_name", "Paperband", "show_advanced", true));

        String md = """
                # {{ vars.product_name }}

                {% fragment "Foo.java" %}

                {% if vars.show_advanced %}
                Advanced section
                {% endif %}

                {% if vars.never_declared %}
                Should never appear
                {% endif %}
                """;
        String out = withVars.process(md, source);

        assertTrue(out.contains("# Paperband"));
        assertTrue(out.contains("public class Foo {}"));
        assertTrue(out.contains("Advanced section"));
        assertFalse(out.contains("Should never appear"));
    }

    // ---- helpers ----

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        return Files.writeString(p, content);
    }
}
