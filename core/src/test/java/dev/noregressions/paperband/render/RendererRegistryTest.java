package dev.noregressions.paperband.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RendererRegistryTest {

    @Nested
    @DisplayName("Explicit construction")
    class ExplicitConstruction {

        @Test
        void should_create_empty_registry_when_no_renderers_provided() {
            RendererRegistry registry = RendererRegistry.of();

            assertTrue(registry.isEmpty());
            assertEquals(0, registry.size());
            assertTrue(registry.all().isEmpty());
            assertTrue(registry.available().isEmpty());
        }

        @Test
        void should_register_single_renderer_correctly() {
            MockRenderer renderer = new MockRenderer("test", true);
            RendererRegistry registry = RendererRegistry.of(renderer);

            assertFalse(registry.isEmpty());
            assertEquals(1, registry.size());
            assertEquals(List.of(renderer), registry.all());
            assertEquals(List.of(renderer), registry.available());
        }

        @Test
        void should_register_multiple_renderers_in_order() {
            MockRenderer renderer1 = new MockRenderer("first", true);
            MockRenderer renderer2 = new MockRenderer("second", true);
            RendererRegistry registry = RendererRegistry.of(renderer1, renderer2);

            assertEquals(2, registry.size());
            assertEquals(List.of(renderer1, renderer2), registry.all());
        }

        @Test
        void should_handle_duplicate_names_by_keeping_last() {
            MockRenderer renderer1 = new MockRenderer("same", true);
            MockRenderer renderer2 = new MockRenderer("same", true);
            RendererRegistry registry = RendererRegistry.of(renderer1, renderer2);

            assertEquals(1, registry.size());
            assertEquals(List.of(renderer2), registry.all());
        }
    }

    @Nested
    @DisplayName("Renderer lookup")
    class RendererLookup {

        @Test
        void should_find_existing_renderer_by_exact_name() {
            MockRenderer renderer = new MockRenderer("exact-name", true);
            RendererRegistry registry = RendererRegistry.of(renderer);

            Optional<HtmlToPdfRenderer> found = registry.get("exact-name");
            assertTrue(found.isPresent());
            assertEquals(renderer, found.get());
        }

        @Test
        void should_return_empty_for_unknown_renderer_name() {
            MockRenderer renderer = new MockRenderer("known", true);
            RendererRegistry registry = RendererRegistry.of(renderer);

            Optional<HtmlToPdfRenderer> found = registry.get("unknown");
            assertTrue(found.isEmpty());
        }

        @Test
        void should_be_case_sensitive_for_renderer_names() {
            MockRenderer renderer = new MockRenderer("lowercase", true);
            RendererRegistry registry = RendererRegistry.of(renderer);

            assertTrue(registry.get("lowercase").isPresent());
            assertTrue(registry.get("LOWERCASE").isEmpty());
            assertTrue(registry.get("Lowercase").isEmpty());
        }
    }

    @Nested
    @DisplayName("Availability filtering")
    class AvailabilityFiltering {

        @Test
        void should_filter_available_renderers_correctly() {
            MockRenderer available = new MockRenderer("available", true);
            MockRenderer unavailable = new MockRenderer("unavailable", false);
            RendererRegistry registry = RendererRegistry.of(available, unavailable);

            assertEquals(List.of(available, unavailable), registry.all());
            assertEquals(List.of(available), registry.available());
        }

        @Test
        void should_return_empty_available_list_when_all_unavailable() {
            MockRenderer unavailable1 = new MockRenderer("first", false);
            MockRenderer unavailable2 = new MockRenderer("second", false);
            RendererRegistry registry = RendererRegistry.of(unavailable1, unavailable2);

            assertEquals(2, registry.all().size());
            assertTrue(registry.available().isEmpty());
        }

        @Test
        void should_return_all_renderers_when_all_available() {
            MockRenderer available1 = new MockRenderer("first", true);
            MockRenderer available2 = new MockRenderer("second", true);
            RendererRegistry registry = RendererRegistry.of(available1, available2);

            assertEquals(registry.all(), registry.available());
        }
    }

    @Nested
    @DisplayName("Service discovery")
    class ServiceDiscovery {

        @Test
        void should_create_registry_from_discovery() {
            // Test the discovery method creates a registry (may be empty depending on classpath)
            RendererRegistry registry = RendererRegistry.discover();

            assertNotNull(registry);
            // Size could be 0 if no services are registered, which is fine for this test
            assertTrue(registry.size() >= 0);
        }

        @Test
        void should_create_registry_from_discovery_with_classloader() {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            RendererRegistry registry = RendererRegistry.discover(loader);

            assertNotNull(registry);
            assertTrue(registry.size() >= 0);
        }
    }

    /**
     * Mock implementation of HtmlToPdfRenderer for testing.
     */
    private static class MockRenderer implements HtmlToPdfRenderer {
        private final String name;
        private final boolean available;

        public MockRenderer(String name, boolean available) {
            this.name = name;
            this.available = available;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Mock renderer: " + name;
        }

        @Override
        public void render(HtmlInput input, Path output) throws PdfRenderException {
            // No-op for testing
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }
}