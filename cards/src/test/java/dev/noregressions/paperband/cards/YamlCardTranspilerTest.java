package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardSchema;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transpiles a spring-break-cheat-sheet-shaped YAML card and feeds the result
 * through the real {@link CardLoader}, asserting the whole chain: frontmatter
 * fidelity, section blocks with stable classes, and the diff-card /
 * error-output fences surviving into {@code DiffCardProcessor}'s structured
 * HTML.
 */
class YamlCardTranspilerTest {

    private static final Path SOURCE = Path.of("batch-package-moves.yaml");

    private static CardSchema schema() {
        return CardSchema.fromYaml(new org.yaml.snakeyaml.Yaml().load("""
                frontmatter: [id, tier, tier_label, title, series, effort, openrewrite]
                sections:
                  - field: oneliner
                  - field: error_output
                    heading: "Error"
                    fence: error-output
                  - field: what_changed
                    heading: "What Changed"
                  - field: why_changed
                    heading: "Why"
                  - field: diffs
                    heading: "Fix"
                    fence: diff-card
                  - field: fixes
                    heading: "How To Fix"
                  - field: watch_out
                    heading: "Watch Out"
                  - field: footer_links
                    heading: "Links"
                    class: footer-links
                """));
    }

    private static final String CARD_YAML = """
            id: batch-package-moves
            tier: 1
            tier_label: "Won't Build"
            title: "Spring Batch Core Package Relocations"
            series: "spring-boot 3.5 -> 4.0"
            effort: M
            openrewrite: false

            oneliner: >
              Core domain classes moved to a subpackage.

            error_output: |
              $ mvn compile
              [ERROR] package org.springframework.batch.core does not contain class Job

            what_changed: >
              Classes moved from <code>org.springframework.batch.core</code>
              to <code>org.springframework.batch.core.job</code>.

            why_changed: >
              Spring Batch 6.0 redesigned the domain model.

            diffs:
              - comment: "// Core domain imports"
                removed: |
                  import org.springframework.batch.core.Job;
                added: |
                  import org.springframework.batch.core.job.Job;

            fixes:
              - label: "Update imports."
                text: >
                  Add <code>.job</code> to the package path.

            watch_out:
              - >
                Not all classes moved. Verify each import.

            footer_links:
              - text: "Migration Guide"
                url: "https://example.com/guide"
            """;

    private static Card load() {
        String md = new YamlCardTranspiler().transpile(SOURCE, CARD_YAML, schema());
        return new CardLoader().parse(SOURCE, md);
    }

    private static Block find(Card card, String cssClass) {
        for (Block b : card.blocks()) {
            if (b.classes().contains(cssClass)) return b;
        }
        return null;
    }

    @Test
    void frontmatterFieldsSurviveWithTypes() {
        Card card = load();
        assertEquals("batch-package-moves", card.id());
        assertEquals("Spring Batch Core Package Relocations", card.title());
        assertEquals(1, card.frontmatter().getInt("tier").orElseThrow());
        assertEquals(false, card.frontmatter().getBoolean("openrewrite").orElseThrow());
        assertEquals("Won't Build", card.frontmatter().getString("tier_label").orElseThrow());
    }

    @Test
    void headinglessSectionBecomesIntro() {
        Card card = load();
        Block intro = find(card, "intro");
        assertNotNull(intro, "oneliner should land in the intro block");
        assertTrue(intro.html().contains("Core domain classes moved"));
    }

    @Test
    void sectionsCarryStableFieldDerivedClasses() {
        Card card = load();
        assertNotNull(find(card, "what-changed"), "what_changed → .what-changed");
        assertNotNull(find(card, "why-changed"),  "why_changed → .why-changed");
        assertNotNull(find(card, "footer-links"), "explicit class: wins");
    }

    @Test
    void errorOutputFenceReachesDiffCardProcessor() {
        Card card = load();
        Block error = find(card, "error-output");
        assertNotNull(error);
        assertTrue(error.html().contains("err-prompt"), "prompt line decorated");
        assertTrue(error.html().contains("err-error"), "[ERROR] line decorated");
    }

    @Test
    void diffsBecomeDiffCardFigures() {
        Card card = load();
        Block fix = find(card, "diffs");
        assertNotNull(fix);
        assertTrue(fix.html().contains("class=\"diff-card\""), "diff-card figure emitted");
        assertTrue(fix.html().contains("Core domain imports"), "comment becomes caption");
        assertTrue(fix.html().contains("org.springframework.batch.core.job.Job"));
    }

    @Test
    void fixesRenderLabelAndText() {
        Card card = load();
        Block fixes = find(card, "fixes");
        assertNotNull(fixes);
        assertTrue(fixes.html().contains("<strong>Update imports.</strong>"));
        assertTrue(fixes.html().contains("to the package path"));
    }

    @Test
    void watchOutBecomesBullets() {
        Card card = load();
        Block watchOut = find(card, "watch-out");
        assertNotNull(watchOut);
        assertTrue(watchOut.html().contains("<li>"), "list of strings → bullets");
    }

    @Test
    void footerLinksBecomeMarkdownLinks() {
        Card card = load();
        Block links = find(card, "footer-links");
        assertNotNull(links);
        assertTrue(links.html().contains("<a href=\"https://example.com/guide\">Migration Guide</a>"));
    }

    @Test
    void absentOptionalFieldsAreSkippedSilently() {
        String md = new YamlCardTranspiler().transpile(
                SOURCE, "id: tiny\ntitle: Tiny\noneliner: Just this.\n", schema());
        Card card = new CardLoader().parse(SOURCE, md);
        assertEquals("Tiny", card.title());
        assertEquals(1, card.blocks().size(), "only the intro block");
    }

    @Test
    void embeddedHtmlPassesThrough() {
        Card card = load();
        Block what = find(card, "what-changed");
        assertNotNull(what);
        assertTrue(what.html().contains("<code"), "inline <code> HTML preserved");
    }

    @Test
    void backticksInContentCannotEscapeTheFence() {
        String md = new YamlCardTranspiler().transpile(SOURCE, """
                id: sneaky
                title: Sneaky
                error_output: |
                  ```
                  not a real fence
                  ```
                """, schema());
        Card card = new CardLoader().parse(SOURCE, md);
        Block error = find(card, "error-output");
        assertNotNull(error);
        assertTrue(error.html().contains("not a real fence"));
        assertFalse(error.html().contains("<h2"), "content stayed inside the fence");
    }

    @Test
    void missingSchemaFailsWithHelpfulMessage() {
        CardParseException e = assertThrows(CardParseException.class,
                () -> new YamlCardTranspiler().transpile(SOURCE, CARD_YAML, null));
        assertTrue(e.getMessage().contains("cardSchema"));
    }

    @Test
    void nonMappingYamlFails() {
        assertThrows(CardParseException.class,
                () -> new YamlCardTranspiler().transpile(SOURCE, "- just\n- a list\n", schema()));
    }

    @Test
    void isYamlCardExcludesConfigFiles() {
        assertTrue(YamlCardTranspiler.isYamlCard(Path.of("cards/some-card.yaml")));
        assertTrue(YamlCardTranspiler.isYamlCard(Path.of("some-card.yml")));
        assertFalse(YamlCardTranspiler.isYamlCard(Path.of("cards/paperband.yaml")));
        assertFalse(YamlCardTranspiler.isYamlCard(Path.of("card.md")));
    }

    @Test
    void sectionOrderFollowsSchemaNotYaml() {
        // what_changed listed before error_output in the YAML, but the schema
        // orders error_output first — schema order must win.
        String md = new YamlCardTranspiler().transpile(SOURCE, """
                id: order
                title: Order
                what_changed: Something changed.
                error_output: |
                  $ boom
                """, schema());
        Card card = new CardLoader().parse(SOURCE, md);
        List<Block> blocks = card.blocks();
        int errorIdx = -1;
        int whatIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).classes().contains("error-output")) errorIdx = i;
            if (blocks.get(i).classes().contains("what-changed")) whatIdx = i;
        }
        assertTrue(errorIdx >= 0 && whatIdx >= 0);
        assertTrue(errorIdx < whatIdx, "schema declaration order wins");
    }
}
