package dev.noregressions.paperband.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

class PageSpecTest {

    @Nested
    @DisplayName("Predefined page specs")
    class PredefinedPageSpecs {

        @Test
        void should_create_correct_a4_page_spec() {
            PageSpec a4 = PageSpec.a4();

            assertEquals(PageSize.A4, a4.size());
            assertEquals(Margins.standard(), a4.margins());
            assertEquals(Orientation.PORTRAIT, a4.orientation());
        }

        @Test
        void should_create_correct_letter_page_spec() {
            PageSpec letter = PageSpec.letter();

            assertEquals(PageSize.LETTER, letter.size());
            assertEquals(Margins.standard(), letter.margins());
            assertEquals(Orientation.PORTRAIT, letter.orientation());
        }

        @Test
        void should_create_correct_booklet_page_spec() {
            PageSpec booklet = PageSpec.booklet6x9();

            assertEquals(PageSize.of(6, 9, Unit.INCH), booklet.size());
            assertEquals(Margins.uniform(15, Unit.MM), booklet.margins());
            assertEquals(Orientation.PORTRAIT, booklet.orientation());
        }

        @Test
        void should_create_correct_packt_page_spec() {
            PageSpec packt = PageSpec.packt();

            assertEquals(PageSize.of(7.5, 9.25, Unit.INCH), packt.size());
            assertEquals(Margins.uniform(18, Unit.MM), packt.margins());
            assertEquals(Orientation.PORTRAIT, packt.orientation());
        }
    }

    @Nested
    @DisplayName("Custom page specs")
    class CustomPageSpecs {

        @Test
        void should_create_custom_page_spec_with_all_components() {
            PageSize customSize = PageSize.of(100, 150, Unit.MM);
            Margins customMargins = Margins.uniform(10, Unit.MM);
            Orientation landscape = Orientation.LANDSCAPE;

            PageSpec custom = new PageSpec(customSize, customMargins, landscape);

            assertEquals(customSize, custom.size());
            assertEquals(customMargins, custom.margins());
            assertEquals(landscape, custom.orientation());
        }

        @Test
        void should_handle_different_margin_configurations() {
            PageSize size = PageSize.A4;
            Margins asymmetric = Margins.of(10, 15, 20, 25, Unit.MM);
            Orientation portrait = Orientation.PORTRAIT;

            PageSpec spec = new PageSpec(size, asymmetric, portrait);

            assertEquals(asymmetric, spec.margins());
            assertEquals(10, spec.margins().top());
            assertEquals(15, spec.margins().right());
            assertEquals(20, spec.margins().bottom());
            assertEquals(25, spec.margins().left());
        }

        @Test
        void should_handle_different_page_sizes_and_orientations() {
            PageSize legal = PageSize.LEGAL;
            Margins standard = Margins.standard();
            Orientation landscape = Orientation.LANDSCAPE;

            PageSpec landscapeLegal = new PageSpec(legal, standard, landscape);

            assertEquals(legal, landscapeLegal.size());
            assertEquals(landscape, landscapeLegal.orientation());
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        void should_throw_when_page_size_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new PageSpec(null, Margins.standard(), Orientation.PORTRAIT));

            assertEquals("size", exception.getMessage());
        }

        @Test
        void should_throw_when_margins_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new PageSpec(PageSize.A4, null, Orientation.PORTRAIT));

            assertEquals("margins", exception.getMessage());
        }

        @Test
        void should_throw_when_orientation_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                new PageSpec(PageSize.A4, Margins.standard(), null));

            assertEquals("orientation", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Factory method consistency")
    class FactoryMethodConsistency {

        @Test
        void should_maintain_a4_spec_consistency() {
            PageSpec a4FromFactory = PageSpec.a4();
            PageSpec a4Manual = new PageSpec(PageSize.A4, Margins.standard(), Orientation.PORTRAIT);

            assertEquals(a4FromFactory, a4Manual);
        }

        @Test
        void should_maintain_letter_spec_consistency() {
            PageSpec letterFromFactory = PageSpec.letter();
            PageSpec letterManual = new PageSpec(PageSize.LETTER, Margins.standard(), Orientation.PORTRAIT);

            assertEquals(letterFromFactory, letterManual);
        }

        @Test
        void should_maintain_booklet_spec_consistency() {
            PageSpec bookletFromFactory = PageSpec.booklet6x9();
            PageSpec bookletManual = new PageSpec(
                PageSize.of(6, 9, Unit.INCH),
                Margins.uniform(15, Unit.MM),
                Orientation.PORTRAIT
            );

            assertEquals(bookletFromFactory, bookletManual);
        }

        @Test
        void should_maintain_packt_spec_consistency() {
            PageSpec packtFromFactory = PageSpec.packt();
            PageSpec packtManual = new PageSpec(
                PageSize.of(7.5, 9.25, Unit.INCH),
                Margins.uniform(18, Unit.MM),
                Orientation.PORTRAIT
            );

            assertEquals(packtFromFactory, packtManual);
        }
    }

    @Nested
    @DisplayName("Record behavior")
    class RecordBehavior {

        @Test
        void should_implement_equals_correctly() {
            PageSpec spec1 = new PageSpec(PageSize.A4, Margins.standard(), Orientation.PORTRAIT);
            PageSpec spec2 = new PageSpec(PageSize.A4, Margins.standard(), Orientation.PORTRAIT);

            assertEquals(spec1, spec2);
            assertEquals(spec1.hashCode(), spec2.hashCode());
        }

        @Test
        void should_implement_equals_correctly_with_different_values() {
            PageSpec spec1 = new PageSpec(PageSize.A4, Margins.standard(), Orientation.PORTRAIT);
            PageSpec spec2 = new PageSpec(PageSize.A5, Margins.standard(), Orientation.PORTRAIT);
            PageSpec spec3 = new PageSpec(PageSize.A4, Margins.standard(), Orientation.LANDSCAPE);

            assertNotEquals(spec1, spec2);
            assertNotEquals(spec1, spec3);
        }

        @Test
        void should_have_useful_to_string() {
            PageSpec spec = new PageSpec(PageSize.A4, Margins.standard(), Orientation.LANDSCAPE);
            String toString = spec.toString();

            assertTrue(toString.contains("PageSpec"));
            assertTrue(toString.contains("LANDSCAPE"));
            // The toString should contain the PageSize representation (which includes dimensions)
            assertTrue(toString.contains("210") || toString.contains("297")); // A4 dimensions
        }
    }

    @Nested
    @DisplayName("Integration scenarios")
    class IntegrationScenarios {

        @Test
        void should_support_common_print_scenarios() {
            // US Letter with narrow margins for text-heavy documents
            PageSpec textDoc = new PageSpec(
                PageSize.LETTER,
                Margins.uniform(12.7, Unit.MM), // 0.5 inch
                Orientation.PORTRAIT
            );

            // A4 landscape for wide tables or charts
            PageSpec chartDoc = new PageSpec(
                PageSize.A4,
                Margins.uniform(10, Unit.MM),
                Orientation.LANDSCAPE
            );

            // Custom booklet size
            PageSpec miniBooklet = new PageSpec(
                PageSize.of(4, 6, Unit.INCH),
                Margins.uniform(8, Unit.MM),
                Orientation.PORTRAIT
            );

            assertAll(
                () -> assertEquals(PageSize.LETTER, textDoc.size()),
                () -> assertEquals(Orientation.LANDSCAPE, chartDoc.orientation()),
                () -> assertEquals(4.0, miniBooklet.size().width())
            );
        }
    }

    @Nested
    @DisplayName("sizeLabel")
    class SizeLabel {

        @Test
        void names_every_preset_with_the_slug_that_parses_back() {
            // The round trip is the contract: whatever sizeLabel() prints,
            // forSizeName() must accept, so a log line can be pasted straight
            // into a <pageSize> or a page.size:.
            assertAll(
                () -> assertEquals("a4", PageSpec.a4().sizeLabel()),
                () -> assertEquals("a5", PageSpec.a5().sizeLabel()),
                () -> assertEquals("letter", PageSpec.letter().sizeLabel()),
                () -> assertEquals("6x9", PageSpec.booklet6x9().sizeLabel()),
                () -> assertEquals("packt", PageSpec.packt().sizeLabel()),
                () -> assertEquals("16x9", PageSpec.slide16x9().sizeLabel())
            );
        }

        @Test
        void every_label_it_prints_for_a_preset_round_trips() {
            for (PageSpec spec : new PageSpec[] { PageSpec.a4(), PageSpec.a5(),
                    PageSpec.letter(), PageSpec.booklet6x9(), PageSpec.packt(),
                    PageSpec.slide16x9() }) {
                assertEquals(spec.size(),
                    PageSpec.forSizeName(spec.sizeLabel()).size(),
                    spec.sizeLabel() + " must parse back to the size it names");
            }
        }

        @Test
        void names_legal_even_though_no_factory_builds_it() {
            // page.size: legal resolves through PageConfigResolver, which has
            // a slug forSizeName doesn't. The label still has to name it —
            // otherwise a legal build reports itself as 8.5x14in.
            assertEquals("legal",
                new PageSpec(PageSize.LEGAL, Margins.standard(), Orientation.PORTRAIT)
                    .sizeLabel());
        }

        @Test
        void falls_back_to_dimensions_for_a_size_no_preset_covers() {
            assertAll(
                () -> assertEquals("200x150mm", new PageSpec(
                    PageSize.of(200, 150, Unit.MM), Margins.standard(),
                    Orientation.PORTRAIT).sizeLabel()),
                () -> assertEquals("4x6in", new PageSpec(
                    PageSize.of(4, 6, Unit.INCH), Margins.standard(),
                    Orientation.PORTRAIT).sizeLabel()),
                () -> assertEquals("595x842pt", new PageSpec(
                    PageSize.of(595, 842, Unit.POINT), Margins.standard(),
                    Orientation.PORTRAIT).sizeLabel())
            );
        }

        @Test
        void keeps_a_fractional_dimension_and_drops_a_trailing_zero() {
            // 13.4in must not print as 13in, and a whole-number 300mm must
            // not print as 300.0mm. (210x297mm is not the example to use here:
            // that IS A4 by value, and labels itself "a4" — which the preset
            // tests above pin.)
            assertAll(
                () -> assertEquals("13.4x7.5in", new PageSpec(
                    PageSize.of(13.4, 7.5, Unit.INCH), Margins.standard(),
                    Orientation.PORTRAIT).sizeLabel()),
                () -> assertEquals("300x400mm", new PageSpec(
                    PageSize.of(300.0, 400.0, Unit.MM), Margins.uniform(0, Unit.MM),
                    Orientation.PORTRAIT).sizeLabel())
            );
        }

        @Test
        void ignores_margins_and_orientation_because_only_the_sheet_is_named() {
            // A landscape A4 with hand-set margins is still an a4 sheet; the
            // log line reports rotation and margins separately.
            assertEquals("a4", new PageSpec(
                PageSize.A4, Margins.uniform(3, Unit.MM), Orientation.LANDSCAPE)
                .sizeLabel());
        }
    }
}
