package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.Watermark;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("The <watermark> block")
class WatermarkConfigTest {

    @Test
    void the_bare_string_shorthand_sets_the_text() throws Exception {
        // <watermark>DRAFT</watermark> — Maven calls set() for an element
        // bound to a bean that carries text rather than children.
        WatermarkConfig block = new WatermarkConfig();
        block.set("DRAFT");

        assertTrue(block.hasSubject());
        assertEquals("DRAFT", block.subject().text());
    }

    @Test
    void a_block_declares_its_subject_without_its_knobs() throws Exception {
        WatermarkConfig block = declare("text", "REVIEW COPY", "color", "#aa0000", "angle", -45f);

        // The subject is bare; the knobs travel separately so that a block, the
        // flat parameters and the yaml can be combined in one fixed order.
        assertEquals("REVIEW COPY", block.subject().text());
        assertEquals(Watermark.DEFAULT_COLOR, block.subject().color());
        assertEquals("#aa0000", block.knobs().color());
        assertEquals(-45f, block.knobs().angle());
    }

    @Test
    void a_block_with_only_knobs_declares_no_watermark() throws Exception {
        // <watermark><opacity>0.3</opacity></watermark> retunes a stamp the
        // book's yaml declared; it does not create one.
        WatermarkConfig block = declare("opacity", 0.3f);

        assertFalse(block.hasSubject());
        assertNull(block.subject());
        assertEquals(0.3f, block.knobs().opacity());
    }

    @Test
    void a_block_declaring_both_a_text_and_an_image_is_refused() throws Exception {
        WatermarkConfig block = declare("text", "DRAFT", "image", "logo.png");

        MojoExecutionException e = assertThrows(MojoExecutionException.class, block::subject);
        assertTrue(e.getMessage().contains("pick one"), e.getMessage());
    }

    @Test
    void an_unknown_pages_value_fails_the_block() throws Exception {
        WatermarkConfig block = declare("text", "DRAFT", "pages", "last");

        MojoExecutionException e = assertThrows(MojoExecutionException.class, block::knobs);
        assertTrue(e.getMessage().contains("except-cover"), e.getMessage());
    }

    @Test
    void the_flat_parameters_win_over_the_block() throws Exception {
        // -Dpaperband.watermark has to be able to restamp a book whose POM
        // declares a block, the way the block restamps one whose yaml does.
        WatermarkConfig block = declare("text", "FROM BLOCK");

        assertEquals("FROM CLI", Watermarks.base(block, "FROM CLI", null).text());
        assertEquals("FROM BLOCK", Watermarks.base(block, null, null).text());
        assertNull(Watermarks.base(null, null, null));
    }

    @Test
    void the_block_supplies_knobs_and_the_flat_parameters_override_them() throws Exception {
        WatermarkConfig block = declare("color", "#aa0000", "angle", -45f, "tile", true);

        Watermark.Overrides merged = Watermarks.overrides(block,
                "#00ff00", null, null, null, null, null, null, null, null, null, null);

        assertEquals("#00ff00", merged.color(), "the flat parameter wins");
        assertEquals(-45f, merged.angle(), "an untouched block knob survives");
        assertEquals(Boolean.TRUE, merged.tile());
    }

    @Test
    void a_block_and_the_flat_parameters_compose_onto_a_yaml_stamp() throws Exception {
        // The three-channel case: the book says DRAFT, the POM restyles it, the
        // command line retunes that.
        Watermark fromYaml = Watermark.fromYaml("DRAFT");
        WatermarkConfig block = declare("color", "#aa0000", "tile", true);

        Watermark result = fromYaml.withOverrides(Watermarks.overrides(block,
                null, 0.4f, null, null, null, null, null, null, null, null, null));

        assertEquals("DRAFT", result.text());
        assertEquals("#aa0000", result.color());
        assertTrue(result.tile());
        assertEquals(0.4f, result.opacity());
    }

    /** Fill a block the way Maven's configurator does — straight onto the fields. */
    private static WatermarkConfig declare(Object... keyValues) throws Exception {
        WatermarkConfig block = new WatermarkConfig();
        for (int i = 0; i < keyValues.length; i += 2) {
            Field f = WatermarkConfig.class.getDeclaredField((String) keyValues[i]);
            f.setAccessible(true);
            f.set(block, keyValues[i + 1]);
        }
        return block;
    }
}
