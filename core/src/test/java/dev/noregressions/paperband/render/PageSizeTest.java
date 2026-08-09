package dev.noregressions.paperband.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

class PageSizeTest {

    @Nested
    @DisplayName("Predefined constants")
    class PredefinedConstants {

        @Test
        void should_have_correct_a4_dimensions() {
            assertEquals(210, PageSize.A4.width());
            assertEquals(297, PageSize.A4.height());
            assertEquals(Unit.MM, PageSize.A4.unit());
        }

        @Test
        void should_have_correct_a5_dimensions() {
            assertEquals(148, PageSize.A5.width());
            assertEquals(210, PageSize.A5.height());
            assertEquals(Unit.MM, PageSize.A5.unit());
        }

        @Test
        void should_have_correct_letter_dimensions() {
            assertEquals(8.5, PageSize.LETTER.width());
            assertEquals(11, PageSize.LETTER.height());
            assertEquals(Unit.INCH, PageSize.LETTER.unit());
        }

        @Test
        void should_have_correct_legal_dimensions() {
            assertEquals(8.5, PageSize.LEGAL.width());
            assertEquals(14, PageSize.LEGAL.height());
            assertEquals(Unit.INCH, PageSize.LEGAL.unit());
        }
    }

    @Nested
    @DisplayName("Custom page sizes")
    class CustomPageSizes {

        @Test
        void should_create_custom_page_size_with_valid_dimensions() {
            PageSize custom = PageSize.of(100, 150, Unit.MM);

            assertEquals(100, custom.width());
            assertEquals(150, custom.height());
            assertEquals(Unit.MM, custom.unit());
        }

        @Test
        void should_create_page_size_with_different_units() {
            PageSize inchPage = PageSize.of(6, 9, Unit.INCH);
            PageSize pointPage = PageSize.of(432, 648, Unit.POINT);

            assertEquals(Unit.INCH, inchPage.unit());
            assertEquals(Unit.POINT, pointPage.unit());
        }

        @Test
        void should_handle_decimal_dimensions() {
            PageSize fractional = PageSize.of(8.27, 11.69, Unit.INCH);

            assertEquals(8.27, fractional.width(), 0.001);
            assertEquals(11.69, fractional.height(), 0.001);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        void should_throw_when_width_is_zero() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                PageSize.of(0, 100, Unit.MM));

            assertEquals("page dimensions must be positive", exception.getMessage());
        }

        @Test
        void should_throw_when_height_is_zero() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                PageSize.of(100, 0, Unit.MM));

            assertEquals("page dimensions must be positive", exception.getMessage());
        }

        @Test
        void should_throw_when_width_is_negative() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                PageSize.of(-10, 100, Unit.MM));

            assertEquals("page dimensions must be positive", exception.getMessage());
        }

        @Test
        void should_throw_when_height_is_negative() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                PageSize.of(100, -10, Unit.MM));

            assertEquals("page dimensions must be positive", exception.getMessage());
        }

        @Test
        void should_throw_when_unit_is_null() {
            NullPointerException exception = assertThrows(NullPointerException.class, () ->
                PageSize.of(100, 150, null));

            assertEquals("unit", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Factory method equivalence")
    class FactoryMethodEquivalence {

        @Test
        void should_create_equivalent_page_size_via_constructor_and_factory() {
            PageSize viaConstructor = new PageSize(100, 150, Unit.MM);
            PageSize viaFactory = PageSize.of(100, 150, Unit.MM);

            assertEquals(viaConstructor, viaFactory);
            assertEquals(viaConstructor.hashCode(), viaFactory.hashCode());
        }
    }

    @Nested
    @DisplayName("Record behavior")
    class RecordBehavior {

        @Test
        void should_implement_equals_correctly() {
            PageSize size1 = PageSize.of(100, 150, Unit.MM);
            PageSize size2 = PageSize.of(100, 150, Unit.MM);

            assertEquals(size1, size2);
            assertEquals(size1.hashCode(), size2.hashCode());
        }

        @Test
        void should_implement_equals_correctly_with_different_values() {
            PageSize size1 = PageSize.of(100, 150, Unit.MM);
            PageSize size2 = PageSize.of(100, 150, Unit.INCH);
            PageSize size3 = PageSize.of(200, 150, Unit.MM);

            assertNotEquals(size1, size2);
            assertNotEquals(size1, size3);
        }

        @Test
        void should_have_useful_to_string() {
            PageSize size = PageSize.of(100, 150, Unit.MM);
            String toString = size.toString();

            assertTrue(toString.contains("PageSize"));
            assertTrue(toString.contains("100"));
            assertTrue(toString.contains("150"));
            assertTrue(toString.contains("MM"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void should_handle_very_small_positive_dimensions() {
            PageSize tiny = PageSize.of(0.001, 0.001, Unit.MM);

            assertEquals(0.001, tiny.width(), 0.0001);
            assertEquals(0.001, tiny.height(), 0.0001);
        }

        @Test
        void should_handle_very_large_dimensions() {
            PageSize huge = PageSize.of(10000, 10000, Unit.POINT);

            assertEquals(10000, huge.width());
            assertEquals(10000, huge.height());
        }
    }
}