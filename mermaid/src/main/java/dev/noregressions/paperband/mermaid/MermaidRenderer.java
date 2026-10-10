package dev.noregressions.paperband.mermaid;

import dev.noregressions.paperband.blocks.BlockRenderer;
import dev.noregressions.paperband.chromium.ScriptPages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * {@code ```mermaid} blocks as inline SVG, drawn by Mermaid in headless Chromium at build time, so
 * the output has no script in it and looks the same on the web and in print.
 * <ul>
 *   <li>Labels are SVG text, not HTML ({@code htmlLabels: false}), so they print and measure the
 *       same everywhere; {@code <br/>} in a label still breaks the line.</li>
 *   <li>Each diagram's ids come from a hash of its source, so the same source gives the same SVG on
 *       every build, and diagrams don't share ids (their styles are scoped by id).</li>
 *   <li>A diagram Mermaid can't parse stops the build with Mermaid's message.</li>
 * </ul>
 */
public final class MermaidRenderer implements BlockRenderer {

    private static final Map<String, String> RENDERED = new HashMap<>();

    private static final String RENDER = """
            async ({ id, source }) => {
              if (!window.paperbandReady) {
                mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral',
                                     htmlLabels: false, flowchart: { htmlLabels: false }, deterministicIds: true });
                window.paperbandReady = true;
              }
              try {
                const { svg } = await mermaid.render(id, source);
                return { svg };
              } catch (e) {
                document.getElementById('d' + id)?.remove(); // what mermaid leaves behind on an error
                return { error: String((e && e.message) || e) };
              }
            }""";

    @Override
    public Set<String> languages() {
        return Set.of("mermaid");
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized String render(String language, String source) throws IOException {
        String cached = RENDERED.get(source);
        if (cached != null) {
            return cached;
        }
        Object result = ScriptPages.evaluate("mermaid", page(), RENDER, Map.of("id", id(source), "source", source));
        Map<String, Object> r = (Map<String, Object>) result;
        if (r.get("error") != null) {
            throw new IOException("Mermaid couldn't draw the diagram: " + firstLines(String.valueOf(r.get("error"))));
        }
        String svg = String.valueOf(r.get("svg"));
        RENDERED.put(source, svg);
        return svg;
    }

    /** {@code mermaid-} and the start of the source's SHA-256: stable across builds, distinct between diagrams. */
    static String id(String source) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return "mermaid-" + HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String page;

    /** A blank page with Mermaid loaded. */
    private static synchronized String page() throws IOException {
        if (page == null) {
            page = "<!doctype html><html><head><meta charset=\"utf-8\"><script>" + mermaidJs()
                    + "</script></head><body></body></html>";
        }
        return page;
    }

    static String version() throws IOException {
        Properties p = new Properties();
        try (InputStream in = MermaidRenderer.class.getResourceAsStream("mermaid.properties")) {
            if (in == null) {
                throw new IOException("mermaid.properties is missing from dev.noregressions.paperband:mermaid");
            }
            p.load(in);
        }
        return p.getProperty("version");
    }

    private static String mermaidJs() throws IOException {
        String path = "/META-INF/resources/webjars/mermaid/" + version() + "/dist/mermaid.min.js";
        try (InputStream in = MermaidRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Mermaid isn't on the classpath (" + path + "): dev.noregressions.paperband:mermaid needs its"
                        + " org.webjars.npm:mermaid dependency");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("</script", "<\\/script");
        }
    }

    private static String firstLines(String message) {
        List<String> lines = message.strip().lines().limit(4).toList();
        return String.join(" / ", lines);
    }
}
