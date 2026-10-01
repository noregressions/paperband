package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code {!number}}: the card's own number, unknown while the card is read,
 * so it's left as {@link CardNumber#MARK} for the layout to fill in.
 */
class NumberMarkerTest {

    private static Card card(String md) {
        return new CardLoader().parse(Path.of("t.md"), "---\ntitle: T\n---\n" + md);
    }

    @Test
    void the_marker_is_left_where_the_number_will_read() {
        List<Block> bs = card("## Scenario {!number} recap\n\nThis is scenario {!number}.\n").blocks();
        assertEquals("Scenario " + CardNumber.MARK + " recap", bs.get(0).heading());
        assertTrue(bs.get(0).html().contains("This is scenario " + CardNumber.MARK + "."), bs.get(0).html());
    }

    @Test
    void the_anchor_does_not_change_with_the_number() {
        Block b = card("## Scenario {!number} recap\n").blocks().get(0);
        assertTrue(b.classes().contains("scenario-number-recap"), b.classes().toString());
    }

    @Test
    void in_code_it_is_an_example() {
        Block b = card("## A\n\nWrite `{!number}` to print it.\n").blocks().get(0);
        assertFalse(b.html().contains(CardNumber.MARK), b.html());
        assertTrue(b.html().contains("{!number}"), b.html());
    }

    @Test
    void it_takes_no_value() {
        CardParseException e = assertThrows(CardParseException.class, () -> card("## A {!number=3}\n"));
        assertTrue(e.getMessage().contains("takes no value"), e.getMessage());
    }

    @Test
    void it_is_not_an_attribute() {
        CardParseException e = assertThrows(CardParseException.class, () -> card("## A {.x !number}\n"));
        assertTrue(e.getMessage().contains("not in an attribute group"), e.getMessage());
    }
}
