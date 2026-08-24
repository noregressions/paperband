package dev.noregressions.paperband.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamedTemplatesTest {

    @Nested
    @DisplayName("Declared template paths")
    class DeclaredPaths {

        @Test
        void should_be_relative_to_the_layouts_directory() {
            // What every book and doc writes: the file as it sits on disk.
            assertEquals("footer", NamedTemplates.templateName("layouts/footer.html"));
            // And the same file named without the redundant prefix.
            assertEquals("footer", NamedTemplates.templateName("footer.html"));
        }

        @Test
        void should_keep_subdirectories_so_they_are_addressable_at_all() {
            // Used to collapse to "front", which looked for layouts/front.html
            // — so a template exactly where it said it was could not be found.
            assertEquals("covers/front", NamedTemplates.templateName("layouts/covers/front.html"));
            assertEquals("covers/front", NamedTemplates.templateName("covers/front.html"));
            assertEquals("a/b/c", NamedTemplates.templateName("layouts/a/b/c.html"));
        }

        @Test
        void should_leave_a_bare_name_alone() {
            // A bundled template, or a preset already resolved elsewhere.
            assertEquals("_book-cover", NamedTemplates.templateName("_book-cover"));
            assertEquals("minimal", NamedTemplates.templateName("minimal"));
        }

        @Test
        void should_strip_only_the_final_extension() {
            assertEquals("footer.print", NamedTemplates.templateName("layouts/footer.print.html"));
            assertEquals("dotted.name", NamedTemplates.templateName("dotted.name.html"));
        }

        @Test
        void should_fall_back_to_the_filename_for_paths_the_loader_cannot_reach() {
            // Absolute, or climbing out of layouts/: not addressable through a
            // layouts-rooted loader, so name the file and let the "looked in
            // ..." error explain if it isn't there.
            assertEquals("front", NamedTemplates.templateName("/etc/paperband/front.html"));
            assertEquals("front", NamedTemplates.templateName("../elsewhere/front.html"));
        }

        @Test
        void should_treat_nothing_as_nothing() {
            assertNull(NamedTemplates.templateName(null));
            assertNull(NamedTemplates.templateName("   "));
        }
    }

    @Nested
    @DisplayName("Resolved template paths")
    class ResolvedPaths {

        @Test
        void should_be_expressed_relative_to_layouts_when_inside_it() {
            Path bookRoot = Path.of("/book");
            assertEquals("covers/front",
                    NamedTemplates.templateName(bookRoot, Path.of("/book/layouts/covers/front.html")));
            assertEquals("footer",
                    NamedTemplates.templateName(bookRoot, Path.of("/book/layouts/footer.html")));
        }

        @Test
        void should_fall_back_to_the_filename_when_outside_layouts() {
            Path bookRoot = Path.of("/book");
            assertEquals("front",
                    NamedTemplates.templateName(bookRoot, Path.of("/book/elsewhere/front.html")));
            assertEquals("front",
                    NamedTemplates.templateName(null, Path.of("/anywhere/front.html")));
        }
    }

    @Nested
    @DisplayName("Section landing presets")
    class SectionPresets {

        @Test
        void should_resolve_preset_names_without_touching_the_filesystem() {
            assertEquals("site-section", NamedTemplates.resolveSectionTemplate(Path.of("/book"), "default"));
            assertEquals("site-section-minimal",
                    NamedTemplates.resolveSectionTemplate(Path.of("/book"), "minimal"));
        }

        @Test
        void should_treat_anything_else_as_a_template_path() {
            assertEquals("sections/scanners",
                    NamedTemplates.resolveSectionTemplate(Path.of("/book"), "layouts/sections/scanners.html"));
            assertNull(NamedTemplates.resolveSectionTemplate(Path.of("/book"), null));
        }
    }
}
