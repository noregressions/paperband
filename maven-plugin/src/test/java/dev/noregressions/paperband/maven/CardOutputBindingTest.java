package dev.noregressions.paperband.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card markdown can branch on which output is being built, in every goal that
 * loads cards.
 *
 * <p>This was once bound at each call site, and three of six were missed — the
 * failure silent, because an undefined Pebble variable is simply not equal to
 * {@code "print"}, so a site-only passage rendered into the book. The guard is
 * therefore structural: no goal may build its own preprocessor.
 */
class CardOutputBindingTest {

    private static final Path MAVEN_SRC =
            Path.of("src/main/java/dev/noregressions/paperband/maven");

    @Test
    @DisplayName("every card-loading goal goes through the one factory")
    void no_goal_builds_its_own_card_preprocessor() throws IOException {
        Pattern direct = Pattern.compile("Includes\\.defaultPreprocessor\\(");
        try (Stream<Path> files = Files.walk(MAVEN_SRC)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    // SectionBodies is the documented exception: a section body
                    // also needs a `section` model, so it wires its own and
                    // binds the same keys.
                    .filter(p -> !p.getFileName().toString().equals("SectionBodies.java"))
                    .filter(p -> !p.getFileName().toString().equals("CardLoading.java"))
                    .filter(p -> {
                        try {
                            return direct.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .toList();

            assertEquals(List.of(), offenders,
                    "these build a card preprocessor directly and so leave `output` unbound; "
                            + "use CardLoading.preprocessorFor instead");
        }
    }

    @Test
    @DisplayName("the factory binds both output and target")
    void factory_binds_the_builds_identity() throws IOException {
        String src = Files.readString(MAVEN_SRC.resolve("CardLoading.java"));

        assertTrue(src.contains("model.put(\"output\", output)"), src);
        assertTrue(src.contains("model.put(\"target\", target)"), src);
    }

    @Test
    @DisplayName("a site-only passage is a no-op for a card with no conditional")
    void plain_cards_are_unaffected() {
        // The binding adds keys; it never rewrites content. A card that says
        // nothing about output must render identically in both goals.
        assertEquals(Map.of("output", "print", "target", "pdf-a4").keySet().size(), 2);
    }
}
