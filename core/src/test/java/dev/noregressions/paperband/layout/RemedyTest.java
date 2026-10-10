package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemedyTest {

    @TempDir
    Path dir;

    private Layouts load(String... files) throws IOException {
        for (int i = 0; i < files.length; i += 2) {
            Files.writeString(dir.resolve(files[i] + ".yml"), files[i + 1]);
        }
        return Layouts.load(dir, PageSpec.A4);
    }

    private String error(String yaml) {
        return assertThrows(ConfigException.class, () -> load("bad", yaml)).getMessage();
    }

    @Test
    void boxAndPageRemediesAreParsedInOrder() throws IOException {
        Layouts layouts = load("l", """
                grid: { areas: ["a", "b"] }
                grids:
                  side: { columns: [1fr, 1fr], areas: ["a b"] }
                boxes:
                  - id: a
                    area: a
                    select: rest
                    fit:
                      - shrink: { min: 90% }
                      - tighten: { line-height: 1.1, gap: 2mm }
                      - variant: shortest
                      - drop: optional
                      - truncate: { lines: 5 }
                      - otherwise: fail
                  - { id: b, area: b, text: x, priority: optional, drop: { below-min: true }, size: { min: 5mm },
                      fit: [{ shrink: true }, { tighten: true }, { truncate: true }] }
                fit: [{ drop: boxes }, { grid: side }, { layout: other }]
                """, "other", "boxes: [{ id: all, select: rest }]\n");
        Layout l = layouts.get("l");
        assertEquals(List.of(new Remedy.Shrink(0.9), new Remedy.Tighten(1.1, "2mm"), new Remedy.Variant(true),
                new Remedy.DropOptional(), new Remedy.Truncate(5)), l.boxes().get(0).rules().fit());
        assertEquals(List.of(new Remedy.Shrink(0.85), new Remedy.Tighten(1.2, "1mm"), new Remedy.Truncate(null)),
                l.boxes().get(1).rules().fit());
        assertEquals(List.of(new Remedy.DropBoxes(), new Remedy.UseGrid("side"), new Remedy.UseLayout("other")), l.fit());
        assertEquals(List.of("1fr", "1fr"), l.grid("side").columns());
        assertSame(l.grid(), l.grid(null));
        assertEquals("shrink (min 90%)", l.boxes().get(0).rules().fit().getFirst().describe());
    }

    @Test
    void splitTakesItsPlaceAmongABoxsRemedies() throws IOException {
        Layouts layouts = load("l", "boxes:\n  - { id: a, select: rest, split: true, fit: [{ shrink: true }, split, { tighten: true }] }\n",
                "m", "boxes:\n  - { id: b, select: rest, split: true, fit: [{ split: true }] }\n");
        assertEquals(List.of(new Remedy.Shrink(0.85), new Remedy.Split(), new Remedy.Tighten(1.2, "1mm")),
                layouts.get("l").boxes().get(0).rules().fit());
        assertEquals(List.of(new Remedy.Split()), layouts.get("m").boxes().getFirst().rules().fit());
    }

    @Test
    void mistakesAreReported() {
        assertEquals("bad.yml: boxes[0].fit[0]: unknown remedy \"squash\"; a box can use shrink, tighten, variant,"
                + " drop: optional, truncate", error("boxes: [{ id: a, select: rest, fit: [{ squash: true }] }]\n"));
        assertEquals("bad.yml: boxes[0].fit[0].shrink.min: expected a percentage below 100%, e.g. 85%",
                error("boxes: [{ id: a, select: rest, fit: [{ shrink: { min: 100% } }] }]\n"));
        assertEquals("bad.yml: boxes[0].fit[0]: grid is a page remedy: list it under the layout's own fit:",
                error("boxes: [{ id: a, select: rest, fit: [{ grid: x }] }]\n"));
        assertEquals("bad.yml: fit[0]: shrink is a box remedy: list it under the box's fit:",
                error("fit: [{ shrink: true }]\nboxes: [{ id: a, select: rest }]\n"));
        assertEquals("bad.yml: boxes[0].fit[0].variant: \"longer\" isn't one of [next, shortest]",
                error("boxes: [{ id: a, select: rest, fit: [{ variant: longer }] }]\n"));
        assertEquals("bad.yml: boxes[0]: fit: a box with wrap: false has no element to fit; leave wrap on",
                error("boxes: [{ id: a, select: rest, wrap: false, fit: [{ shrink: true }] }]\n"));
        assertEquals("bad.yml: fit: drop: boxes needs a box that can be dropped (priority normal or optional, with"
                + " drop: { below-min: true })", error("fit: [{ drop: boxes }]\nboxes: [{ id: a, select: rest }]\n"));
        assertEquals("bad.yml: fit: grid \"wide\" isn't one of the layout's grids []",
                error("grid: { areas: [a] }\nfit: [{ grid: wide }]\nboxes: [{ id: a, area: a, select: rest }]\n"));
        assertEquals("bad.yml: grids: grids are alternatives to the layout's grid, so it needs one",
                error("grids: { x: { areas: [a] } }\nboxes: [{ id: a, select: rest }]\n"));
        assertEquals("bad.yml: grids.x: box \"a\" needs its area \"a\" in this grid too; its areas are [b]",
                error("grid: { areas: [a] }\ngrids: { x: { areas: [b] } }\nboxes: [{ id: a, area: a, select: rest }]\n"));
        assertEquals("bad: fit: layout: unknown layout \"nowhere\"; available: [bad, default]",
                error("fit: [{ layout: nowhere }]\nboxes: [{ id: a, select: rest }]\n"));
        assertEquals("bad.yml: boxes[0]: fit: split needs the box's own split: rule (where it may break)",
                error("boxes: [{ id: a, select: rest, fit: [split] }]\n"));
        assertEquals("bad.yml: boxes[0]: fit: split is listed twice",
                error("boxes: [{ id: a, select: rest, split: true, fit: [split, split] }]\n"));
        assertEquals("bad.yml: fit[0].otherwise: the only choice is fail (the build fails when nothing fits)",
                error("fit: [{ otherwise: shrug }]\nboxes: [{ id: a, select: rest }]\n"));
    }
}
