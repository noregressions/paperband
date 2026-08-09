package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slot-based structural templates: {@link SlotTracker} accounting plus the
 * post-render enforcement in {@link LayoutEngine} (single-card and book paths).
 */
class SlotPlacementTest {

    // ---- SlotTracker unit level ----

    @Nested
    @DisplayName("SlotTracker accounting")
    class TrackerAccounting {

        private Map<String, Object> block(String id, String heading, String... classes) {
            Map<String, Object> m = new HashMap<>();
            m.put("kind", "HEADING_SECTION");
            m.put("id", id);
            m.put("classes", new ArrayList<>(List.of(classes)));
            m.put("classAttr", String.join(" ", classes));
            m.put("heading", heading);
            m.put("level", 2);
            m.put("html", "<p>x</p>");
            m.put("children", List.of());
            return m;
        }

        @Test
        void take_matches_by_class_and_consumes() {
            SlotTracker t = new SlotTracker(List.of(
                    block(null, null, "intro"),
                    block(null, "Watch Out", "watch-out")));

            assertEquals(1, t.take("watch-out").size());
            assertEquals(0, t.take("watch-out").size(), "second take finds nothing left");
            assertEquals(1, t.unplaced().size(), "intro not yet consumed");
        }

        @Test
        void take_matches_by_explicit_block_id() {
            SlotTracker t = new SlotTracker(List.of(
                    block("wo-1", "Watch Out", "watch-out")));

            assertEquals(1, t.take("wo-1").size());
            assertTrue(t.unplaced().isEmpty());
        }

        @Test
        void first_take_wins_when_a_block_matches_two_slots() {
            SlotTracker t = new SlotTracker(List.of(
                    block(null, "Fix", "how-to-fix", "diffs")));

            assertEquals(1, t.take("diffs").size());
            assertEquals(0, t.take("how-to-fix").size());
        }

        @Test
        void take_accepts_a_list_of_alias_names() {
            SlotTracker t = new SlotTracker(List.of(
                    block(null, "Gotchas", "gotchas")));

            assertEquals(1, t.take(List.of("watch-out", "gotchas")).size());
        }

        @Test
        void rest_returns_leftovers_in_document_order_and_consumes() {
            SlotTracker t = new SlotTracker(List.of(
                    block(null, "A", "a"),
                    block(null, "B", "b"),
                    block(null, "C", "c")));
            t.take("b");

            List<Map<String, Object>> rest = t.rest();

            assertEquals(2, rest.size());
            assertEquals("A", rest.get(0).get("heading"));
            assertEquals("C", rest.get(1).get("heading"));
            assertTrue(t.unplaced().isEmpty());
        }

        @Test
        void has_peeks_without_consuming() {
            SlotTracker t = new SlotTracker(List.of(block(null, "Diffs", "diffs")));

            assertTrue(t.has("diffs"));
            assertEquals(1, t.unplaced().size(), "has() must not consume");
            assertFalse(t.has("watch-out"));
        }

        @Test
        void require_records_a_missing_slot() {
            SlotTracker t = new SlotTracker(List.of(block(null, null, "intro")));
            t.take("intro");
            t.require("check");

            assertEquals(List.of("check"), t.missingRequired());
            assertTrue(t.unplaced().isEmpty());
        }

        @Test
        void untouched_tracker_reports_not_used() {
            SlotTracker t = new SlotTracker(List.of(block(null, null, "intro")));

            assertFalse(t.used());
        }

        @Test
        void blank_or_empty_name_is_rejected() {
            SlotTracker t = new SlotTracker(List.of());

            assertThrows(IllegalArgumentException.class, () -> t.take(""));
            assertThrows(IllegalArgumentException.class, () -> t.take(List.of()));
        }
    }

    // ---- Engine level ----

    @Nested
    @DisplayName("Engine enforcement")
    class EngineEnforcement {

        private Block hblock(String id, String heading, String cls) {
            return new Block(Block.Kind.HEADING_SECTION, id, Set.of(cls),
                    heading, 2, "<p>" + cls + "-content</p>", List.of());
        }

        private Block intro() {
            return new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                    null, 0, "<p>intro-content</p>", List.of());
        }

        private Card card(String id, Block... blocks) {
            return new Card(id, Path.of(id + ".md"), new Frontmatter(Map.of()),
                    "Test Card", List.of(blocks));
        }

        private RenderContext minimalCtx() {
            BookConfig book = new BookConfig(null, "Test Book",
                    List.of(), List.of(), Map.of(), List.of(), null, null);
            return new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
        }

        private LayoutEngine engineWith(Path tempDir, String templateName, String body)
                throws IOException {
            Path layouts = Files.createDirectories(tempDir.resolve("layouts"));
            Files.writeString(layouts.resolve(templateName + ".html"), body);
            return new LayoutEngine(tempDir);
        }

        @Test
        void slotted_template_places_blocks_in_template_order(@TempDir Path tempDir)
                throws IOException {
            LayoutEngine engine = engineWith(tempDir, "slotted", """
                <html><body>
                {% for b in card.slots.take('check') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                </body></html>
                """);
            Card c = card("t", intro(), hblock(null, "Check", "check"));

            String html = engine.render(c, minimalCtx(), "slotted");

            assertTrue(html.indexOf("check-content") < html.indexOf("intro-content"),
                    "template order wins over document order");
        }

        @Test
        void unplaced_block_fails_the_render(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = engineWith(tempDir, "slotted", """
                <html><body>
                {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                </body></html>
                """);
            Card c = card("gc-tuning", intro(), hblock(null, "Rollback", "rollback"));

            SlotPlacementException e = assertThrows(SlotPlacementException.class,
                    () -> engine.render(c, minimalCtx(), "slotted"));

            assertTrue(e.getMessage().contains("gc-tuning"));
            assertTrue(e.getMessage().contains("Rollback"));
            assertTrue(e.getMessage().contains("rollback"));
        }

        @Test
        void rest_catch_all_rescues_unexpected_blocks(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = engineWith(tempDir, "slotted", """
                <html><body>
                {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                {% for b in card.slots.rest() %}{% include "_block-section" with {"block": b} %}{% endfor %}
                </body></html>
                """);
            Card c = card("t", intro(), hblock(null, "Rollback", "rollback"));

            String html = assertDoesNotThrow(() -> engine.render(c, minimalCtx(), "slotted"));

            assertTrue(html.contains("rollback-content"));
        }

        @Test
        void missing_required_slot_fails_the_render(@TempDir Path tempDir) throws IOException {
            LayoutEngine engine = engineWith(tempDir, "slotted", """
                <html><body>
                {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                {% for b in card.slots.require('check') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                </body></html>
                """);
            Card c = card("no-check", intro());

            SlotPlacementException e = assertThrows(SlotPlacementException.class,
                    () -> engine.render(c, minimalCtx(), "slotted"));

            assertTrue(e.getMessage().contains("missing required slot: check"));
        }

        @Test
        void has_branches_layout_without_affecting_accounting(@TempDir Path tempDir)
                throws IOException {
            LayoutEngine engine = engineWith(tempDir, "slotted", """
                <html><body>
                {% if card.slots.has('diffs') %}<div class="fix-first">DIFFS-LAYOUT</div>{% endif %}
                {% for b in card.slots.rest() %}{% include "_block-section" with {"block": b} %}{% endfor %}
                </body></html>
                """);
            Card withDiffs = card("a", intro(), hblock(null, "The Fix", "diffs"));
            Card without = card("b", intro());

            String htmlA = engine.render(withDiffs, minimalCtx(), "slotted");
            String htmlB = engine.render(without, minimalCtx(), "slotted");

            assertTrue(htmlA.contains("DIFFS-LAYOUT"));
            assertFalse(htmlB.contains("DIFFS-LAYOUT"));
        }

        @Test
        void default_looping_template_is_never_checked(@TempDir Path tempDir) {
            LayoutEngine engine = new LayoutEngine(tempDir);
            Card c = card("t", intro(), hblock(null, "Anything", "anything"));

            assertDoesNotThrow(() -> engine.render(c, minimalCtx()));
        }

        @Test
        void book_path_aggregates_failures_across_cards(@TempDir Path tempDir)
                throws IOException {
            LayoutEngine engine = engineWith(tempDir, "book-slotted", """
                <html><body>
                {% for card in cards %}
                {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
                {% endfor %}
                </body></html>
                """);
            List<Card> cards = List.of(
                    card("one", intro(), hblock(null, "Extra One", "extra-one")),
                    card("two", intro(), hblock(null, "Extra Two", "extra-two")));
            List<RenderContext> contexts = List.of(minimalCtx(), minimalCtx());
            BookConfig book = new BookConfig(tempDir, "Test Book",
                    List.of(), List.of(), Map.of(), List.of(), null, null);
            RenderContext bookCtx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");

            SlotPlacementException e = assertThrows(SlotPlacementException.class,
                    () -> engine.renderBook(cards, contexts, bookCtx, "book-slotted"));

            assertTrue(e.getMessage().contains("card 'one'"));
            assertTrue(e.getMessage().contains("card 'two'"));
            assertTrue(e.getMessage().contains("extra-one"));
            assertTrue(e.getMessage().contains("extra-two"));
        }
    }
}
