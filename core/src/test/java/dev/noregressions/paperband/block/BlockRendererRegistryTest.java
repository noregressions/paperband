package dev.noregressions.paperband.block;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's job is to answer "who renders ```x?" the same way on every
 * machine — which means refusing the classpaths where it couldn't.
 */
class BlockRendererRegistryTest {

    /** A renderer that claims what it's told to and renders nothing interesting. */
    private record Fake(String name, Set<String> types, boolean available) implements BlockRenderer {
        @Override public String description() { return "test"; }
        @Override public boolean isAvailable() { return available; }
        @Override public String unavailableReason() { return available ? null : "no backend here"; }
        @Override public String render(BlockRequest request) { return "<p>" + name + "</p>"; }
    }

    private static Fake fake(String name, String... types) {
        return new Fake(name, Set.of(types), true);
    }

    @Test
    void aRendererIsFoundByEveryTypeItClaims() {
        BlockRendererRegistry registry = BlockRendererRegistry.of(fake("uml", "plantuml", "puml"));

        assertEquals("uml", registry.forType("plantuml").orElseThrow().name());
        assertEquals("uml", registry.forType("puml").orElseThrow().name());
        assertTrue(registry.forType("java").isEmpty(), "unclaimed types stay code blocks");
        assertTrue(registry.forType(null).isEmpty(), "an untyped fence claims nothing");
    }

    @Test
    void twoRenderersClaimingOneType_failRatherThanRace() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BlockRendererRegistry.of(fake("a", "plantuml"), fake("b", "plantuml", "puml")));

        // The message has to name the type and both claimants: the fix is to
        // drop a jar, and you can't drop one you can't identify.
        assertTrue(e.getMessage().contains("```plantuml"), e.getMessage());
        assertTrue(e.getMessage().contains("a ("), e.getMessage());
        assertTrue(e.getMessage().contains("b ("), e.getMessage());
    }

    @Test
    void aRendererClaimingNothing_isAWiringMistake() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BlockRendererRegistry.of(new Fake("mute", Set.of(), true)));

        assertTrue(e.getMessage().contains("claims no fence types"), e.getMessage());
    }

    @Test
    void anUnavailableRenderer_declinesTheTypeButStaysListed() {
        Fake broken = new Fake("needs-graphviz", Set.of("dot"), false);
        BlockRendererRegistry registry = BlockRendererRegistry.of(broken);

        // The block falls through to a template or to preformatted source: a
        // book still builds on a machine that can't draw its diagrams.
        assertTrue(registry.forType("dot").isEmpty());
        assertEquals(List.of(), registry.types());
        // ...but `mvn paperband:blocks` must still be able to say why.
        assertEquals(1, registry.size());
        assertEquals("no backend here", registry.all().get(0).unavailableReason());
    }

    @Test
    void aBlankVarDoesNotBlowUpTheRequest() {
        // `key:` with nothing after it is a null in the cascade. Map.copyOf
        // would refuse it, and a book should not be able to fail a build by
        // leaving a var blank.
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("theme", null);

        BlockRequest request = new BlockRequest("plantuml", "a -> b", null, null,
                withNull, withNull, null, null);

        assertEquals("svg", request.setting("theme", "svg"));
        assertTrue(request.vars().containsKey("theme"));
    }

    @Test
    void anEmptyRegistryClaimsNothing() {
        assertTrue(BlockRendererRegistry.empty().isEmpty());
        assertTrue(BlockRendererRegistry.empty().forType("plantuml").isEmpty());
    }
}
