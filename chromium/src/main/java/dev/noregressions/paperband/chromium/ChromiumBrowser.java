package dev.noregressions.paperband.chromium;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Media;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.impl.driver.Driver;
import dev.noregressions.paperband.Fonts;
import dev.noregressions.paperband.browser.Browser;
import dev.noregressions.paperband.fit.Measurement;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless Chromium, through Playwright for Java.
 * <ul>
 *   <li>By default it's the Chromium that Playwright downloads on first use (about 150 MB, into
 *       Playwright's cache) and that matches its version, so every machine renders the same. A
 *       channel ({@code chrome}, {@code msedge}) or an executable path uses an installed browser
 *       instead, and nothing is downloaded.</li>
 *   <li>A document is loaded from a made-up origin and every request is answered locally: the
 *       document, the bundled fonts ({@link Fonts}), and files on disk for relative links, which
 *       resolve against the directory the output is written to, as when the paged HTML is opened
 *       from there. Nothing goes to the network.</li>
 *   <li>The viewport and pixel density are fixed, and printing waits for fonts to load.</li>
 *   <li>Links tagged {@code pageref} show their target's page number in the PDF ({@link PageRefs}).</li>
 * </ul>
 */
final class ChromiumBrowser implements Browser {

    /** Not a real host: {@code .invalid} never resolves, and every request is routed anyway. */
    static final String ORIGIN = "http://paperband.invalid";
    private static final String PAGE = "paperband-page.html";

    private final Playwright playwright;
    private final com.microsoft.playwright.Browser browser;

    private ChromiumBrowser(Playwright playwright, com.microsoft.playwright.Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    /** @param which null for Playwright's Chromium, a channel name, or a path to an executable */
    static ChromiumBrowser launch(String which) throws IOException {
        boolean installed = which != null;
        // Playwright would download every browser it supports; only Chromium is needed, or none.
        Map<String, String> env = new HashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Playwright playwright = null;
        try {
            if (!installed) {
                installChromium();
            }
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (installed && (which.contains("/") || which.contains("\\"))) {
                Path exe = Path.of(which);
                if (!Files.isExecutable(exe)) {
                    throw new IOException("browser: " + exe + " isn't an executable file");
                }
                options.setExecutablePath(exe);
            } else if (installed) {
                options.setChannel(which);
            }
            return new ChromiumBrowser(playwright, playwright.chromium().launch(options));
        } catch (PlaywrightException e) {
            if (playwright != null) {
                playwright.close();
            }
            String hint = installed
                    ? "is \"" + which + "\" installed? Leave browser unset to use the Chromium Playwright downloads"
                    : "Playwright downloads its Chromium on first use (about 150 MB), which needs the network; or set"
                    + " browser: chrome to use an installed Chrome";
            throw new IOException("couldn't start the browser for pdf output (" + hint + "): " + firstLine(e), e);
        } catch (IOException | RuntimeException e) {
            if (playwright != null) {
                playwright.close();
            }
            throw e;
        }
    }

    /** The Playwright browser, for {@link ScriptPages}. */
    com.microsoft.playwright.Browser playwrightBrowser() {
        return browser;
    }

    private static boolean chromiumInstalled;

    /**
     * Downloads Playwright's Chromium if it isn't in Playwright's cache yet (once per JVM; it's
     * quick when it's there). Uses Playwright's driver, whose API is internal but fixed by the
     * pinned Playwright version.
     */
    private static synchronized void installChromium() throws IOException {
        if (chromiumInstalled) {
            return;
        }
        ProcessBuilder pb = Driver.ensureDriverInstalled(Map.of(), false).createProcessBuilder();
        pb.command().addAll(List.of("install", "chromium"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IOException("couldn't download Chromium for pdf output (it needs the network, about 150 MB;"
                        + " or set browser: chrome to use an installed Chrome): " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading Chromium", e);
        }
        chromiumInstalled = true;
    }

    private static final String MEASURE = readResource("measure.js");

    @Override
    public Rendering render(String html, Path baseDir, boolean print) throws IOException {
        Path dir = baseDir.toAbsolutePath().normalize();
        String dirPath = dir.toUri().getRawPath();
        String pageUrl = ORIGIN + (dirPath.endsWith("/") ? dirPath : dirPath + "/") + PAGE;
        try (BrowserContext context = browser.newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                .setViewportSize(1280, 1024).setDeviceScaleFactor(1).setLocale("en-GB").setTimezoneId("UTC"))) {
            context.route("**/*", route -> serve(route, pageUrl, html));
            Page page = context.newPage();
            page.navigate(pageUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT)); // measure what prints
            page.evaluate("() => document.fonts.ready.then(() => true)");
            Measurement measurement = measurement(page.evaluate(MEASURE));
            byte[] pdf = print ? PageRefs.print(page,
                    () -> page.pdf(new Page.PdfOptions().setPreferCSSPageSize(true).setPrintBackground(true))) : null;
            return new Rendering(measurement, pdf);
        } catch (PlaywrightException e) {
            throw new IOException("rendering in the browser failed: " + firstLine(e), e);
        }
    }

    /** The plain objects {@code measure.js} returns, as a {@link Measurement}. */
    @SuppressWarnings("unchecked")
    static Measurement measurement(Object result) {
        Map<String, Object> m = (Map<String, Object>) result;
        List<Measurement.Sheet> sheets = new ArrayList<>();
        for (Object o : (List<Object>) m.get("pages")) {
            Map<String, Object> p = (Map<String, Object>) o;
            List<Measurement.Box> boxes = new ArrayList<>();
            for (Object b : (List<Object>) p.get("boxes")) {
                Map<String, Object> box = (Map<String, Object>) b;
                List<Double> units = ((List<Object>) box.get("units")).stream().map(u -> ((Number) u).doubleValue()).toList();
                boxes.add(new Measurement.Box(String.valueOf(box.get("path")), num(box, "x"), num(box, "y"),
                        num(box, "w"), num(box, "h"), num(box, "overflowX"), num(box, "overflowY"), units,
                        num(box, "content")));
            }
            List<Object> margin = (List<Object>) p.get("margin");
            double[] mg = new double[4];
            for (int i = 0; i < 4; i++) {
                mg[i] = ((Number) margin.get(i)).doubleValue();
            }
            sheets.add(new Measurement.Sheet(String.valueOf(p.get("id")), num(p, "width"), num(p, "height"), mg,
                    num(p, "overflowX"), num(p, "overflowY"), List.copyOf(boxes)));
        }
        List<Measurement.BrokenImage> images = new ArrayList<>();
        for (Object o : (List<Object>) m.get("images")) {
            Map<String, Object> i = (Map<String, Object>) o;
            images.add(new Measurement.BrokenImage(String.valueOf(i.get("page")), String.valueOf(i.get("box")),
                    String.valueOf(i.get("src"))));
        }
        List<String> fonts = ((List<Object>) m.get("fonts")).stream().map(String::valueOf).toList();
        return new Measurement(List.copyOf(sheets), List.copyOf(images), fonts);
    }

    private static double num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
    }

    private static String readResource(String name) {
        try (var in = ChromiumBrowser.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Answers a request locally: the document, a bundled font, or a file on disk. */
    private static void serve(Route route, String pageUrl, String html) {
        String url = route.request().url();
        int cut = indexOfAny(url, '?', '#');
        String plain = cut < 0 ? url : url.substring(0, cut);
        if (!plain.startsWith(ORIGIN + "/")) {
            route.abort();
            return;
        }
        if (plain.equals(pageUrl)) {
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8")
                    .setBodyBytes(html.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        String rawPath = plain.substring(ORIGIN.length());
        String fontPrefix = pageUrl.substring(ORIGIN.length(), pageUrl.length() - PAGE.length()) + Fonts.DIR + "/";
        if (rawPath.startsWith(fontPrefix)) {
            byte[] font = Fonts.read(rawPath.substring(fontPrefix.length()));
            if (font != null) {
                route.fulfill(new Route.FulfillOptions().setContentType("font/woff2").setBodyBytes(font));
                return;
            }
        }
        Path file;
        try {
            file = Path.of(URI.create("file://" + rawPath));
        } catch (RuntimeException e) {
            route.fulfill(new Route.FulfillOptions().setStatus(400));
            return;
        }
        if (Files.isRegularFile(file)) {
            route.fulfill(new Route.FulfillOptions().setPath(file));
        } else {
            route.fulfill(new Route.FulfillOptions().setStatus(404));
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static String firstLine(Exception e) {
        String m = String.valueOf(e.getMessage()).strip();
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    @Override
    public void close() {
        try {
            browser.close();
        } finally {
            playwright.close();
        }
    }
}
