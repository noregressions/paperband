package dev.noregressions.paperband.layout;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The manifest's sequence worked out against the actual source files: every page to write,
 * in order, with its layout.
 *
 * @param items  the pages, in output order
 * @param unused sources that are neither in the sequence nor hidden
 */
public record ResolvedSequence(List<Item> items, List<Path> unused) {

    /**
     * @param context where it came from in the manifest, for messages
     * @param file    the source file, or null for a generated page
     * @param layout  the layout name to use
     * @param named   true if the sequence entry named the layout (rather than the file's front
     *                matter or the default), so an output's {@code layout} doesn't replace it
     */
    public record Item(String context, Path file, String layout, boolean named) {

        public Item(String context, Path file, String layout) {
            this(context, file, layout, file == null);
        }

        Item withLayout(String layout) {
            return new Item(context, file, layout, named);
        }
    }

    /** The source files in the sequence, in order (generated pages left out). */
    public List<Path> files() {
        return items.stream().map(Item::file).filter(Objects::nonNull).toList();
    }
}
