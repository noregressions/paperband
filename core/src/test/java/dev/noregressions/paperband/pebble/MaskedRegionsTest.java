package dev.noregressions.paperband.pebble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests of {@link MaskedRegions#mask}, the logic that protects
 * frontmatter, fenced code blocks, and inline code spans from any Pebble
 * evaluation pass run over card markdown — {@code include}'s
 * {@code {% fragment %}} tag and {@code cards}' whole-body vars/
 * conditionals pass both depend on this exact behaviour.
 *
 * <p>This is the mechanism that fixed a real historical bug ("Include
 * preprocessor ignored code fences, broke the guide build") — moved here,
 * unchanged, when the logic was promoted out of {@code include} to be shared
 * by both modules.
 */
class MaskedRegionsTest {

    // ---- helpers ----

    /** Assert every character of the first occurrence of {@code needle} is masked. */
    private static void assertMasked(String markdown, String needle) {
        int start = markdown.indexOf(needle);
        assertTrue(start >= 0, "needle not found: " + needle);
        boolean[] mask = MaskedRegions.mask(markdown);
        for (int i = start; i < start + needle.length(); i++) {
            assertTrue(mask[i], "expected masked at index " + i + " (" + markdown.charAt(i) + ") in: " + needle);
        }
    }

    /** Assert no character of the first occurrence of {@code needle} is masked. */
    private static void assertUnmasked(String markdown, String needle) {
        int start = markdown.indexOf(needle);
        assertTrue(start >= 0, "needle not found: " + needle);
        boolean[] mask = MaskedRegions.mask(markdown);
        for (int i = start; i < start + needle.length(); i++) {
            assertFalse(mask[i], "expected unmasked at index " + i + " (" + markdown.charAt(i) + ") in: " + needle);
        }
    }

    // ---- frontmatter ----

    @Test
    void frontmatterBlockIsMasked() {
        String md = """
                ---
                title: "Uses {% fragment %} tags"
                ---
                # Body
                {% fragment "x" %}
                """;
        assertMasked(md, "title: \"Uses {% fragment %} tags\"");
        assertUnmasked(md, "{% fragment \"x\" %}");
    }

    @Test
    void noFrontmatter_bodyStartsUnmasked() {
        String md = "# Body\n{% fragment \"x\" %}\n";
        assertUnmasked(md, "{% fragment \"x\" %}");
    }

    @Test
    void unterminatedFrontmatterDelimiter_notTreatedAsFrontmatter() {
        // Opens with "---" but never closes -- CardLoader's own regex would also
        // fail to match this as frontmatter, so mask() must agree and leave it alone.
        String md = "---\ntitle: no closing delimiter\n{% fragment \"x\" %}\n";
        assertUnmasked(md, "{% fragment \"x\" %}");
    }

    @Test
    void frontmatterDelimiterMayHaveTrailingWhitespace() {
        String md = "---  \ntitle: x\n---   \nbody {% fragment \"x\" %}\n";
        assertUnmasked(md, "{% fragment \"x\" %}");
    }

    // ---- fenced code blocks ----

    @Test
    void backtickFence_wholeBlockMasked() {
        String md = """
                before
                ```
                {% fragment "path:anchor" %}
                ```
                after {% fragment "real" %}
                """;
        assertMasked(md, "{% fragment \"path:anchor\" %}");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void fenceWithLanguageInfoString_isMasked() {
        String md = """
                ```java
                // {% fragment "x" %}
                ```
                """;
        assertMasked(md, "{% fragment \"x\" %}");
    }

    @Test
    void tildeFence_isMasked() {
        String md = """
                ~~~
                {% fragment "x" %}
                ~~~
                {% fragment "real" %}
                """;
        assertMasked(md, "{% fragment \"x\" %}");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void fenceIndentedUpToThreeSpaces_isRecognized() {
        String md = "   ```\n   {% fragment \"x\" %}\n   ```\n{% fragment \"real\" %}\n";
        assertMasked(md, "{% fragment \"x\" %}");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void closingFenceMustBeSameOrLongerRun() {
        // A two-backtick "closer" doesn't close a three-backtick fence -- CommonMark
        // rule -- so the real fragment call four lines down must stay masked too.
        String md = """
                ```
                ``
                {% fragment "still inside" %}
                ```
                {% fragment "real" %}
                """;
        assertMasked(md, "{% fragment \"still inside\" %}");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void closingFenceLineMayOnlyHaveTrailingWhitespace() {
        // "``` extra" is not a valid closer per CommonMark, so the fence stays open
        // and content after it remains masked until a bare closing fence appears.
        String md = """
                ```
                one
                ``` extra
                two
                ```
                {% fragment "real" %}
                """;
        assertMasked(md, "two");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void unclosedFence_masksToEndOfDocument() {
        String md = "```\n{% fragment \"never closes\" %}\n";
        assertMasked(md, "{% fragment \"never closes\" %}");
    }

    @Test
    void textBeforeBackticks_doesNotOpenFence() {
        // "some prefix ```" does not start with the fence marker, so it's not a
        // fence opener -- this line is scanned for inline spans instead.
        String md = "some prefix ``` {% fragment \"real\" %}\nafter\n";
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    // ---- inline code spans ----

    @Test
    void singleBacktickSpan_isMasked() {
        String md = "The tag looks like `{% fragment \"path:anchor\" %}` in prose.\n";
        assertMasked(md, "{% fragment \"path:anchor\" %}");
    }

    @Test
    void doubleBacktickSpan_protectsInnerSingleBacktick() {
        String md = "Use `` `{% fragment \"x\" %}` `` to show a backtick-containing example.\n";
        assertMasked(md, "{% fragment \"x\" %}");
    }

    @Test
    void multipleInlineSpansOnSameLine_bothMasked() {
        String md = "First `{% fragment \"a\" %}` then `{% fragment \"b\" %}` then {% fragment \"real\" %}.\n";
        assertMasked(md, "{% fragment \"a\" %}");
        assertMasked(md, "{% fragment \"b\" %}");
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void unmatchedBacktickOnLine_treatedAsLiteralNotMasked() {
        // No closing backtick on the same line -- per MaskedRegions' own contract,
        // this is left as literal text rather than masked.
        String md = "stray ` backtick then {% fragment \"real\" %}\n";
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    @Test
    void inlineSpanDoesNotCrossLines() {
        // A backtick opened on one line must not pair with a backtick on the next
        // line: if it did, everything between them -- including the real fragment
        // call below -- would be wrongly masked.
        String md = "open ` {% fragment \"real\" %}\nclose ` after\n";
        assertUnmasked(md, "{% fragment \"real\" %}");
    }

    // ---- combined / realistic ----

    @Test
    void realisticDocument_onlyLiteralExamplesMasked() {
        // Mirrors guide/guide/02-authoring/03-includes.md's own structure:
        // frontmatter, a real fragment call, and several literal examples of the
        // same syntax shown in different masked contexts.
        String md = """
                ---
                title: "Includes"
                ---
                # Includes

                ## Real include

                {% fragment "pom.xml:java-version" %}

                ## Literal example in a fenced block

                ```
                {% fragment "pom.xml:java-version" %}
                ```

                ## Literal example inline

                The tag looks like `{% fragment "path:anchor" %}` in prose.
                """;
        boolean[] mask = MaskedRegions.mask(md);

        // Both occurrences of the real-looking reference string exist twice in the
        // fenced/inline examples and once as the real call; only the real one (the
        // first occurrence, under "## Real include") is unmasked.
        int realCall = md.indexOf("{% fragment \"pom.xml:java-version\" %}");
        assertFalse(mask[realCall], "the real call must remain unmasked");

        int fencedExample = md.indexOf("{% fragment \"pom.xml:java-version\" %}", realCall + 1);
        assertTrue(mask[fencedExample], "the fenced literal example must be masked");

        assertMasked(md, "{% fragment \"path:anchor\" %}");
        assertMasked(md, "title: \"Includes\"");
    }

    // ---- substitute/restore ----

    @Test
    void substitute_restoresMaskedTextVerbatim() {
        String md = "real {% x %}\n\n```\nliteral {% y %}\n```\n";
        MaskedRegions.Masked masked = MaskedRegions.substitute(md);

        assertFalse(masked.text().contains("{% y %}"), "masked region should be replaced with a sentinel");
        assertTrue(masked.text().contains("{% x %}"), "unmasked region should survive untouched");

        // Round-trip through a no-op "render" (identity) restores the original.
        assertEquals(md, masked.restore(masked.text()));
    }

    @Test
    void substitute_restoresCorrectly_withMoreThanTenMaskedRegions() {
        // Regression test: with a plain numeric suffix-less sentinel format
        // ("PWMASK" + index), "PWMASK1" is a literal substring of "PWMASK10"
        // through "PWMASK19" (and so on for every longer index sharing that
        // prefix). restore() replaces indexes one at a time with String.replace,
        // so replacing index 1 before index 10 has been replaced corrupts index
        // 10's still-unreplaced sentinel: "PWMASK10" -> "<original for 1>0".
        // Build 15 distinct single-backtick masked spans (>10, so this can bite)
        // on one line, each with a distinguishable original.
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            md.append("`span").append(i).append("` ");
        }
        String source = md.toString();

        MaskedRegions.Masked masked = MaskedRegions.substitute(source);
        // Identity "render" -- nothing should touch the sentinels in between.
        String restored = masked.restore(masked.text());

        assertEquals(source, restored);
        for (int i = 0; i < 15; i++) {
            assertTrue(restored.contains("`span" + i + "`"), "span" + i + " should survive round-trip verbatim");
        }
    }
}
