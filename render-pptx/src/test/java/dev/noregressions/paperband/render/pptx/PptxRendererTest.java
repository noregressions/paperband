package dev.noregressions.paperband.render.pptx;

import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.render.Orientation;
import dev.noregressions.paperband.render.PageSize;
import dev.noregressions.paperband.render.PageSpec;
import dev.noregressions.paperband.render.PdfMetadata;
import dev.noregressions.paperband.render.RendererRegistry;
import dev.noregressions.paperband.render.Unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderer's contract with the build: its name, its ServiceLoader
 * registration, and the geometry it derives from a {@link PageSpec}.
 *
 * <p>Nothing here launches a browser. The rendering path is covered by
 * {@link DeckWriterTest} downstream of the harvest, and end to end by the guide
 * build.
 */
@DisplayName("PptxRenderer")
class PptxRendererTest {

    private static HtmlInput input(PageSpec spec) {
        return new HtmlInput("<html><head></head><body></body></html>",
                URI.create("file:///tmp/"), spec, PdfMetadata.empty());
    }

    @Nested
    @DisplayName("SPI contract")
    class Spi {

        @Test
        void is_registered_for_service_discovery() {
            // The plugin resolves renderers by name through ServiceLoader, so a
            // missing or misspelt META-INF/services entry makes the renderer
            // invisible with no error beyond "Unknown renderer: pptx".
            Optional<HtmlToPdfRenderer> found = RendererRegistry.discover().get("pptx");
            assertAll(
                    () -> assertTrue(found.isPresent(),
                            "pptx should be discoverable: " + RendererRegistry.discover().all()
                                    .stream().map(HtmlToPdfRenderer::name).toList()),
                    () -> assertTrue(found.orElseThrow() instanceof PptxRenderer));
        }

        @Test
        void the_name_is_the_renderer_parameter_users_type() {
            assertEquals("pptx", new PptxRenderer().name());
        }

        @Test
        void does_not_claim_to_produce_a_pdf() {
            // This is what stops BookBuild running its four PDFBox post-passes
            // over a .pptx and failing the build after a successful render.
            assertFalse(new PptxRenderer().producesPdf());
        }

        @Test
        void every_other_renderer_still_claims_to_produce_a_pdf() {
            // producesPdf() defaults to true so that renderers written before
            // it existed keep their behaviour.
            RendererRegistry.discover().all().stream()
                    .filter(r -> !(r instanceof PptxRenderer))
                    .forEach(r -> assertTrue(r.producesPdf(),
                            r.name() + " should still take the PDF post-passes"));
        }

        @Test
        void the_description_names_its_requirements() {
            // A renderer that silently needs Chromium and a particular theme
            // should say so where `mvn paperband:renderers` will print it.
            String d = new PptxRenderer().description();
            assertAll(
                    () -> assertTrue(d.contains("Chromium"), d),
                    () -> assertTrue(d.toLowerCase().contains("theme"), d));
        }
    }

    @Nested
    @DisplayName("Page geometry")
    class Geometry {

        @Test
        void the_16x9_preset_resolves_to_powerpoints_slide_in_css_px() {
            // 13.333x7.5in at 96 px/in, which becomes 960x540pt.
            HtmlInput in = input(PageSpec.slide16x9());
            assertAll(
                    () -> assertEquals(1280, Math.round(PptxRenderer.pxOf(in, true))),
                    () -> assertEquals(720, Math.round(PptxRenderer.pxOf(in, false))));
        }

        @Test
        void a_landscape_page_swaps_its_dimensions() {
            PageSpec portrait = new PageSpec(
                    PageSize.of(100, 200, Unit.MM), Margins.uniform(0, Unit.MM),
                    Orientation.PORTRAIT);
            PageSpec landscape = new PageSpec(
                    PageSize.of(100, 200, Unit.MM), Margins.uniform(0, Unit.MM),
                    Orientation.LANDSCAPE);

            assertAll(
                    () -> assertEquals(Math.round(PptxRenderer.pxOf(input(portrait), true)),
                            Math.round(PptxRenderer.pxOf(input(landscape), false))),
                    () -> assertEquals(Math.round(PptxRenderer.pxOf(input(portrait), false)),
                            Math.round(PptxRenderer.pxOf(input(landscape), true))));
        }

        @Test
        void margins_do_not_shrink_the_slide() {
            // A slide is full bleed: the deck theme supplies its own inset, so
            // the exported page must stay the whole sheet even if a book
            // declares margins. Using the content box here would crop it.
            PageSpec bleed = PageSpec.slide16x9();
            PageSpec inset = new PageSpec(PageSize.SLIDE_16X9,
                    Margins.uniform(20, Unit.MM), Orientation.PORTRAIT);

            assertEquals(Math.round(PptxRenderer.pxOf(input(bleed), true)),
                    Math.round(PptxRenderer.pxOf(input(inset), true)));
        }
    }
}
