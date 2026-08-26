package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Section;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.render.Margins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Nested
    @DisplayName("No configuration files")
    class NoConfigurationFiles {

        @Test
        void should_return_empty_context_when_no_yaml_files_exist(@TempDir Path tempDir) throws IOException {
            ConfigLoader loader = new ConfigLoader();
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            RenderContext context = loader.load(mdFile, "web", "A4");

            assertNotNull(context);
            assertEquals(tempDir, context.book().bookRoot());
            assertTrue(context.cssChain().isEmpty(), "CSS chain should be empty when no config files");
            // Built-in vars are computed, not configured, so they're present
            // whether or not a yaml is: a book declared entirely outside the
            // tree (the Maven plugin's <book>) reads {{ vars.build_date }} like
            // any other, and this path used to be the one place it came back
            // empty.
            assertFalse(context.vars().isEmpty(), "built-ins are seeded with or without yaml");
            assertTrue(context.vars().containsKey("build_date"));
            assertNull(context.vars().get("author"), "but nothing a book would have declared");
            assertNull(context.layout());
            assertEquals("web", context.target());
            assertEquals("A4", context.size());
        }

        @Test
        void should_handle_markdown_file_in_root_directory(@TempDir Path tempDir) throws IOException {
            ConfigLoader loader = new ConfigLoader();
            Path mdFile = tempDir.resolve("root.md");
            Files.createFile(mdFile);

            RenderContext context = loader.load(mdFile, null, null);

            assertEquals(tempDir, context.book().bookRoot());
            assertNull(context.target());
            assertNull(context.size());
        }
    }

    @Nested
    @DisplayName("Single book root configuration")
    class SingleBookRootConfiguration {

        @Test
        void should_parse_minimal_book_config(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals("Test Book", context.book().title());
            assertEquals(tempDir, context.book().bookRoot());
            assertTrue(context.book().axes().isEmpty());
        }

        @Test
        void should_parse_complete_book_config(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Complete Book"
                theme: "herodevs"
                css:
                  - "styles/book.css"
                  - "styles/custom.css"
                vars:
                  author: "Test Author"
                  version: "1.0"
                targets:
                  - "pdf-a4"
                  - "web"
                axes:
                  - name: "tier"
                    title: "Migration Tier"
                    values:
                      - id: 1
                        label: "Tier 1"
                      - id: 2
                        label: "Tier 2"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals("Complete Book", context.book().title());
            assertEquals("herodevs", context.book().theme());
            assertEquals(2, context.book().globalCss().size());
            assertTrue(context.book().globalCss().get(0).toString().endsWith("styles/book.css"));
            assertEquals(1, context.book().axes().size());
            assertEquals("tier", context.book().axes().get(0).name());
            assertEquals("Test Author", context.vars().get("author"));
            assertEquals("1.0", context.vars().get("version"));
        }

        @Test
        void should_keep_a_landing_template_subdirectory(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                sections:
                  landing:
                    template: "layouts/sections/scanners.html"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            RenderContext context = new ConfigLoader().load(mdFile, "pdf", "A4");

            assertEquals("sections/scanners", context.book().sectionLandingTemplate(),
                    "a template nested under layouts/ is addressable");
        }

        @Test
        void should_parse_section_landing_template_default(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                sections:
                  landing:
                    template: "layouts/my-section.html"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            // Resolved to the template name the engine's loader chain resolves:
            // the path relative to the book's layouts/ directory, extension
            // stripped. This used to keep only the filename, which discarded
            // any directory the author wrote.
            assertEquals("my-section", context.book().sectionLandingTemplate());
        }

        @Test
        void should_resolve_named_preset_for_section_landing_template(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                sections:
                  landing:
                    template: "minimal"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            // "minimal" is a built-in named preset, not a file path -- resolves
            // directly to its bundled template name.
            assertEquals("site-section-minimal", context.book().sectionLandingTemplate());
        }

        @Test
        void should_leave_section_landing_template_null_when_not_declared(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertNull(context.book().sectionLandingTemplate());
        }

        @Test
        void should_handle_single_css_file_as_string(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                css: "single.css"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals(1, context.cssChain().size());
            assertTrue(context.cssChain().get(0).toString().endsWith("single.css"));
        }
    }

    @Nested
    @DisplayName("Configuration cascade")
    class ConfigurationCascade {

        @Test
        void should_cascade_css_files_in_correct_order(@TempDir Path tempDir) throws IOException {
            // Book root config
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                css:
                  - "global.css"
                """);

            // Subfolder config
            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("paperband.yaml"), """
                css:
                  - "local.css"
                """);

            Path mdFile = subfolder.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals(2, context.cssChain().size());
            assertTrue(context.cssChain().get(0).toString().endsWith("global.css"));
            assertTrue(context.cssChain().get(1).toString().endsWith("local.css"));
        }

        @Test
        void should_override_vars_with_later_values(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                vars:
                  author: "Original Author"
                  version: "1.0"
                """);

            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("paperband.yaml"), """
                vars:
                  author: "Updated Author"
                  edition: "Special"
                """);

            Path mdFile = subfolder.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals("Updated Author", context.vars().get("author")); // Overridden
            assertEquals("1.0", context.vars().get("version")); // Preserved
            assertEquals("Special", context.vars().get("edition")); // Added
        }

        @Test
        void should_merge_axis_bindings_into_vars(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                axes:
                  - name: "tier"
                    title: "Migration Tier"
                    values:
                      - id: 1
                        label: "Tier 1"
                      - id: 2
                        label: "Tier 2"
                """);

            Path subfolder = tempDir.resolve("tier1");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("paperband.yaml"), """
                axis:
                  tier: 1
                """);

            Path mdFile = subfolder.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals(1, context.vars().get("tier"));
        }

        @Test
        void should_cascade_through_multiple_levels(@TempDir Path tempDir) throws IOException {
            // Root
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Multi-Level Book"
                css: ["root.css"]
                vars:
                  level: "root"
                  keep: "original"
                """);

            // Level 1
            Path level1 = tempDir.resolve("level1");
            Files.createDirectories(level1);
            createYamlFile(level1.resolve("paperband.yaml"), """
                css: ["level1.css"]
                vars:
                  level: "level1"
                  l1var: "present"
                """);

            // Level 2
            Path level2 = level1.resolve("level2");
            Files.createDirectories(level2);
            createYamlFile(level2.resolve("paperband.yaml"), """
                css: ["level2.css"]
                vars:
                  level: "level2"
                """);

            Path mdFile = level2.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals(3, context.cssChain().size());
            assertTrue(context.cssChain().get(0).toString().endsWith("root.css"));
            assertTrue(context.cssChain().get(1).toString().endsWith("level1.css"));
            assertTrue(context.cssChain().get(2).toString().endsWith("level2.css"));

            assertEquals("level2", context.vars().get("level")); // Final override
            assertEquals("original", context.vars().get("keep")); // Preserved from root
            assertEquals("present", context.vars().get("l1var")); // From intermediate level
        }
    }

    @Nested
    @DisplayName("Layout and targets handling")
    class LayoutAndTargetsHandling {

        @Test
        void should_resolve_layout_path_relative_to_yaml_directory(@TempDir Path tempDir) throws IOException {
            // Create book root first
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);

            // Create subfolder with layout config
            Path subfolder = tempDir.resolve("templates");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("paperband.yaml"), """
                layout: "custom.peb"
                """);

            Path mdFile = subfolder.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertNotNull(context.layout());
            assertTrue(context.layout().toString().endsWith("templates/custom.peb"));
        }

        @Test
        void should_use_last_non_empty_targets_list(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                targets: ["pdf-a4", "web"]
                """);

            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("paperband.yaml"), """
                targets: ["pdf-letter"]
                """);

            Path mdFile = subfolder.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            // The book root targets should remain unchanged - cascade targets aren't stored in BookConfig
            assertEquals(List.of("pdf-a4", "web"), context.book().targets());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        void should_throw_exception_for_malformed_yaml(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                malformed: [
                  - incomplete
                """);

            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            assertThrows(ConfigParseException.class, () ->
                loader.load(mdFile, "pdf", "A4"));
        }

        @Test
        void should_throw_exception_for_non_map_yaml(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                - just
                - a
                - list
                """);

            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            ConfigParseException exception = assertThrows(ConfigParseException.class, () ->
                loader.load(mdFile, "pdf", "A4"));

            assertTrue(exception.getMessage().contains("top level must be a YAML mapping"));
        }

        @Test
        void should_throw_exception_for_axis_without_name(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                axes:
                  - title: "Missing Name Axis"
                    values: []
                """);

            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            ConfigParseException exception = assertThrows(ConfigParseException.class, () ->
                loader.load(mdFile, "pdf", "A4"));

            assertTrue(exception.getMessage().contains("axis missing required 'name'"));
        }
    }

    @Nested
    @DisplayName("Built-in vars integration")
    class BuiltInVarsIntegration {

        @Test
        void should_include_built_in_vars(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            // Should contain all built-in vars
            assertNotNull(context.vars().get("build_date"));
            assertNotNull(context.vars().get("build_year"));
            assertTrue(context.vars().get("build_date").toString().matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Test
        void should_allow_user_vars_to_override_built_in_vars(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                vars:
                  build_date: "2025-01-01"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertEquals("2025-01-01", context.vars().get("build_date"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void should_handle_empty_yaml_file(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), "");
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertNull(context.book().title());
            assertTrue(context.cssChain().isEmpty());
            assertEquals(tempDir, context.book().bookRoot());
        }

        @Test
        void should_handle_yaml_with_only_comments(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                # This is a comment
                # Another comment
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            assertNull(context.book().title());
        }
    }

    @Nested
    @DisplayName("Caller-supplied margins")
    class CallerSuppliedMargins {

        /** The full-bleed case the Maven plugin's {@code <margins>0</margins>} asks for. */
        @Test
        void should_replace_the_page_presets_margins(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            RenderContext ctx = new ConfigLoader().load(
                    mdFile, "pdf", "A4", Margins.parse("0"));

            double[] mm = ctx.pageSpec().marginsMm();
            assertArrayEquals(new double[] {0, 0, 0, 0}, mm, 0.01,
                    "A4's own 20mm margins are replaced, so the render is full-bleed");
            assertEquals(297.0, ctx.pageSpec().size().unit()
                            .toMillimetres(ctx.pageSpec().size().height()), 0.01,
                    "and only the margins — the size preset is untouched");
        }

        @Test
        void should_lose_to_the_books_own_page_block(@TempDir Path tempDir) throws IOException {
            // Same precedence `size` has: the caller seeds the base, yaml wins.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                vars:
                  page:
                    margins: { top: 12, right: 9, bottom: 12, left: 9 }
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            RenderContext ctx = new ConfigLoader().load(
                    mdFile, "pdf", "A4", Margins.parse("0"));

            assertArrayEquals(new double[] {12, 9, 12, 9}, ctx.pageSpec().marginsMm(), 0.01,
                    "the book's own page block overrides what the caller passed");
        }

        @Test
        void should_keep_the_presets_margins_when_none_are_passed(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            RenderContext ctx = new ConfigLoader().load(mdFile, "pdf", "A4", null);

            assertArrayEquals(new double[] {20, 20, 20, 20}, ctx.pageSpec().marginsMm(), 0.01,
                    "A4's standard margins");
        }
    }

    @Nested
    @DisplayName("Declared book root")
    class DeclaredBookRoot {

        /**
         * The case that has no yaml at all: a book whose structure and config
         * are declared in a build tool. Without a declared root the loader
         * infers one from the card's own parent directory, so every card lands
         * in a different "book".
         */
        @Test
        void should_pin_the_book_root_when_no_yaml_exists(@TempDir Path tempDir) throws IOException {
            Path card = tempDir.resolve("setup").resolve("install.md");
            Files.createDirectories(card.getParent());
            Files.writeString(card, "# Install\n");

            RenderContext inferred = new ConfigLoader().load(card, "pdf", "A4");
            assertEquals(tempDir.resolve("setup"), inferred.book().bookRoot(),
                    "inference can only guess the card's own folder");

            RenderContext declared = new ConfigLoader().load(card, "pdf", "A4", null, tempDir);
            assertEquals(tempDir, declared.book().bookRoot(),
                    "a declared root is the book root, yaml or no yaml");
        }

        @Test
        void should_stop_the_cascade_at_the_declared_root(@TempDir Path tempDir) throws IOException {
            // A yaml above the declared root belongs to somebody else's book.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Outer Book"
                vars:
                  outer: "yes"
                """);
            Path inner = tempDir.resolve("inner");
            Files.createDirectories(inner.resolve("cards"));
            createYamlFile(inner.resolve("paperband.yaml"), """
                title: "Inner Book"
                """);
            Path card = inner.resolve("cards").resolve("a.md");
            Files.writeString(card, "# A\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "A4", null, inner);

            assertEquals(inner, ctx.book().bookRoot());
            assertEquals("Inner Book", ctx.book().title());
            assertNull(ctx.vars().get("outer"), "the outer book's vars stay out of this one");
        }

        @Test
        void should_seed_declared_vars_where_the_books_own_vars_go(@TempDir Path tempDir) throws IOException {
            // Book-level, so every card sees them, and a folder can still win.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Book"
                vars:
                  author: "Yaml Author"
                """);
            Path folder = tempDir.resolve("part");
            Files.createDirectories(folder);
            createYamlFile(folder.resolve("paperband.yaml"), """
                vars:
                  subtitle: "Folder Subtitle"
                """);
            Path card = folder.resolve("a.md");
            Files.writeString(card, "# A\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "A4", null, tempDir,
                    Map.of("author", "Declared Author", "series", "Declared Series"));

            assertEquals("Declared Author", ctx.vars().get("author"),
                    "a declared var wins over the book yaml's");
            assertEquals("Declared Series", ctx.vars().get("series"));
            assertEquals("Folder Subtitle", ctx.vars().get("subtitle"),
                    "and the folder cascade still applies on top");
        }

        @Test
        void should_reach_a_card_even_with_no_yaml_anywhere(@TempDir Path tempDir) throws IOException {
            Path card = tempDir.resolve("a.md");
            Files.writeString(card, "# A\n");

            RenderContext ctx = new ConfigLoader().load(card, "pdf", "A4", null, tempDir,
                    Map.of("author", "Declared Author"));

            assertEquals(tempDir, ctx.book().bookRoot());
            assertEquals("Declared Author", ctx.vars().get("author"));
        }
    }

    @Nested
    @DisplayName("Declared sections")
    class DeclaredSections {

        @Test
        void should_parse_sections_with_titles_and_folders(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                title: "Test Book"
                sections:
                  - title: "Foundations"
                    folders:
                      - 01-getting-started
                      - 02-authoring
                  - title: "Reference"
                    folders:
                      - 03-configuration
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            List<Section> sections = new ConfigLoader().load(mdFile, "pdf", "A4").book().sections();

            assertEquals(2, sections.size());
            assertEquals("Foundations", sections.get(0).title());
            assertEquals(List.of("01-getting-started", "02-authoring"), sections.get(0).folders());
            assertEquals("Reference", sections.get(1).title());
            assertEquals(List.of("03-configuration"), sections.get(1).folders());
        }

        @Test
        void should_default_section_id_to_a_slug_of_the_title(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Getting Started & Beyond"
                    folders: [intro]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            List<Section> sections = new ConfigLoader().load(mdFile, "pdf", "A4").book().sections();
            assertEquals("getting-started-beyond", sections.get(0).id());
        }

        @Test
        void should_honour_an_explicit_section_id(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - id: ref
                    title: "Reference Material"
                    folders: [config]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            List<Section> sections = new ConfigLoader().load(mdFile, "pdf", "A4").book().sections();
            assertEquals("ref", sections.get(0).id());
        }

        @Test
        void should_resolve_a_section_landing_template_preset(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Quiet Part"
                    folders: [quiet]
                    landing:
                      template: minimal
                  - title: "Loud Part"
                    folders: [loud]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            List<Section> sections = new ConfigLoader().load(mdFile, "pdf", "A4").book().sections();
            assertEquals("site-section-minimal", sections.get(0).landingTemplate());
            assertNull(sections.get(1).landingTemplate(),
                    "no landing key falls through to the book-wide default later");
        }

        @Test
        void should_let_a_section_opt_out_of_its_landing_page(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Fronted"
                    folders: [fronted]
                  - title: "Quiet"
                    folders: [quiet]
                    landing: false
                  - title: "Explicit"
                    folders: [explicit]
                    landing: true
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            List<Section> sections = new ConfigLoader().load(mdFile, "pdf", "A4").book().sections();
            assertTrue(sections.get(0).landingPage(), "on by default");
            assertFalse(sections.get(1).landingPage(),
                    "landing: false keeps the grouping but skips the section's page");
            assertTrue(sections.get(2).landingPage(), "landing: true is the explicit default");
        }

        @Test
        void should_reject_a_bare_scalar_landing_value(@TempDir Path tempDir) throws IOException {
            // `landing: minimal` was silently ignored before — almost certainly
            // a template the author meant to declare.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Oops"
                    folders: [oops]
                    landing: minimal
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("landing"), e.getMessage());
            assertTrue(e.getMessage().contains("template"),
                    "the message shows the shape the author probably wanted: " + e.getMessage());
        }

        @Test
        void should_default_to_no_sections_when_the_key_is_absent(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), "title: \"Plain Book\"\n");
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            assertEquals(List.of(), new ConfigLoader().load(mdFile, "pdf", "A4").book().sections());
        }

        @Test
        void should_reject_duplicate_section_ids(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Same Name"
                    folders: [a]
                  - title: "Same Name"
                    folders: [b]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("duplicate section id"), e.getMessage());
        }

        @Test
        void should_reject_a_folder_claimed_by_two_sections(@TempDir Path tempDir) throws IOException {
            // Ambiguous: the folder's cards would have no single group to report.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "One"
                    folders: [shared]
                  - title: "Two"
                    folders: [shared]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("claimed by both section"), e.getMessage());
        }

        @Test
        void should_reject_a_section_with_no_folders(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - title: "Empty"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("declares no 'folders'"), e.getMessage());
        }

        @Test
        void should_reject_a_section_with_neither_title_nor_id(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  - folders: [orphan]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("neither a usable 'title' nor an 'id'"),
                    e.getMessage());
        }

        @Test
        void should_reject_a_scalar_sections_key(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), "sections: nonsense\n");
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("'sections' must be a list"), e.getMessage());
        }

        @Test
        void should_reject_a_non_list_declare_key(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  declare: nonsense
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("'sections' must declare a list"), e.getMessage());
        }

        @Test
        void should_parse_the_map_form_with_landing_default_and_declared_sections(
                @TempDir Path tempDir) throws IOException {
            // The map form is for books that need both at once: the book-wide
            // landing default AND declared sections.
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                sections:
                  landing:
                    template: minimal
                  declare:
                    - title: "Foundations"
                      folders: [intro]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            var book = new ConfigLoader().load(mdFile, "pdf", "A4").book();
            assertEquals("site-section-minimal", book.sectionLandingTemplate());
            assertEquals(1, book.sections().size());
            assertEquals("foundations", book.sections().get(0).id());
        }

        @Test
        void should_reject_the_old_parts_key_by_naming_the_rename(@TempDir Path tempDir)
                throws IOException {
            createYamlFile(tempDir.resolve("paperband.yaml"), """
                parts:
                  - title: "Foundations"
                    folders: [intro]
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigParseException e = assertThrows(ConfigParseException.class,
                    () -> new ConfigLoader().load(mdFile, "pdf", "A4"));
            assertTrue(e.getMessage().contains("renamed"), e.getMessage());
            assertTrue(e.getMessage().contains("sections"), e.getMessage());
        }
    }

    // Helper method to create YAML files
    private void createYamlFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    // ---- split geography: home vs content root ----

    @Test
    void homeYamlSuppliesTheBookConfig_withPathsResolvedAgainstHome(@TempDir Path tempDir)
            throws IOException {
        Path home = Files.createDirectories(tempDir.resolve("src/main/paperband"));
        Files.createDirectories(home.resolve("styles"));
        Files.writeString(home.resolve("styles/book.css"), "body{}");
        createYamlFile(home.resolve("paperband.yaml"), """
                title: "Scattered Book"
                css: [styles/book.css]
                """);
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        Path card = docs.resolve("a.md");
        Files.createFile(card);

        var ctx = new ConfigLoader().load(card, "pdf", "A4", null, docs, home, Map.of());

        assertEquals("Scattered Book", ctx.book().title(), "book config comes from home's yaml");
        assertEquals(docs.toAbsolutePath().normalize(), ctx.book().bookRoot(),
                "the model's bookRoot stays the content root — ids and sections are content concerns");
        assertTrue(ctx.cssChain().get(0).toString().contains("src/main/paperband"),
                "css paths resolve against home, not the content root: " + ctx.cssChain());
    }

    @Test
    void perFolderCascadeStillAppliesBetweenCardAndContentRoot(@TempDir Path tempDir)
            throws IOException {
        Path home = Files.createDirectories(tempDir.resolve("src/main/paperband"));
        createYamlFile(home.resolve("paperband.yaml"), """
                title: "Scattered Book"
                vars: { tone: default }
                """);
        Path docs = Files.createDirectories(tempDir.resolve("docs/deep"));
        createYamlFile(docs.resolve("paperband.yaml"), "vars: { tone: folder }\n");
        Path card = docs.resolve("a.md");
        Files.createFile(card);

        var ctx = new ConfigLoader().load(card, "pdf", "A4", null,
                tempDir.resolve("docs"), home, Map.of());

        assertEquals("folder", ctx.vars().get("tone"),
                "home supplies the book level; folder yamls between card and content root still win");
    }

    @Test
    void withoutAHomeYaml_theContentRootsOwnStillApplies(@TempDir Path tempDir) throws IOException {
        Path home = Files.createDirectories(tempDir.resolve("src/main/paperband"));  // no yaml
        Path docs = Files.createDirectories(tempDir.resolve("docs"));
        createYamlFile(docs.resolve("paperband.yaml"), "title: \"Self-Contained\"\n");
        Path card = docs.resolve("a.md");
        Files.createFile(card);

        var ctx = new ConfigLoader().load(card, "pdf", "A4", null, docs, home, Map.of());

        assertEquals("Self-Contained", ctx.book().title(),
                "an empty home never shadows a self-contained book's own yaml");
    }
}
