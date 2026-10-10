package dev.noregressions.paperband.chromium;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs scripts in headless Chromium for modules that render with a browser library, such as
 * {@code dev.noregressions.paperband:mermaid}. One browser is shared by the whole JVM, started on first use (Playwright's
 * Chromium, downloaded once, as for pdf output) and closed when the JVM exits. Each kind of page is
 * loaded once and kept, so a library is parsed once however many times it's called.
 */
public final class ScriptPages {

    private static ChromiumBrowser browser;
    private static final Map<String, Page> PAGES = new HashMap<>();

    private ScriptPages() {
    }

    /**
     * Calls {@code script} (a JavaScript function, maybe async) with {@code arg} in the page named
     * {@code key}, loading it from {@code html} the first time.
     *
     * @return what the function returns, as Playwright converts it (strings, numbers, lists, maps)
     */
    public static synchronized Object evaluate(String key, String html, String script, Object arg) throws IOException {
        try {
            Page page = PAGES.get(key);
            if (page == null) {
                if (browser == null) {
                    browser = ChromiumBrowser.launch(null);
                    Runtime.getRuntime().addShutdownHook(new Thread(ScriptPages::closeQuietly));
                }
                page = browser.playwrightBrowser().newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                        .setLocale("en-GB").setTimezoneId("UTC")).newPage();
                page.setContent(html);
                PAGES.put(key, page);
            }
            return page.evaluate(script, arg);
        } catch (PlaywrightException e) {
            String m = String.valueOf(e.getMessage()).strip();
            throw new IOException("the browser script failed: " + (m.contains("\n") ? m.substring(0, m.indexOf('\n')) : m), e);
        }
    }

    private static synchronized void closeQuietly() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException e) {
            // the JVM is exiting; nothing to report to
        }
    }
}
