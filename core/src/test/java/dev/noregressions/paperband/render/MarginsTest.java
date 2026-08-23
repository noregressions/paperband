package dev.noregressions.paperband.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarginsTest {

    @Nested
    @DisplayName("CSS shorthand parsing")
    class ShorthandParsing {

        /** All four edges, in millimetres, for terser assertions. */
        private double[] mm(String shorthand) {
            Margins m = Margins.parse(shorthand);
            return new double[] {
                    m.unit().toMillimetres(m.top()),
                    m.unit().toMillimetres(m.right()),
                    m.unit().toMillimetres(m.bottom()),
                    m.unit().toMillimetres(m.left())
            };
        }

        private void assertEdges(String shorthand, double top, double right, double bottom, double left) {
            double[] got = mm(shorthand);
            assertEquals(top, got[0], 0.01, shorthand + " top");
            assertEquals(right, got[1], 0.01, shorthand + " right");
            assertEquals(bottom, got[2], 0.01, shorthand + " bottom");
            assertEquals(left, got[3], 0.01, shorthand + " left");
        }

        @Test
        void oneToFourValuesReadTheWayCssReadsThem() {
            assertEdges("18mm", 18, 18, 18, 18);
            assertEdges("20mm 15mm", 20, 15, 20, 15);
            assertEdges("20mm 15mm 25mm", 20, 15, 25, 15);
            assertEdges("20 15 25 10", 20, 15, 25, 10);
        }

        @Test
        void zeroIsTheFullBleedCase() {
            assertEdges("0", 0, 0, 0, 0);
        }

        @Test
        void unitsAreOptionalAndMayBeMixed() {
            assertEdges("20", 20, 20, 20, 20);            // bare number is mm
            assertEdges("1in", 25.4, 25.4, 25.4, 25.4);
            assertEdges("1inch", 25.4, 25.4, 25.4, 25.4);
            assertEdges("72pt", 25.4, 25.4, 25.4, 25.4);
            assertEdges("2cm", 20, 20, 20, 20);
            assertEdges("1in 10mm", 25.4, 10, 25.4, 10);  // mixed, normalised to mm
        }

        @Test
        void separatorsMayBeSpacesOrCommas() {
            assertEdges("20mm,15mm", 20, 15, 20, 15);
            assertEdges("20mm,  15mm", 20, 15, 20, 15);
            assertEdges("  18mm  ", 18, 18, 18, 18);
        }

        @Test
        void nothingDeclaredMeansNoOverride() {
            assertNull(Margins.parse(null), "null keeps the page preset's own margins");
            assertNull(Margins.parse(""));
            assertNull(Margins.parse("   "));
        }

        @Test
        void malformedShorthandIsRejectedRatherThanGuessedAt() {
            // A typo in page geometry should fail the build, not silently
            // render at the preset's margins.
            assertThrows(IllegalArgumentException.class, () -> Margins.parse("wide"));
            assertThrows(IllegalArgumentException.class, () -> Margins.parse("18em"));
            assertThrows(IllegalArgumentException.class, () -> Margins.parse("-5mm"));
            assertThrows(IllegalArgumentException.class, () -> Margins.parse("1 2 3 4 5"));
        }
    }
}
