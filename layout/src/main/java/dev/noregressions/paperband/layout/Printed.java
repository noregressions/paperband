package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.OutlineEntry;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a rendered book actually holds, read back from its HTML: the ids its
 * templates wrote.
 *
 * <p>Which cards a book holds is settled before layout (a selection, a view's
 * {@code keep.html}), and the contents and bookmarks are worked out from that.
 * Whether the templates then printed them is a separate question, and only the
 * output can answer it. A theme's card body that drops {@code id="card-<id>"},
 * or a {@code book.html} that skips a card, would leave {@code card:} links,
 * contents lines and bookmarks pointing at nothing. So a kept card that isn't
 * in the output fails the build, and a bookmark whose anchor isn't there is
 * dropped with a warning -- a divider page a template chose not to print isn't
 * an error, but it has nothing to open.
 */
final class Printed {

    private final Set<String> ids = new HashSet<>();

    /** The ids of every element in {@code html}. */
    Printed(String html) {
        for (Element el : Jsoup.parse(html).select("[id]")) ids.add(el.id());
    }

    /**
     * Fail when a card the book holds wasn't printed.
     *
     * @throws UnprintedCardException naming every kept card with no {@code id="card-<id>"} in the output
     */
    void requireCards(List<Card> cards, String layoutName) {
        List<String> missing = new ArrayList<>();
        for (Card card : cards) {
            if (!ids.contains("card-" + card.id())) missing.add(card.id());
        }
        if (missing.isEmpty()) return;
        throw new UnprintedCardException(missing.size() + (missing.size() == 1 ? " card the book holds was" : " cards the book holds were")
                + " never printed: " + String.join(", ", missing) + ". Layout '" + layoutName
                + "' wrote no id=\"card-<id>\" for "
                + (missing.size() == 1 ? "it" : "them")
                + ", so card: links, the contents and the bookmarks would point at nothing. A card body"
                + " template keeps id=\"card-{{ card.id }}\" on what it writes; to leave a card out of a"
                + " build, use a view whose keep.html prints false for it.");
    }

    /**
     * The bookmarks whose anchors the output holds. A dropped entry's children
     * move up a level, so they don't land under whichever entry came before it.
     *
     * @param dropped receives the label and anchor of each entry left out
     */
    List<OutlineEntry> reachable(List<OutlineEntry> outline, List<String> dropped) {
        List<OutlineEntry> out = new ArrayList<>(outline.size());
        int droppedDepth = -1;              // depth of the entry whose children are being lifted
        for (OutlineEntry e : outline) {
            if (droppedDepth >= 0 && e.depth() <= droppedDepth) droppedDepth = -1;
            if (!ids.contains(e.anchor())) {
                dropped.add("'" + e.label() + "' (#" + e.anchor() + ")");
                if (droppedDepth < 0) droppedDepth = e.depth();
                continue;
            }
            int depth = droppedDepth >= 0 && e.depth() > droppedDepth ? e.depth() - 1 : e.depth();
            out.add(new OutlineEntry(e.label(), e.anchor(), depth));
        }
        return out;
    }
}
