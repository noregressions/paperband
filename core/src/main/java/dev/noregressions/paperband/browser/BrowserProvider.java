package dev.noregressions.paperband.browser;

import java.io.IOException;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Opens {@link Browser}s. Found with {@link ServiceLoader}: the {@code dev.noregressions.paperband:chromium} module
 * provides one, so a build can print PDF when that module is on the classpath.
 */
public interface BrowserProvider {

    /**
     * @param browser which browser to use: null for the provider's own, a channel name such as
     *                {@code chrome} or {@code msedge} for one that's installed, or a path to an executable
     */
    Browser open(String browser) throws IOException;

    /** The provider on the classpath, if there is one. */
    static Optional<BrowserProvider> find() {
        return ServiceLoader.load(BrowserProvider.class, BrowserProvider.class.getClassLoader()).findFirst()
                .or(() -> ServiceLoader.load(BrowserProvider.class).findFirst());
    }
}
