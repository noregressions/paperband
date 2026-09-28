package dev.noregressions.paperband.render.playwright;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A composed book document, loaded into a Playwright {@link Page} and settled
 * to the point where the DOM can be measured, screenshotted or printed.
 *
 * <p>Every consumer of a paperband HTML document in a browser needs the same
 * two awkward things, and getting either wrong produces output that looks
 * plausible and is quietly wrong — so they live here once rather than in each
 * caller.
 *
 * <h2>Why not just {@code setContent}</h2>
 * Two problems stop relative book-local assets (cover images etc.) loading
 * under {@code setContent}:
 * <ol>
 *   <li>A {@code setContent} document has no base URL — the context's
 *       {@code setBaseURL} only affects navigation — so relative
 *       {@code src}/{@code href} resolve against nothing.</li>
 *   <li>Even with a {@code <base>} fix the document is {@code about:blank},
 *       and Chromium blocks {@code file://} subresource loads from a non-file
 *       document ("Not allowed to load local resource").</li>
 * </ol>
 * So when the base URI is a local directory, the HTML is written to a temp
 * file <em>inside that directory</em> and navigated to: the document is then
 * file-origin in the right place, and both relative resolution and file&rarr;file
 * image loads work. {@code setContent} with an injected {@code <base>} remains
 * the fallback for non-file base URIs.
 *
 * <h2>Why NETWORKIDLE is not enough</h2>
 * {@code NETWORKIDLE} races web fonts: an {@code @import}'d font stylesheet
 * resolves late, and the font files it references can still be in flight — or
 * not yet requested, since a weight is only fetched once text using it lays
 * out — when the network goes quiet. {@code document.fonts.ready} settles only
 * after every face the document actually uses has loaded or failed.
 *
 * <p>Page scripts that lay out content after load aren't covered either: their
 * work is CPU-bound once the network is quiet (mermaid rendering diagrams to
 * SVG). {@code window.paperbandPending} is the contract for that — see
 * {@code _mermaid.html} — and awaiting it is what keeps a measured height and
 * a printed page in agreement.
 *
 * <h2>Lifetime</h2>
 * {@link #close()} removes the temp file, so hold the instance open for as long
 * as the page is in use and close it after. It does not close the page or the
 * browser; the caller owns those.
 */
public final class LoadedPage implements AutoCloseable {

    private final Path tempHtml;

    private LoadedPage(Path tempHtml) {
        this.tempHtml = tempHtml;
    }

    /**
     * Load {@code html} into {@code page} and return once fonts and any
     * page-script layout work have settled.
     *
     * @param page     the page to load into; the caller owns its lifecycle
     * @param html     the fully composed HTML document
     * @param baseUri  base URI for resolving relative asset references
     * @param onWarn   receives a message when a settle step fails in a way that
     *                 is survivable; may be null
     * @return a handle whose {@link #close()} cleans up the temp file, if one
     *         was needed
     * @throws IOException if the temp file can't be written
     */
    public static LoadedPage open(Page page, String html, URI baseUri, Consumer<String> onWarn)
            throws IOException {
        Path temp = null;
        if (baseUri != null && "file".equals(baseUri.getScheme())) {
            Path baseDir = Path.of(baseUri);
            if (Files.isDirectory(baseDir)) {
                temp = Files.createTempFile(baseDir, ".paperband-page-", ".html");
                Files.writeString(temp, html);
                page.navigate(temp.toUri().toString(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            }
        }
        if (temp == null) {
            String withBase = baseUri == null
                    ? html
                    : PlaywrightRenderer.injectBase(html, baseUri.toString());
            page.setContent(withBase,
                    new Page.SetContentOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        }

        LoadedPage loaded = new LoadedPage(temp);
        try {
            page.evaluate("() => document.fonts.ready");
            page.evaluate("() => Promise.all(window.paperbandPending || [])");
        } catch (RuntimeException e) {
            // A page script that rejected shouldn't strand the temp file.
            if (onWarn != null) {
                onWarn.accept("Page did not settle cleanly: " + e.getMessage());
            }
        }
        return loaded;
    }

    @Override
    public void close() {
        if (tempHtml == null) return;
        try {
            Files.deleteIfExists(tempHtml);
        } catch (IOException ignored) {
            // A leftover temp file in the book directory is untidy, not fatal;
            // failing close() here would mask whatever the caller was doing.
        }
    }
}
