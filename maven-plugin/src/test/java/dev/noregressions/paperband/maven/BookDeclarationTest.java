package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.PageMatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The POM as the book's declaration: structure and config in XML, content in
 * markdown, appearance in CSS.
 */
class BookDeclarationTest {

    /** Set a private field the way Maven's configurator would. */
    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no such field: " + field, e);
        }
    }

    private static PageMatterConfig pageMatter(String image, String template) {
        PageMatterConfig p = new PageMatterConfig();
        if (image != null) set(p, "image", image);
        if (template != null) set(p, "template", template);
        return p;
    }

    @Nested
    @DisplayName("Book-level config declared in the POM")
    class DeclaredBookConfig {

        @Test
        void should_layer_over_the_yaml_config_field_by_field(@TempDir Path root) {
            BookConfig base = new BookConfig(root, "Yaml Title", List.of(), List.of(),
                    Map.of("author", "Yaml Author", "series", "Yaml Series"), List.of(),
                    "editorial", null, null, null, null, null, null, List.of());

            BookLayout book = new BookLayout();
            set(book, "title", "POM Title");
            set(book, "vars", new java.util.LinkedHashMap<>(Map.of("author", "POM Author")));

            BookConfig merged = book.mergeInto(base, root);

            assertEquals("POM Title", merged.title(), "a declaration wins over the yaml");
            assertEquals("POM Author", merged.vars().get("author"), "declared vars win per key");
            assertEquals("Yaml Series", merged.vars().get("series"),
                    "and leave the keys they don't mention alone");
            assertEquals("editorial", merged.theme(),
                    "fields the POM says nothing about are untouched");
        }

        @Test
        void should_declare_pages_as_an_image_or_a_template(@TempDir Path root) throws java.io.IOException {
            java.nio.file.Files.createDirectories(root.resolve("covers"));
            java.nio.file.Files.writeString(root.resolve("covers/front.png"), "");
            BookLayout book = new BookLayout();
            set(book, "cover", pageMatter("covers/front.png", null));
            set(book, "footer", pageMatter(null, "layouts/footer.html"));

            BookConfig merged = book.mergeInto(BookConfig.empty(root), root);

            assertEquals("covers/front.png", merged.cover().image());
            assertNull(merged.cover().template());
            assertEquals("footer", merged.footer().template(),
                    "a template path resolves to the bare loader name, as a yaml one does");
            assertNull(merged.back(), "an undeclared page stays absent");
        }

        @Test
        void should_reject_a_page_declaring_neither_image_nor_template(@TempDir Path root) {
            BookLayout book = new BookLayout();
            set(book, "cover", pageMatter(null, null));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> book.mergeInto(BookConfig.empty(root), root));
            assertTrue(e.getMessage().contains("cover"), "the message names the empty page");
        }

        @Test
        void should_resolve_a_section_landing_preset_by_name(@TempDir Path root) {
            BookLayout book = new BookLayout();
            set(book, "sectionLandingTemplate", "minimal");

            assertEquals("site-section-minimal",
                    book.mergeInto(BookConfig.empty(root), root).sectionLandingTemplate());
        }

        @Test
        void should_report_whether_it_declares_anything_at_all(@TempDir Path root) {
            BookLayout empty = new BookLayout();
            assertFalse(empty.declaresBookConfig(), "a <book> with only patterns declares no config");

            BookLayout titled = new BookLayout();
            set(titled, "title", "Runbook");
            assertTrue(titled.declaresBookConfig());
        }

        @Test
        void should_separate_declaring_config_from_selecting_cards() {
            BookLayout configOnly = new BookLayout();
            set(configOnly, "title", "Runbook");
            assertFalse(configOnly.declaresCardSelection(),
                    "config without patterns means: walk the root for cards");

            BookLayout withSections = new BookLayout();
            SectionConfig section = new SectionConfig();
            set(section, "title", "Setup");
            set(section, "includes", List.of("setup/**/*.md"));
            set(withSections, "sections", List.of(section));
            assertTrue(withSections.declaresCardSelection());
        }
    }

    @Nested
    @DisplayName("Authorship")
    class Authors {

        @Test
        void should_take_a_single_author(@TempDir Path root) {
            BookLayout book = new BookLayout();
            set(book, "author", "Ada Lovelace");

            assertEquals("Ada Lovelace", book.declaredVars().get("author"));
            assertEquals(List.of("Ada Lovelace"), book.declaredVars().get("authors"));
        }

        @Test
        void should_render_several_authors_for_templates_that_know_only_one() {
            // A theme written against `book.author` is every theme, so the list
            // has to arrive as something readable there too.
            BookLayout book = new BookLayout();
            set(book, "authors", List.of("Ada Lovelace", "Grace Hopper"));
            assertEquals("Ada Lovelace and Grace Hopper", book.declaredVars().get("author"));

            BookLayout three = new BookLayout();
            set(three, "authors", List.of("A", "B", "C"));
            assertEquals("A, B and C", three.declaredVars().get("author"));

            assertEquals(List.of("Ada Lovelace", "Grace Hopper"), book.declaredVars().get("authors"),
                    "and as a list, for a theme that wants to lay them out itself");
        }

        @Test
        void should_reject_declaring_authorship_two_ways() {
            BookLayout book = new BookLayout();
            set(book, "author", "Solo");
            set(book, "authors", List.of("Ada Lovelace", "Grace Hopper"));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, book::validate);
            assertTrue(e.getMessage().contains("<author>") && e.getMessage().contains("<authors>"),
                    e.getMessage());
        }

        @Test
        void should_ignore_blank_entries(@TempDir Path root) {
            BookLayout book = new BookLayout();
            set(book, "authors", Arrays.asList("Ada Lovelace", "  ", null));

            assertEquals(List.of("Ada Lovelace"), book.declaredVars().get("authors"));
        }

        @Test
        void should_reject_fullPage_on_anything_but_the_cover(@TempDir Path root) throws java.io.IOException {
            java.nio.file.Files.writeString(root.resolve("x.png"), "");

            PageMatterConfig back = pageMatter("x.png", null);
            set(back, "fullPage", Boolean.TRUE);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> back.toPageMatter(root, "back"));
            assertTrue(e.getMessage().contains("fullPage"), e.getMessage());

            PageMatterConfig cover = pageMatter("x.png", null);
            set(cover, "fullPage", Boolean.TRUE);
            assertTrue(cover.toPageMatter(root, "cover").fullPage());
        }

        @Test
        void should_reject_an_image_that_is_not_on_disk(@TempDir Path root) {
            // A missing image would otherwise render as a silent blank in the
            // PDF — Chromium doesn't fail on a dead file: URI.
            PageMatterConfig cover = pageMatter("covers/frnt.png", null);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> cover.toPageMatter(root, "cover"));
            assertTrue(e.getMessage().contains("frnt.png"), e.getMessage());
        }

        @Test
        void should_pass_index_modes_through_vars_and_reject_junk() {
            BookLayout book = new BookLayout();
            set(book, "index", "auto");
            assertEquals("auto", book.declaredVars().get("index"));

            BookLayout bad = new BookLayout();
            set(bad, "index", "yes please");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, bad::validate);
            assertTrue(e.getMessage().contains("<index>"), e.getMessage());
        }

        @Test
        void should_leave_vars_alone_when_no_author_is_declared() {
            BookLayout book = new BookLayout();
            set(book, "vars", new java.util.LinkedHashMap<>(Map.of("author", "From Vars")));

            assertEquals("From Vars", book.declaredVars().get("author"),
                    "an author set the old way still reaches the cover");
        }
    }

    @Nested
    @DisplayName("Repeated singular elements in the raw configuration")
    class RepeatedSingularElements {

        /** A goal with the given raw {@code <configuration>} DOM. */
        private AbstractPaperbandMojo mojoWith(Xpp3Dom configuration) {
            AbstractPaperbandMojo mojo = new AbstractPaperbandMojo() {
                @Override
                public void execute() {}
            };
            mojo.mojoExecution = new MojoExecution(new MojoDescriptor(), configuration);
            return mojo;
        }

        private Xpp3Dom book(String... childrenXml) {
            Xpp3Dom config = new Xpp3Dom("configuration");
            Xpp3Dom book = new Xpp3Dom("book");
            config.addChild(book);
            for (int i = 0; i + 1 < childrenXml.length; i += 2) {
                Xpp3Dom child = new Xpp3Dom(childrenXml[i]);
                child.setValue(childrenXml[i + 1]);
                book.addChild(child);
            }
            return config;
        }

        @Test
        void should_reject_two_top_level_author_elements() {
            // Maven maps both onto one String field and keeps the last, so
            // without this check the first author silently vanishes.
            AbstractPaperbandMojo mojo = mojoWith(
                    book("author", "Ada Lovelace", "author", "Grace Hopper"));

            MojoExecutionException e = assertThrows(MojoExecutionException.class,
                    () -> mojo.checkBookDeclaration(null));
            assertTrue(e.getMessage().contains("<author>"), e.getMessage());
            assertTrue(e.getMessage().contains("<authors>"),
                    "should point at the plural form: " + e.getMessage());
        }

        @Test
        void should_accept_a_single_author() throws Exception {
            mojoWith(book("author", "Ada Lovelace")).checkBookDeclaration(null);
        }

        @Test
        void should_leave_collection_elements_free_to_repeat() throws Exception {
            AbstractPaperbandMojo mojo = mojoWith(book("include", "a/**", "include", "b/**"));
            mojo.checkBookDeclaration(null);
        }

        @Test
        void should_reject_other_repeated_singular_elements_too() {
            AbstractPaperbandMojo mojo = mojoWith(book("title", "One", "title", "Two"));

            MojoExecutionException e = assertThrows(MojoExecutionException.class,
                    () -> mojo.checkBookDeclaration(null));
            assertTrue(e.getMessage().contains("<title>"), e.getMessage());
        }

        @Test
        void should_pass_a_goal_with_no_book_element() throws Exception {
            mojoWith(new Xpp3Dom("configuration")).checkBookDeclaration(null);
        }
    }

    @Nested
    @DisplayName("Axes declared in the POM")
    class DeclaredAxes {

        private AxisValueConfig value(String id, String label, String color) {
            AxisValueConfig v = new AxisValueConfig();
            set(v, "id", id);
            if (label != null) set(v, "label", label);
            if (color != null) set(v, "color", color);
            return v;
        }

        private AxisConfig axis(String name, String title, List<AxisValueConfig> values) {
            AxisConfig a = new AxisConfig();
            if (name != null) set(a, "name", name);
            if (title != null) set(a, "title", title);
            set(a, "values", values);
            return a;
        }

        @Test
        void should_carry_values_in_declaration_order_with_colour_as_meta(@TempDir Path root) {
            AxisConfig tier = axis("tier", "Tier", List.of(
                    value("1", "Critical", "#c0392b"),
                    value("2", "Standard", null)));

            BookLayout book = new BookLayout();
            set(book, "axes", List.of(tier));
            BookConfig merged = book.mergeInto(BookConfig.empty(root), root);

            assertEquals(1, merged.axes().size());
            Axis axis = merged.axes().get(0);
            assertEquals("tier", axis.name());
            assertEquals("Tier", axis.title());
            assertEquals(List.of("1", "2"), axis.values().stream().map(v -> v.id().toString()).toList(),
                    "declaration order is divider order");
            assertEquals("Critical", axis.values().get(0).label());
            assertEquals("#c0392b", axis.values().get(0).meta().get("color"),
                    "<color> folds into meta, where a yaml value's sibling keys go");
            assertTrue(axis.values().get(1).meta().isEmpty(), "no colour declared, no meta");
        }

        @Test
        void should_match_a_numeric_frontmatter_value_despite_being_a_string(@TempDir Path root) {
            // XML has no scalar types, but every axis comparison runs both sides
            // through String.valueOf first, so <id>1</id> still matches `tier: 1`.
            BookLayout book = new BookLayout();
            set(book, "axes", List.of(axis("tier", "Tier", List.of(value("1", "Critical", null)))));

            Object id = book.mergeInto(BookConfig.empty(root), root)
                    .axes().get(0).values().get(0).id();

            assertEquals("1", String.valueOf(id));
            assertEquals(String.valueOf(1), String.valueOf(id),
                    "the same normalisation a card's Integer 1 goes through");
        }

        @Test
        void should_replace_yaml_declared_axes_wholesale(@TempDir Path root) {
            BookConfig base = new BookConfig(root, "Book",
                    List.of(new Axis("section", "Section",
                            List.of(AxisValue.of("intro", "Intro")), null)),
                    List.of(), Map.of(), List.of(), null, null, null, null, null, null, null,
                    List.of());

            BookLayout book = new BookLayout();
            set(book, "axes", List.of(axis("tier", "Tier", List.of(value("1", "Critical", null)))));

            List<Axis> merged = book.mergeInto(base, root).axes();

            assertEquals(1, merged.size(), "one structural statement, not a merge by name");
            assertEquals("tier", merged.get(0).name());
        }

        @Test
        void should_resolve_a_landing_template_against_the_book_root(@TempDir Path root) {
            AxisConfig tier = axis("tier", "Tier", List.of(value("1", "Critical", null)));
            set(tier, "landingTemplate", "layouts/tier.html");

            BookLayout book = new BookLayout();
            set(book, "axes", List.of(tier));

            assertEquals(root.resolve("layouts/tier.html"),
                    book.mergeInto(BookConfig.empty(root), root).axes().get(0).landingTemplate());
        }

        @Test
        void should_reject_an_axis_that_cannot_produce_structure(@TempDir Path root) {
            BookLayout noName = new BookLayout();
            set(noName, "axes", List.of(axis(null, "Tier", List.of(value("1", "Critical", null)))));
            assertThrows(IllegalArgumentException.class,
                    () -> noName.mergeInto(BookConfig.empty(root), root));

            BookLayout noValues = new BookLayout();
            set(noValues, "axes", List.of(axis("tier", "Tier", List.of())));
            assertThrows(IllegalArgumentException.class,
                    () -> noValues.mergeInto(BookConfig.empty(root), root));

            BookLayout valueWithoutId = new BookLayout();
            set(valueWithoutId, "axes",
                    List.of(axis("tier", "Tier", List.of(value(null, "Critical", null)))));
            assertThrows(IllegalArgumentException.class,
                    () -> valueWithoutId.mergeInto(BookConfig.empty(root), root));
        }
    }

    @Nested
    @DisplayName("Card id collisions")
    class CardIds {

        private Card card(String id, Path source) {
            return new Card(id, source, dev.noregressions.paperband.model.Frontmatter.empty(),
                    id, List.of());
        }

        /**
         * A book that names every scenario's file {@code TRACE.md} gives them all
         * the id {@code TRACE}. That id is the PDF's anchor and the site's page
         * filename, so the duplicates don't merge — they overwrite, and a site
         * quietly ships a fraction of its cards.
         */
        @Test
        void should_fail_naming_every_file_that_shares_an_id(@TempDir Path root) {
            List<Card> cards = List.of(
                    card("TRACE", root.resolve("scenarios/S01/TRACE.md")),
                    card("TRACE", root.resolve("scenarios/S02/TRACE.md")),
                    card("intro", root.resolve("setup/intro.md")));

            MojoFailureException e = assertThrows(MojoFailureException.class,
                    () -> CardLoading.requireUniqueIds(cards, root));

            assertTrue(e.getMessage().contains("'TRACE' claimed by 2 cards"),
                    e.getMessage());
            assertTrue(e.getMessage().contains("scenarios/S01/TRACE.md"), "names the first file");
            assertTrue(e.getMessage().contains("scenarios/S02/TRACE.md"), "and the second");
            assertFalse(e.getMessage().contains("setup/intro.md"), "leaves innocent cards out of it");
            assertTrue(e.getMessage().contains("id:"), "and says how to fix it");
        }

        @Test
        void should_pass_when_every_card_is_its_own(@TempDir Path root) throws Exception {
            CardLoading.requireUniqueIds(List.of(
                    card("s01-trace", root.resolve("scenarios/S01/TRACE.md")),
                    card("s02-trace", root.resolve("scenarios/S02/TRACE.md"))), root);
        }
    }

    @Nested
    @DisplayName("theme=none")
    class ThemeNone {

        @Test
        void should_turn_theming_off_even_when_the_book_asks_for_a_theme() throws Exception {
            // The reason `none` has to exist: an unset <theme> falls back to the
            // book's own, so without it the build can swap one theme for another
            // but never for nothing.
            assertSame(ThemeBundle.NONE, Themes.resolve("none", "blueprint", null));
            assertSame(ThemeBundle.NONE, Themes.resolve("NONE", "blueprint", null));
        }

        @Test
        void should_otherwise_prefer_the_declared_theme_then_the_books() throws Exception {
            assertEquals("blueprint", Themes.resolve("blueprint", "editorial", null).name());
            assertEquals("editorial", Themes.resolve(null, "editorial", null).name(),
                    "unset falls back to the book's theme");
            assertEquals("editorial", Themes.resolve("  ", "editorial", null).name());
            assertSame(ThemeBundle.NONE, Themes.resolve(null, null, null),
                    "no theme anywhere is still a valid build");
        }
    }
}
