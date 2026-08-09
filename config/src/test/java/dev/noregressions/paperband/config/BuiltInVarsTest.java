package dev.noregressions.paperband.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuiltInVarsTest {

    /** Clock pinned to 2026-05-12T14:23:01Z so assertions are deterministic. */
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-05-12T14:23:01Z"), ZoneOffset.UTC);

    @Test
    void compute_includesAllExpectedKeys() {
        Map<String, Object> vars = BuiltInVars.compute(FIXED);
        assertEquals(
                java.util.Set.of("build_date", "build_date_long",
                        "build_year", "build_month_year", "build_iso"),
                vars.keySet(),
                "no unexpected keys; no missing keys");
    }

    @Test
    void compute_buildDateIsIsoFormat() {
        assertEquals("2026-05-12", BuiltInVars.compute(FIXED).get("build_date"));
    }

    @Test
    void compute_buildDateLongIsHumanReadable() {
        assertEquals("May 12, 2026", BuiltInVars.compute(FIXED).get("build_date_long"));
    }

    @Test
    void compute_buildYearIsFourDigits() {
        assertEquals("2026", BuiltInVars.compute(FIXED).get("build_year"));
    }

    @Test
    void compute_buildMonthYearIsSpelledOut() {
        assertEquals("May 2026", BuiltInVars.compute(FIXED).get("build_month_year"));
    }

    @Test
    void compute_buildIsoIsUtcInstant() {
        assertEquals("2026-05-12T14:23:01Z", BuiltInVars.compute(FIXED).get("build_iso"));
    }

    @Test
    void compute_systemClock_returnsAllKeysAndStringValues() {
        // Smoke test against the real clock — just verify shape, not values.
        Map<String, Object> vars = BuiltInVars.compute();
        assertEquals(5, vars.size());
        for (var e : vars.entrySet()) {
            assertNotNull(e.getValue(), e.getKey() + " should have a value");
            assertInstanceOf(String.class, e.getValue(),
                    e.getKey() + " should be a String for the whole-body Pebble vars pass");
            assertFalse(((String) e.getValue()).isBlank(),
                    e.getKey() + " should be non-blank");
        }
    }

    @Test
    void compute_yearBoundary_handlesDecember31() {
        Clock newYearsEve = Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC);
        Map<String, Object> vars = BuiltInVars.compute(newYearsEve);
        assertEquals("2026-12-31",       vars.get("build_date"));
        assertEquals("December 31, 2026", vars.get("build_date_long"));
        assertEquals("2026",              vars.get("build_year"));
        assertEquals("December 2026",     vars.get("build_month_year"));
    }
}
