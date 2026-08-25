package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookPlan;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookLayout#toSpecs()} — the POM-to-plan translation. Maven's
 * configurator populates these plain-data holders by element name, so the
 * tests set the fields the same way (reflection) rather than standing up a
 * Plexus container just to prove a mapping.
 */
class BookLayoutTest {

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

    private static SectionConfig section(String title, String include) {
        SectionConfig p = new SectionConfig();
        set(p, "title", title);
        set(p, "includes", List.of(include));
        return p;
    }

    @Test
    void everySectionGetsItsOwnPageUnlessTheDeclarationOptsOut() {
        SectionConfig setup = section("Introduction and Setup", "setup/**/*.md");
        SectionConfig appendix = section("Appendix", "appendix/*.md");
        set(appendix, "landingPage", false);

        BookLayout book = new BookLayout();
        set(book, "sections", List.of(setup, appendix));

        List<BookPlan.SectionSpec> specs = book.toSpecs();

        assertEquals(2, specs.size());
        assertTrue(specs.get(0).landingPage(), "generation is on by default");
        assertFalse(specs.get(1).landingPage(), "<landingPage>false</landingPage> turns it off");
        assertEquals("Introduction and Setup", specs.get(0).title());
        assertEquals(List.of("appendix/*.md"), specs.get(1).includes());
    }

    @Test
    void thePatternShorthandCarriesEverythingItDeclares() {
        BookLayout book = new BookLayout();
        set(book, "includes", List.of("scenarios/**/TRACE.md"));
        set(book, "excludes", List.of("scenarios/wip/**"));
        set(book, "sort", "tier, -id");

        List<BookPlan.SectionSpec> specs = book.toSpecs();

        assertEquals(1, specs.size());
        BookPlan.SectionSpec spec = specs.get(0);
        assertEquals(List.of("scenarios/**/TRACE.md"), spec.includes());
        assertEquals(List.of("scenarios/wip/**"), spec.excludes());
        assertEquals(List.of("tier", "-id"), spec.sort(), "sort fields split and trimmed");
        assertNull(spec.id(), "the shorthand is one untitled, anonymous section");
        assertNull(spec.title());
    }

    @Test
    void bothFormsAtOnceOrNeitherIsRejected() {
        BookLayout both = new BookLayout();
        set(both, "sections", List.of(section("A", "a/*.md")));
        set(both, "includes", List.of("b/*.md"));
        assertThrows(IllegalArgumentException.class, both::toSpecs);

        assertThrows(IllegalArgumentException.class, new BookLayout()::toSpecs);
    }

    @Test
    void aSectionWithNoIncludesIsRejected() {
        SectionConfig empty = new SectionConfig();
        set(empty, "title", "Nothing");
        BookLayout book = new BookLayout();
        set(book, "sections", List.of(empty));

        assertThrows(IllegalArgumentException.class, book::toSpecs);
    }
}
