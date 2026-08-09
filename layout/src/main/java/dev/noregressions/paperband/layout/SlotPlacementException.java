package dev.noregressions.paperband.layout;

/**
 * Thrown after a card body has rendered through a slot-using ("structural")
 * template when placement accounting fails: one or more top-level blocks were
 * never consumed by any {@code card.slots} call, or a {@code require(...)}
 * slot matched nothing. See {@link SlotTracker}.
 *
 * <p>Kept as a distinct subtype so the CLI can map it to its own exit code
 * (4 — structural check failed) the same way page enforcement owns exit
 * code 3, letting CI tell "card has the wrong shape" apart from "build
 * broken".
 */
public class SlotPlacementException extends LayoutException {

    public SlotPlacementException(String message) {
        super(message);
    }
}
