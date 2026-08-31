package dev.noregressions.paperband.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Files that describe a folder rather than being a card in it.
 *
 * <p>{@code _section.md} is a section's own introduction, rendered onto its
 * landing page. Loading it as a card too is visibly wrong: the prose appears
 * above the card list and as an entry in it, and the section's card count is
 * one too high.
 */
@DisplayName("Intro files are not cards")
class IntroFileDiscoveryTest {

    private static boolean isCard(String name) {
        return CardFiles.isCard(Path.of("content/tools", name), false);
    }

    private static boolean namedExplicitly(String name) {
        return CardFiles.isCard(Path.of("content/tools", name), false, true);
    }

    @Test
    void a_section_intro_is_not_a_card() {
        assertFalse(isCard("_section.md"));
    }

    @Test
    void a_readme_is_not_a_card() {
        assertFalse(isCard("README.md"));
        assertFalse(isCard("readme.md"), "the check is case-insensitive");
    }

    @Test
    void an_ordinary_card_still_is_one() {
        assertTrue(isCard("jdeps.md"));
        assertTrue(isCard("_not-a-section.md"), "only the exact name is reserved");
    }

    @Test
    void a_glob_that_names_one_explicitly_still_wins() {
        // A pattern that says `*/README.md` means those files and nothing else;
        // refusing them would make it match nothing with no way to tell why.
        assertTrue(namedExplicitly("README.md"));
        assertTrue(namedExplicitly("_section.md"));
    }
}
