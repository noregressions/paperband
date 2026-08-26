package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Card;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content policy end to end through {@link CardLoader}: presentation
 * smuggled into content via raw HTML is stripped ({@code clean}, the
 * default), kept ({@code allow}), or fails the build ({@code strict}) — while
 * the sanctioned structural HTML and the {@code {.class}} attributes route
 * pass untouched.
 */
class ContentSanitizerTest {

    private final List<String> removals = new ArrayList<>();

    private Card parse(ContentPolicy policy, String markdown) {
        CardLoader loader = new CardLoader();
        loader.setContentPolicy(policy, removals::add);
        return loader.parse(Path.of("card.md"), markdown);
    }

    private static String html(Card card) {
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(b.html()));
        return sb.toString();
    }

    // ---- clean (the default) ----

    @Test
    void inlineStyleAttribute_isStripped_classSurvives() {
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                <p class="warning" style="color: red">careful</p>
                """);

        String html = html(card);
        assertFalse(html.contains("style="), html);
        assertTrue(html.contains("class=\"warning\""), "class is the sanctioned route and must survive");
        assertTrue(html.contains("careful"), "content itself is kept");
        assertTrue(removals.stream().anyMatch(r -> r.contains("card.md") && r.contains("style=")),
                "removal reported, naming the card: " + removals);
    }

    @Test
    void styleAndScriptBlocks_dropWithTheirContent() {
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                <style>p { color: red }</style>
                <script>alert(1)</script>

                prose
                """);

        String html = html(card);
        assertFalse(html.contains("<style"), html);
        assertFalse(html.contains("color: red"), "stylesheet text goes with its element");
        assertFalse(html.contains("alert"), html);
        assertTrue(html.contains("prose"));
    }

    @Test
    void headMetadataElements_areDropped() {
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                <link rel="stylesheet" href="evil.css">
                <meta name="x" content="y">

                prose
                """);

        String html = html(card);
        assertFalse(html.contains("<link"), html);
        assertFalse(html.contains("<meta"), html);
        assertTrue(html.contains("prose"));
    }

    @Test
    void fontAndCenter_unwrapKeepingTheirContent() {
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                <center><font color="red">still here</font></center>
                """);

        String html = html(card);
        assertFalse(html.contains("<font"), html);
        assertFalse(html.contains("<center"), html);
        assertTrue(html.contains("still here"), "unwrap keeps children");
    }

    @Test
    void presentationalAndEventAttributes_areStripped() {
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                <table border="1" cellpadding="4"><tr><td width="200"
                onclick="alert(1)" colspan="2">cell</td></tr></table>
                <p align="center">centered prose</p>
                """);

        String html = html(card);
        assertFalse(html.contains("border="), html);
        assertFalse(html.contains("align="), html);
        assertFalse(html.contains("width="), html);
        assertFalse(html.contains("onclick"), html);
        assertTrue(html.contains("colspan=\"2\""), "structural table attributes survive");
    }

    @Test
    void gfmTableAlignment_isMarkdownSemanticsAndSurvives() {
        // flexmark renders `---:` column syntax as align attributes on th/td;
        // stripping those would destroy alignment the author expressed in
        // pure markdown. Found in a real book's numeric tables.
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                | name | count |
                | --- | ---: |
                | a | 12 |
                """);

        String html = html(card);
        assertTrue(html.contains("align=\"right\""), "GFM column alignment survives: " + html);
        assertTrue(removals.isEmpty(), "and is not reported as a removal: " + removals);
    }

    @Test
    void attributesExtensionStyling_isStrippedLikeAnyOther() {
        // {.class} is sanctioned; {style=...} through the same extension is
        // still presentation and gets the same treatment — one rule,
        // whatever the route in.
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                a paragraph{style=color:red .keepme}
                """);

        String html = html(card);
        assertFalse(html.contains("style="), html);
        assertTrue(html.contains("keepme"), "the class from the same attribute list survives");
    }

    @Test
    void literalExamplesInFencedCode_areUntouched() {
        // Escaped text, not elements — the sanitizer never sees them.
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                ```html
                <div style="color: red">example</div>
                ```

                and inline `<span style="x">` too
                """);

        String html = html(card);
        assertTrue(html.contains("style"), "the literal example must survive verbatim");
        assertTrue(removals.isEmpty(), "and nothing is reported: " + removals);
    }

    @Test
    void cleanMarkdown_reportsNothing() {
        parse(ContentPolicy.CLEAN, """
                # T

                Plain **markdown** with a [link](x.md) and `code`.

                > Watch out {.warning}
                """);

        assertEquals(List.of(), removals);
    }

    // ---- allow ----

    @Test
    void allow_keepsPresentationVerbatim() {
        Card card = parse(ContentPolicy.ALLOW, """
                # T

                <p style="color: red">red</p>
                """);

        assertTrue(html(card).contains("style=\"color: red\""));
        assertTrue(removals.isEmpty());
    }

    // ---- strict ----

    @Test
    void strict_failsNamingTheCardAndTheFinding() {
        CardParseException e = assertThrows(CardParseException.class,
                () -> parse(ContentPolicy.STRICT, """
                        # T

                        <p style="color: red">red</p>
                        """));

        assertTrue(e.getMessage().contains("card.md"), e.getMessage());
        assertTrue(e.getMessage().contains("style="), e.getMessage());
        assertTrue(e.getMessage().contains("{.name}"),
                "the message points at the sanctioned route: " + e.getMessage());
    }

    @Test
    void strict_passesCleanContent() {
        Card card = parse(ContentPolicy.STRICT, """
                # T

                Plain markdown, one <kbd>Ctrl</kbd>+<kbd>C</kbd> and a <details><summary>more</summary>body</details>.
                """);

        String html = html(card);
        assertTrue(html.contains("<kbd>"), "structural HTML is not presentation");
        assertTrue(html.contains("<details>"), html);
    }

    // ---- policy parsing ----

    @Test
    void policyParse_acceptsTheThreeSpellings_andDefaultsToClean() {
        assertEquals(ContentPolicy.ALLOW, ContentPolicy.parse("allow"));
        assertEquals(ContentPolicy.CLEAN, ContentPolicy.parse("Clean"));
        assertEquals(ContentPolicy.STRICT, ContentPolicy.parse("STRICT"));
        assertEquals(ContentPolicy.CLEAN, ContentPolicy.parse(null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContentPolicy.parse("cleanse"));
        assertTrue(e.getMessage().contains("allow, clean or strict"), e.getMessage());
    }

    // ---- fence info-line attributes ----

    @Test
    void fenceInfoLineAttributes_landOnTheBlock_languageKept() {
        // ```text {.output} — the tag rides the opening fence instead of
        // dangling on a line after the closing one. Both spellings work.
        Card card = parse(ContentPolicy.CLEAN, """
                # T

                ```text {.output}
                [INFO] tool output here
                ```

                ```bash
                mvn --version
                ```
                {.command}
                """);

        String html = html(card);
        assertTrue(html.contains("class=\"output") || html.contains("output language-text")
                        || html.contains("language-text output"),
                "info-line class attaches to the block: " + html);
        assertTrue(html.contains("language-text"), "the language survives for Prism: " + html);
        assertTrue(html.contains("command"), "the trailing-line form still works: " + html);
    }
}
