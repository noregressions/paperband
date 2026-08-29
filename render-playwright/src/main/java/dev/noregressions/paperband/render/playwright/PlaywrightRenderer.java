package dev.noregressions.paperband.render.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;

import dev.noregressions.paperband.render.HtmlInput;
import dev.noregressions.paperband.render.HtmlToPdfRenderer;
import dev.noregressions.paperband.render.Margins;
import dev.noregressions.paperband.render.Orientation;
import dev.noregressions.paperband.render.PageSize;
import dev.noregressions.paperband.render.PdfRenderException;
import dev.noregressions.paperband.render.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * HTML&rarr;PDF renderer backed by
 * <a href="https://playwright.dev/java">Playwright Java</a> driving headless Chromium.
 *
 * <p><b>CSS:</b> matches a modern browser &mdash; flexbox, grid, custom properties, web fonts,
 * SVG. The closest thing to "what your designer sees in Chrome" you can get from a
 * library.
 *
 * <p><b>Page geometry:</b> honours both {@link dev.noregressions.paperband.render.PageSpec#size()}
 * and {@link dev.noregressions.paperband.render.PageSpec#margins()} via the native
 * {@code Page.pdf()} options. CSS {@code @page} rules in the document still
 * apply unless the SPI hint overrides them; if you want the template's
 * {@code @page} to win, this renderer is the wrong choice today.
 *
 * <p><b>Runtime cost:</b> the first invocation in a fresh environment downloads
 * Chromium (~200&ndash;300MB) into {@code ~/.cache/ms-playwright/}. Subsequent
 * runs are fast but each render still incurs a browser-launch cost. For
 * batch rendering many cards, future work should reuse a single browser instance.
 *
 * <p><b>Network:</b> {@code page.setContent()} loads the HTML and waits for
 * {@code networkidle} so external assets (web fonts, remote images) finish
 * before the PDF is captured.
 */
public final class PlaywrightRenderer implements HtmlToPdfRenderer {

    @Override
    public String name() {
        return "playwright";
    }

    @Override
    public String description() {
        return "Headless Chromium via Playwright. "
             + "Best-in-class CSS (flexbox, grid, modern). "
             + "First run downloads ~300MB Chromium to ~/.cache/ms-playwright/. "
             + "Honours PageSpec.size and PageSpec.margins.";
    }

    @Override
    public boolean isAvailable() {
        // We can't cheaply probe whether the Chromium binary has been downloaded
        // without instantiating Playwright. Optimistic 'yes' here; render() will
        // produce a clear error if browsers are missing.
        return true;
    }

    @Override
    public void render(HtmlInput input, Path output) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch();
                try {
                    BrowserContext ctx = browser.newContext(
                            new Browser.NewContextOptions()
                                    .setBaseURL(input.baseUri().toString()));
                    Page page = ctx.newPage();
                    // Two problems stop relative book-local assets (cover
                    // images etc.) loading under setContent:
                    //   1. A setContent document has no base URL (the
                    //      context's setBaseURL only affects navigation), so
                    //      relative src/href resolve against nothing.
                    //   2. Even with a <base> fix, the document is
                    //      about:blank, and Chromium blocks file:// subresource
                    //      loads from non-file documents ("Not allowed to load
                    //      local resource").
                    // So when the base URI is a local directory, write the
                    // rendered HTML to a temp file INSIDE that directory and
                    // navigate to it: the document is then file-origin in the
                    // right place, and both relative resolution and file→file
                    // image loads work. setContent (with an injected <base>)
                    // remains the fallback for non-file base URIs.
                    Path tempHtml = null;
                    try {
                        if ("file".equals(input.baseUri().getScheme())) {
                            Path baseDir = Path.of(input.baseUri());
                            if (Files.isDirectory(baseDir)) {
                                tempHtml = Files.createTempFile(baseDir, ".paperband-render-", ".html");
                                Files.writeString(tempHtml, input.html());
                                page.navigate(tempHtml.toUri().toString(),
                                        new Page.NavigateOptions()
                                                .setWaitUntil(WaitUntilState.NETWORKIDLE));
                            }
                        }
                        if (tempHtml == null) {
                            page.setContent(injectBase(input.html(), input.baseUri().toString()),
                                    new Page.SetContentOptions()
                                            .setWaitUntil(WaitUntilState.NETWORKIDLE));
                        }

                        // NETWORKIDLE alone races web fonts: an @import'd font
                        // stylesheet resolves late, and the individual font
                        // files it references can still be in flight (or not
                        // yet requested — a weight is only fetched once text
                        // using it lays out) when the network goes quiet.
                        // document.fonts.ready settles only after every face
                        // the document actually uses has loaded or failed, so
                        // the PDF snapshots real fonts instead of mid-swap
                        // fallbacks (seen as Arial standing in for a themed
                        // sans at one weight while the others embedded fine).
                        page.evaluate("() => document.fonts.ready");

                        // Page scripts that lay out content AFTER load can't be
                        // covered by NETWORKIDLE either — their work is CPU-bound
                        // once the network goes quiet (mermaid diagrams rendering
                        // to SVG, via the bundled _mermaid.html loader). The
                        // contract: such a script pushes a promise into
                        // window.paperbandPending, and the PDF is snapshotted only
                        // once every registered promise has settled. A rejection
                        // fails the render — deliberately: a half-rendered diagram
                        // in a shipped PDF is worse than a build error carrying
                        // the page script's own message.
                        page.evaluate("() => Promise.all(window.paperbandPending || [])");

                        PageSize size = input.pageSpec().size();
                        Margins m   = input.pageSpec().margins();

                        Page.PdfOptions options = new Page.PdfOptions()
                                .setPath(output)
                                .setWidth(format(size.width(), size.unit()))
                                .setHeight(format(size.height(), size.unit()))
                                .setLandscape(input.pageSpec().orientation() == Orientation.LANDSCAPE)
                                .setMargin(new Margin()
                                        .setTop(format(m.top(), m.unit()))
                                        .setRight(format(m.right(), m.unit()))
                                        .setBottom(format(m.bottom(), m.unit()))
                                        .setLeft(format(m.left(), m.unit())))
                                .setPrintBackground(true);

                        // Running header/footer (see HtmlInput.headerHtml/footerHtml
                        // javadoc for why this can't be a CSS @page rule here):
                        // Chromium's print engine has no CSS Paged Media support at
                        // all, so repeating content can only come through Page.pdf()'s
                        // own header/footer option — a totally separate mini-document
                        // with no access to the main page's stylesheet, hence
                        // headerHtml/footerHtml needing to be self-contained.
                        // headerTemplate/footerTemplate must still be set to something
                        // non-empty when the OTHER one is in use, or Chromium falls
                        // back to its own default content there (date + title in the
                        // header, page number in the footer) instead of leaving it
                        // blank; an empty <span> suppresses that without adding
                        // visible content of its own.
                        if (input.footerHtml() != null || input.headerHtml() != null) {
                            options.setDisplayHeaderFooter(true)
                                    .setHeaderTemplate(
                                            input.headerHtml() != null ? input.headerHtml() : "<span></span>")
                                    .setFooterTemplate(
                                            input.footerHtml() != null ? input.footerHtml() : "<span></span>");
                        }

                        // TODO(paperband): wire PdfMetadata (title/author/subject) once
                        //                   metadata is a real requirement. Chromium's
                        //                   Page.pdf() doesn't expose author/subject; would
                        //                   need a PDFBox post-process step.

                        page.pdf(options);
                    } finally {
                        if (tempHtml != null) Files.deleteIfExists(tempHtml);
                    }
                } finally {
                    browser.close();
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            String hint = msg != null && msg.contains("Executable doesn't exist")
                    ? " (run: `mvn -pl render-playwright exec:java "
                    + "-Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args='install chromium'` "
                    + "or set PLAYWRIGHT_BROWSERS_PATH)"
                    : "";
            throw new PdfRenderException("Playwright render failed: " + msg + hint, e);
        }
    }

    /** Format a length in this {@link Unit} as a CSS string Playwright accepts. */
    /**
     * Prepend a {@code <base href>} inside {@code <head>} so relative URLs in
     * setContent-loaded documents resolve against the build's base URI.
     * Documents already declaring a {@code <base} tag are returned unchanged,
     * as are documents with no {@code <head>} (nothing safe to anchor to).
     */
    static String injectBase(String html, String baseUri) {
        if (html == null || baseUri == null) return html;
        String lower = html.toLowerCase();
        if (lower.contains("<base ") || lower.contains("<base>")) return html;
        int head = lower.indexOf("<head>");
        if (head < 0) return html;
        int insertAt = head + "<head>".length();
        return html.substring(0, insertAt)
                + "<base href=\"" + baseUri + "\">"
                + html.substring(insertAt);
    }

    private static String format(double value, Unit unit) {
        String suffix = switch (unit) {
            case MM    -> "mm";
            case INCH  -> "in";
            case POINT -> "pt";
        };
        // Trim trailing zeros: 210.0 -> "210", 8.5 -> "8.5"
        if (value == Math.floor(value)) {
            return ((long) value) + suffix;
        }
        return value + suffix;
    }

    @SuppressWarnings("unused")
    private static List<String> sample() {
        return List.of();
    }
}
