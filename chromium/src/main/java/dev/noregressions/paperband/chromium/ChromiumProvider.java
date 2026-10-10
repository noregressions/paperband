package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.browser.Browser;
import dev.noregressions.paperband.browser.BrowserProvider;

import java.io.IOException;

/** Opens {@link ChromiumBrowser}s; found by {@link BrowserProvider#find()}. */
public final class ChromiumProvider implements BrowserProvider {

    @Override
    public Browser open(String browser) throws IOException {
        return ChromiumBrowser.launch(browser);
    }
}
