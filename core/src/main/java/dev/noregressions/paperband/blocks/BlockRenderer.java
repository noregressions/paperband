package dev.noregressions.paperband.blocks;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Renders fenced code blocks of some languages as something other than code: a diagram, say.
 * Found with {@link ServiceLoader}, so a module on the classpath adds languages
 * ({@code dev.noregressions.paperband:plantuml} renders {@code ```plantuml} fences as SVG). A fence in a language no
 * renderer claims is shown as code, as usual.
 */
public interface BlockRenderer {

    /** The fence languages this renders, e.g. {@code plantuml}. */
    Set<String> languages();

    /**
     * The HTML for one block.
     *
     * @param language the fence's language, one of {@link #languages()}
     * @param source   the block's text
     * @throws IOException if the source can't be rendered; the build stops with the message
     */
    String render(String language, String source) throws IOException;

    /** The renderers on the classpath. */
    static List<BlockRenderer> all() {
        List<BlockRenderer> out = new java.util.ArrayList<>();
        ServiceLoader.load(BlockRenderer.class, BlockRenderer.class.getClassLoader()).forEach(out::add);
        if (out.isEmpty()) {
            ServiceLoader.load(BlockRenderer.class).forEach(out::add);
        }
        return List.copyOf(out);
    }

    /** The renderer for {@code language} among {@code renderers}, or null. */
    static BlockRenderer forLanguage(List<BlockRenderer> renderers, String language) {
        for (BlockRenderer r : renderers) {
            if (r.languages().contains(language)) {
                return r;
            }
        }
        return null;
    }
}
