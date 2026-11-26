package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** fact("name") in a template: a figure from the facts data file, written as the file says, never silently missing. */
class FactsTest {

    @TempDir
    Path templates;

    private static final Object FACTS = FrontMatter.parseYaml("""
            cves-2026: 93081
            dependency-lag:
              value: 278
              unit: days
              was: 215
              good: down
              label: Median dependency lag
              source: Datadog
              url: https://example.com/devsecops
              kind: vendor
            scored: { value: 8.7, unit: "%" }
            breach: { value: 4.99, prefix: $, unit: million }
            window: { text: 4 to 7 months, source: AISI }
            """);

    private String run(String body) throws IOException {
        return new PebblePreprocessor(templates, Map.of()).process(Path.of("a.md"), body, Map.of("data", Map.of("facts", FACTS)));
    }

    @Test
    void aFactPrintsAsItIsWritten() throws IOException {
        assertEquals("93,081 CVEs, 278 days, 8.7%, $4.99 million, 4 to 7 months\n", run("""
                {{ fact("cves-2026") }} CVEs, {{ fact("dependency-lag") }}, {{ fact("scored") }}, {{ fact("breach") }}, {{ fact("window") }}
                """));
    }

    @Test
    void aFactsKeysAndItsNumberCanBeUsed() throws IOException {
        assertEquals("Datadog (vendor): up from 215 days, 63 more\n", run("""
                {{ fact("dependency-lag").source }} ({{ fact("dependency-lag").kind }}): {{ fact("dependency-lag").trend }} from {{ fact("dependency-lag").was }}, {{ fact("dependency-lag").value - 215 }} more
                """));
    }

    @Test
    void aFactInATemplatedFenceIsFilledIn() throws IOException {
        String out = run("""
                ```tiles {! template}
                tiles:
                  - { fact: {{ fact("dependency-lag").tile }}, tag: watch }
                ```
                """);
        assertTrue(out.contains("- { fact: {value: \"278 days\", label: \"Median dependency lag\", "
                + "source: \"[Datadog](https://example.com/devsecops)\", kind: \"vendor\", was: \"215 days\", "
                + "trend: \"up\", good: \"down\"}, tag: watch }"), out);
    }

    @Test
    void anUnknownFactStopsTheBuildAndSuggestsANearName() {
        IOException e = assertThrows(IOException.class, () -> run("{{ fact(\"dependancy-lag\") }}\n"));
        assertTrue(e.getMessage().contains("fact(\"dependancy-lag\"): no fact has that name (did you mean \"dependency-lag\"?)"),
                e.getMessage());
    }

    @Test
    void aFactWithAKeyFactsDoNotHaveIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Facts.of(Map.of("x", Map.of("value", 1, "colour", "red")), "x"));
        assertEquals("fact \"x\": unknown key 'colour'", e.getMessage());
        assertEquals("fact \"y\" needs a 'value' or a 'text'",
                assertThrows(IllegalArgumentException.class, () -> Facts.of(Map.of("y", Map.of("unit", "days")), "y")).getMessage());
    }

    @Test
    void withoutAFactsFileTheErrorSaysWhereItGoes() {
        IOException e = assertThrows(IOException.class, () -> new PebblePreprocessor(templates, Map.of())
                .process(Path.of("a.md"), "{{ fact(\"x\") }}\n", Map.of("data", Map.of())));
        assertTrue(e.getMessage().contains("there is no data file named facts (data/facts.yml)"), e.getMessage());
    }
}
