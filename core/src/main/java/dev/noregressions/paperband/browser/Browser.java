package dev.noregressions.paperband.browser;

import dev.noregressions.paperband.fit.Measurement;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A browser that paged documents are loaded into. It's open until closed: one is opened per
 * build, however many outputs it renders. Implementations come from {@link BrowserProvider}.
 */
public interface Browser extends AutoCloseable {

    /** A document as the browser laid it out: what it measured, and the PDF printed from that same page. */
    record Rendering(Measurement measurement, byte[] pdf) {
    }

    /**
     * Loads a paged HTML document, measures it (print media, after fonts and images load) and,
     * if asked, prints that same page to PDF, page sizes as its {@code @page} rules say.
     *
     * @param baseDir what the document's relative links (images, say) resolve against; the
     *                bundled fonts ({@link dev.noregressions.paperband.Fonts}) are always available
     * @param print   also print to PDF; else {@link Rendering#pdf()} is null
     */
    Rendering render(String html, Path baseDir, boolean print) throws IOException;

    /** Prints a paged HTML document to PDF. */
    default byte[] pdf(String html, Path baseDir) throws IOException {
        return render(html, baseDir, true).pdf();
    }

    @Override
    void close() throws IOException;
}
