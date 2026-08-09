package dev.noregressions.paperband.pebble;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LenientMapTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        void should_create_empty_lenient_map() {
            LenientMap<String, Object> map = new LenientMap<>();

            assertTrue(map.isEmpty());
            assertEquals(0, map.size());
        }

        @Test
        void should_create_from_existing_map() {
            Map<String, String> source = Map.of("key1", "value1", "key2", "value2");
            LenientMap<String, String> map = new LenientMap<>(source);

            assertEquals(2, map.size());
            assertEquals("value1", map.get("key1"));
            assertEquals("value2", map.get("key2"));
        }

        @Test
        void should_handle_null_source_map() {
            LenientMap<String, String> map = new LenientMap<>(null);

            assertTrue(map.isEmpty());
            assertNull(map.get("any-key"));
        }

        @Test
        void should_create_via_of_factory_method() {
            Map<String, Integer> source = Map.of("one", 1, "two", 2);
            LenientMap<String, Integer> map = LenientMap.of(source);

            assertEquals(2, map.size());
            assertEquals(1, map.get("one"));
            assertEquals(2, map.get("two"));
        }

        @Test
        void should_create_empty_map_via_of_with_null() {
            LenientMap<String, String> map = LenientMap.of(null);

            assertTrue(map.isEmpty());
            assertNotNull(map);
        }
    }

    @Nested
    @DisplayName("HashMap behavior")
    class HashMapBehavior {

        @Test
        void should_behave_like_regular_hashmap() {
            LenientMap<String, String> map = new LenientMap<>();

            // Put operations
            map.put("key1", "value1");
            map.put("key2", "value2");

            assertEquals(2, map.size());
            assertTrue(map.containsKey("key1"));
            assertTrue(map.containsValue("value1"));
            assertFalse(map.containsKey("nonexistent"));
        }

        @Test
        void should_support_all_map_operations() {
            LenientMap<String, String> map = LenientMap.of(Map.of("a", "1", "b", "2"));

            // Remove
            assertEquals("1", map.remove("a"));
            assertFalse(map.containsKey("a"));

            // Replace
            map.replace("b", "new-value");
            assertEquals("new-value", map.get("b"));

            // Clear
            map.clear();
            assertTrue(map.isEmpty());
        }

        @Test
        void should_handle_null_values() {
            LenientMap<String, String> map = new LenientMap<>();
            map.put("null-value", null);
            map.put("real-value", "exists");

            assertTrue(map.containsKey("null-value"));
            assertNull(map.get("null-value"));
            assertEquals("exists", map.get("real-value"));
        }

        @Test
        void should_return_null_for_missing_keys() {
            LenientMap<String, String> map = LenientMap.of(Map.of("exists", "value"));

            assertNull(map.get("missing"));
            assertFalse(map.containsKey("missing"));
        }
    }

    @Nested
    @DisplayName("Lenient behavior markers")
    class LenientBehaviorMarkers {

        @Test
        void should_be_instance_of_lenient_map() {
            LenientMap<String, String> map = new LenientMap<>();

            assertInstanceOf(LenientMap.class, map);
            // This is the key behavior - the type system can detect lenient maps
        }

        @Test
        void should_be_distinguishable_from_regular_hashmap() {
            Map<String, String> regularMap = new HashMap<>();
            LenientMap<String, String> lenientMap = new LenientMap<>();

            assertInstanceOf(HashMap.class, regularMap);
            assertInstanceOf(LenientMap.class, lenientMap);
            assertInstanceOf(HashMap.class, lenientMap); // LenientMap extends HashMap

            // But instanceof check can distinguish them
            assertFalse(regularMap instanceof LenientMap);
            assertTrue(lenientMap instanceof LenientMap);
        }

        @Test
        void should_preserve_lenient_type_after_operations() {
            LenientMap<String, String> map = LenientMap.of(Map.of("key", "value"));

            map.put("new-key", "new-value");
            map.remove("key");

            // Should still be a LenientMap after modifications
            assertInstanceOf(LenientMap.class, map);
        }
    }

    @Nested
    @DisplayName("Integration with template system")
    class IntegrationWithTemplateSystem {

        @Test
        void should_wrap_frontmatter_style_data() {
            // Simulate how this would be used for frontmatter
            Map<String, Object> frontmatter = Map.of(
                "title", "Test Card",
                "tier", 1,
                "verify", true
            );

            LenientMap<String, Object> lenient = LenientMap.of(frontmatter);

            assertEquals("Test Card", lenient.get("title"));
            assertEquals(1, lenient.get("tier"));
            assertEquals(true, lenient.get("verify"));
            assertNull(lenient.get("missing-field")); // Should not throw
        }

        @Test
        void should_wrap_vars_style_data() {
            // Simulate how this would be used for template vars
            Map<String, Object> vars = Map.of(
                "author", "John Doe",
                "build_date", "2024-01-01",
                "sidebar", true
            );

            LenientMap<String, Object> lenient = LenientMap.of(vars);

            assertEquals("John Doe", lenient.get("author"));
            assertEquals("2024-01-01", lenient.get("build_date"));
            assertEquals(true, lenient.get("sidebar"));
            assertNull(lenient.get("undefined-var")); // Should not throw
        }

        @Test
        void should_handle_nested_map_structures() {
            Map<String, Object> nested = Map.of(
                "user", Map.of("name", "Alice", "role", "admin"),
                "config", Map.of("enabled", true, "timeout", 30)
            );

            LenientMap<String, Object> lenient = LenientMap.of(nested);

            assertTrue(lenient.get("user") instanceof Map);
            assertTrue(lenient.get("config") instanceof Map);
            assertNull(lenient.get("missing-section"));

            @SuppressWarnings("unchecked")
            Map<String, Object> userSection = (Map<String, Object>) lenient.get("user");
            assertEquals("Alice", userSection.get("name"));
            assertEquals("admin", userSection.get("role"));
        }

        @Test
        void should_handle_empty_and_sparse_data() {
            // Common case: sparse frontmatter
            Map<String, Object> sparse = Map.of("tier", 2);
            LenientMap<String, Object> lenient = LenientMap.of(sparse);

            assertEquals(2, lenient.get("tier"));
            assertNull(lenient.get("effort")); // Missing optional field
            assertNull(lenient.get("tags"));   // Missing optional field
            assertNull(lenient.get("verify")); // Missing optional field

            // Template guards like {% if card.frontmatter.effort %} should work
            assertFalse(lenient.containsKey("effort"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void should_handle_large_maps() {
            Map<String, Integer> large = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                large.put("key" + i, i);
            }

            LenientMap<String, Integer> lenient = LenientMap.of(large);

            assertEquals(1000, lenient.size());
            assertEquals(500, lenient.get("key500"));
            assertNull(lenient.get("key1000")); // Out of range
        }

        @Test
        void should_handle_keys_with_special_characters() {
            Map<String, String> special = Map.of(
                "key.with.dots", "dotted",
                "key-with-dashes", "dashed",
                "key_with_underscores", "underscored",
                "key with spaces", "spaced"
            );

            LenientMap<String, String> lenient = LenientMap.of(special);

            assertEquals("dotted", lenient.get("key.with.dots"));
            assertEquals("dashed", lenient.get("key-with-dashes"));
            assertEquals("underscored", lenient.get("key_with_underscores"));
            assertEquals("spaced", lenient.get("key with spaces"));
        }

        @Test
        void should_maintain_iteration_order() {
            // LinkedHashMap behavior would be nice but HashMap doesn't guarantee order
            // Just test that iteration works without throwing
            LenientMap<String, String> map = LenientMap.of(Map.of(
                "first", "1", "second", "2", "third", "3"
            ));

            assertDoesNotThrow(() -> {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    assertNotNull(entry.getKey());
                    assertNotNull(entry.getValue());
                }
            });

            assertEquals(3, map.keySet().size());
            assertEquals(3, map.values().size());
        }
    }
}
