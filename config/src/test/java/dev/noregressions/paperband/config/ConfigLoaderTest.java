package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.RenderContext;
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
            assertTrue(context.vars().isEmpty(), "No vars when no YAML files exist");
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
        void should_parse_section_landing_template_default(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Test Book"
                sections:
                  landing:
                    template: "templates/my-section.html"
                """);
            Path mdFile = tempDir.resolve("test.md");
            Files.createFile(mdFile);

            ConfigLoader loader = new ConfigLoader();
            RenderContext context = loader.load(mdFile, "pdf", "A4");

            // Resolved to the bare Pebble template name (extension stripped),
            // ready for the engine's template loader chain to find by name.
            assertEquals("my-section", context.book().sectionLandingTemplate());
        }

        @Test
        void should_resolve_named_preset_for_section_landing_template(@TempDir Path tempDir) throws IOException {
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Test Book"
                css:
                  - "global.css"
                """);

            // Subfolder config
            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Test Book"
                vars:
                  author: "Original Author"
                  version: "1.0"
                """);

            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(subfolder.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Multi-Level Book"
                css: ["root.css"]
                vars:
                  level: "root"
                  keep: "original"
                """);

            // Level 1
            Path level1 = tempDir.resolve("level1");
            Files.createDirectories(level1);
            createYamlFile(level1.resolve("pagewright.yaml"), """
                css: ["level1.css"]
                vars:
                  level: "level1"
                  l1var: "present"
                """);

            // Level 2
            Path level2 = level1.resolve("level2");
            Files.createDirectories(level2);
            createYamlFile(level2.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Test Book"
                """);

            // Create subfolder with layout config
            Path subfolder = tempDir.resolve("templates");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
                title: "Test Book"
                targets: ["pdf-a4", "web"]
                """);

            Path subfolder = tempDir.resolve("subfolder");
            Files.createDirectories(subfolder);
            createYamlFile(subfolder.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), "");
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
            createYamlFile(tempDir.resolve("pagewright.yaml"), """
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

    // Helper method to create YAML files
    private void createYamlFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }
}