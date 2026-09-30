package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.PlacedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A view that leaves cards out moves each {@code <page>} marker to count only
 * the cards it keeps, so the site's nav places the page where the book does.
 */
class SiteKeptPagesTest {

    @Test
    void a_page_counts_only_the_kept_cards_in_front_of_it() {
        List<PlacedPage> pages = List.of(new PlacedPage(0, "front"), new PlacedPage(3, "middle"),
                new PlacedPage(4, "back"));
        List<Boolean> keeps = List.of(true, false, true, false);
        assertEquals(List.of(new PlacedPage(0, "front"), new PlacedPage(2, "middle"), new PlacedPage(2, "back")),
                SiteMojo.keptPages(pages, keeps));
    }
}
