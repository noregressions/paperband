package dev.noregressions.paperband;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Element ids for one rendered page of a site, so ids stay unique when many files (or the same
 * content twice) end up in one HTML document. An id {@code download} on page {@code tutorial-md}
 * becomes {@code tutorial-md--download}; if that's already used in the document, {@code -2},
 * {@code -3}, ... is added. In-page links ({@code #download}) point at the first one.
 */
public final class HtmlIds {

    private final String prefix;
    private final Set<String> used;
    private final Map<Path, String> pages;

    /**
     * @param prefix the rendering page's id
     * @param used   ids already in the document; shared by every page, and added to here
     */
    public HtmlIds(String prefix, Set<String> used) {
        this(prefix, used, Map.of());
    }

    /** @param pages the id of each source file's (first) page in the document, for page links ({@link PageLinks}) */
    public HtmlIds(String prefix, Set<String> used, Map<Path, String> pages) {
        this.prefix = prefix;
        this.used = used;
        this.pages = pages;
    }

    /** The id of the page {@code file} is on in this document, or null if it isn't in it. */
    String page(Path file) {
        return pages.get(file.normalize());
    }

    /** The id to give an element whose own id is {@code id}. */
    String element(String id) {
        String base = prefix + "--" + id;
        String out = base;
        for (int n = 2; !used.add(out); n++) {
            out = base + "-" + n;
        }
        return out;
    }

    /** The href for an in-page link to {@code id}. */
    String link(String id) {
        return "#" + prefix + "--" + id;
    }
}
