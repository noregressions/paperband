package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.config.ConfigLoader;
import dev.noregressions.paperband.include.Includes;
import dev.noregressions.paperband.layout.LayoutEngine;
import dev.noregressions.paperband.layout.SlotPlacementException;
import dev.noregressions.paperband.layout.ThemeBundle;
import dev.noregressions.paperband.layout.SectionBody;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Watermark;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a multi-file static HTML site from a book directory: an index, a
 * landing page per axis value and per section (declared or discovered), and a page
 * per card with prev/next navigation.
 *
 * <p>The site shares the book's css chain and theme with the PDF target, so
 * one book renders both without a second set of config. Add a site-specific
 * stylesheet to the chain for the sticky nav, card grid and hero panels.
 *
 * <pre>
 * &lt;execution&gt;
 *   &lt;id&gt;site&lt;/id&gt;
 *   &lt;goals&gt;&lt;goal&gt;site&lt;/goal&gt;&lt;/goals&gt;
 *   &lt;configuration&gt;
 *     &lt;input&gt;${project.basedir}/guide&lt;/input&gt;
 *     &lt;outputDirectory&gt;${project.build.directory}/site&lt;/outputDirectory&gt;
 *   &lt;/configuration&gt;
 * &lt;/execution&gt;
 * </pre>
 */
@Mojo(name = "site", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class SiteMojo extends AbstractPaperbandMojo {

    /** Input book directory. Mutually exclusive with {@link #book}. */
    @Parameter(property = "paperband.input")
    private java.io.File input;

    /**
     * Build the site for a POM-declared book instead of a directory tree — the
     * same {@code <book>} element {@code build} takes, so one declaration feeds
     * both the PDF and the site.
     */
    @Parameter
    private BookLayout book;

    /** Output directory for the generated site. */
    @Parameter(property = "paperband.outputDirectory", required = true)
    private java.io.File outputDirectory;

    /**
     * Clear the {@code cards/} subtree before writing, so a card removed from
     * the book stops being served from a stale page.
     */
    @Parameter(property = "paperband.clean", defaultValue = "false")
    private boolean clean;

    /**
     * Build target the config cascade sees, defaulting to {@code web} rather
     * than the {@code pdf-a4} every other goal uses: target-scoped content
     * ({@code where:} predicates, {@code targets:} lists) is usually written to
     * distinguish exactly this.
     *
     * <p>Named {@code siteTarget} rather than sharing {@code <target>} with the
     * inherited parameter. Two fields in one mojo bound to the same property is
     * a trap: {@code <target>} would set the inherited field, which this goal
     * never reads, so the element would look broken while a {@code -D} override
     * quietly set both.
     */
    @Parameter(property = "paperband.siteTarget", defaultValue = "web")
    private String siteTarget;

    /**
     * The watermark to stamp, as a block — the POM spelling of the
     * {@code vars.watermark} map:
     *
     * <pre>
     * &lt;watermark&gt;
     *   &lt;text&gt;REVIEW COPY&lt;/text&gt;
     *   &lt;color&gt;#aa0000&lt;/color&gt;
     *   &lt;opacity&gt;0.15&lt;/opacity&gt;
     * &lt;/watermark&gt;
     * </pre>
     *
     * <p>Replaces a {@code vars.watermark} declared in the book's yaml. The flat
     * {@code <watermarkColor>} family below still layers over it, since those
     * carry the {@code -D} properties — see {@link WatermarkConfig}.
     */
    @Parameter
    private WatermarkConfig watermark;

    /**
     * Stamp this text across every page of the site (e.g. {@code DRAFT}).
     * Overrides a {@code vars.watermark} declared in the book's yaml. A
     * {@code \n} in the value breaks the stamp across lines.
     */
    @Parameter(property = "paperband.watermark")
    private String watermarkText;

    /**
     * Stamp this image instead of text — a book-root-relative path. The file is
     * copied into the site's {@code assets/} directory alongside the cover art.
     */
    @Parameter(property = "paperband.watermarkImage")
    private String watermarkImage;

    /** Watermark fill colour as {@code #RRGGBB}. */
    @Parameter(property = "paperband.watermarkColor")
    private String watermarkColor;

    /** Watermark fill alpha, 0 to 1. */
    @Parameter(property = "paperband.watermarkOpacity")
    private Float watermarkOpacity;

    /** Watermark rotation in degrees. */
    @Parameter(property = "paperband.watermarkAngle")
    private Float watermarkAngle;

    /** Watermark font size in points; a ceiling unless {@code <watermarkFit>} is false. */
    @Parameter(property = "paperband.watermarkFontSize")
    private Integer watermarkFontSize;

    /** Set the watermark in bold rather than regular weight. */
    @Parameter(property = "paperband.watermarkBold")
    private Boolean watermarkBold;

    /** For an image watermark, its width as a fraction of the page width. */
    @Parameter(property = "paperband.watermarkScale")
    private Float watermarkScale;

    /** Shrink the watermark until it fits the viewport instead of letting it overflow. */
    @Parameter(property = "paperband.watermarkFit")
    private Boolean watermarkFit;

    /** Repeat the watermark across each page instead of centring one stamp. */
    @Parameter(property = "paperband.watermarkTile")
    private Boolean watermarkTile;

    /** Draw the watermark underneath the page content rather than over it. */
    @Parameter(property = "paperband.watermarkBehind")
    private Boolean watermarkBehind;

    /** Book-level vars the POM's {@code <book>} declared, for the config cascade. */
    private java.util.Map<String, Object> declaredVars() {
        return book == null ? java.util.Map.of() : book.declaredVars();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("site")) return;
        checkBookDeclaration(book);
        try {
            build();
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Paperband site build failed: " + e.getMessage(), e);
        }
    }

    private void build() throws Exception {
        if (input != null && book != null) {
            throw new MojoExecutionException("Configure either <input> or <book>, not both.");
        }
        if (content != null && input != null) {
            throw new MojoExecutionException(
                    "Configure either <content> or <input>, not both — <content> is the geography "
                            + "spelling of the same thing (and enables HTML cards).");
        }
        Path output = resolve(outputDirectory);
        Geography geo = geography();
        getLog().info(geo.describe());

        BookBuild.PlannedBook plan = null;
        Path declaredRoot = null;
        boolean contentMode = false;
        Path walkFrom = input == null ? null : resolve(input);
        if (content != null) {
            walkFrom = resolve(content);
            contentMode = true;
        } else if (walkFrom == null && book == null) {
            // Convention over configuration — see Geography.
            if (geo.content() != null) {
                walkFrom = geo.content();
                contentMode = true;
            } else if (geo.home() != null) {
                walkFrom = geo.home();   // home without a content/: legacy walk
            }
        }
        if (contentMode) declaredRoot = walkFrom;
        if (book != null) {
            declaredRoot = bookRoot(book, geo);
            if (book.declaresCardSelection()) {
                try {
                    plan = new BookBuild.PlannedBook(declaredRoot, book.toSpecs(), book.tocAfterSpec(),
                            book.pageMarkers());
                } catch (IllegalArgumentException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            } else {
                walkFrom = declaredRoot;   // config declared, structure from the tree
            }
        }
        BookSource.Resolved source = contentMode
                ? BookSource.walkContent(walkFrom, siteTarget, getLog())
                : BookSource.of(walkFrom, plan, siteTarget, getLog());
        Path bookDir = source.root();
        List<Path> cardFiles = source.cardFiles();

        // Book root captured once (from the first card); the preprocessor is
        // rebuilt per card since it binds that card's vars at construction.
        ConfigLoader configLoader = new ConfigLoader();
        CardLoader cardLoader = null;      // see BookBuild: needs the book root
        ThemeBundle theme = null;
        BlockTemplates blockTemplates = null;
        Map<String, Map<String, Object>> providerConfig = includeProviderConfig();
        List<Card> cards = new ArrayList<>(cardFiles.size());
        List<RenderContext> contexts = new ArrayList<>(cardFiles.size());
        RenderContext bookCtx = null;
        for (Path cardFile : cardFiles) {
            RenderContext ctx = configLoader.load(
                    cardFile, siteTarget, pageSize, resolveMargins(), declaredRoot, geo.home(),
                    declaredVars());
            if (bookCtx == null) bookCtx = ctx;
            if (cardLoader == null) cardLoader = new CardLoader(bookCtx.book().bookRoot());
            if (theme == null) {
                theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDirPath());
                blockTemplates = new BlockTemplates(theme.templateLoader(),
                        geo.layouts() != null ? geo.layouts()
                                : bookCtx.book().bookRoot().resolve("layouts"),
                        BlockRenderers.discover(getLog()),
                        geo.home() != null ? geo.home() : bookCtx.book().bookRoot());
            }
            contexts.add(ctx);
            MarkdownPreprocessor preprocessor = Includes.defaultPreprocessor(
                    bookCtx.book().bookRoot(), geo.layouts(), providerConfig, ctx.vars());
            cards.add(CardLoading.load(cardLoader, preprocessor, cardFile, ctx.book().cardSchema(),
                    ctx.vars(), getLog(), blockTemplates));
        }

        CardLoading.requireUniqueIds(cards, bookDir);

        // Book-level config and sections declared in the POM, applied exactly as
        // in a build: the site's title, landing pages and nav then match the
        // PDF's cover and dividers.
        if (book != null && book.declaresBookConfig()) {
            bookCtx = bookCtx.withBook(book.mergeInto(bookCtx.book(),
                    geo.home() != null ? geo.home() : bookCtx.book().bookRoot()));
        }
        if (!source.sections().isEmpty()) {
            bookCtx = bookCtx.withBook(bookCtx.book().withSections(source.sections()));
        }

        if (theme == null) theme = Themes.resolve(themeName, bookCtx.book().theme(), themeDirPath());
        LayoutEngine layout = geo.layouts() != null
                ? new LayoutEngine(bookCtx.book().bookRoot(), geo.layouts(), theme)
                : new LayoutEngine(bookCtx.book().bookRoot(), theme);
        layout.setExtraCss(stylesheetPaths());
        layout.setWatermark(watermark(bookCtx));
        layout.setSectionBodies(SectionBodies.render(
                bookCtx, geo.layouts(), providerConfig, cards, "site", siteTarget));
        Map<String, String> pages;
        try {
            pages = layout.renderSite(cards, contexts, bookCtx);
        } catch (SlotPlacementException e) {
            // A structural check, not a crash: a slot-using layout couldn't
            // place every block.
            throw new MojoFailureException(e.getMessage(), e);
        } catch (dev.noregressions.paperband.layout.CardLinkException e) {
            // Likewise a content check: a card: link names a card that isn't
            // in the book.
            throw new MojoFailureException(e.getMessage(), e);
        }

        Files.createDirectories(output);
        Path cardsDir = output.resolve("cards");
        if (clean && Files.isDirectory(cardsDir)) {
            try (var walk = Files.walk(cardsDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // Best effort: a leftover file is not worth failing the build.
                            }
                        });
            }
        }
        Files.createDirectories(cardsDir);

        // Page keys are output-relative paths; refuse any that would resolve
        // outside the output directory (defence in depth — card ids are
        // already validated at load time).
        Path outputRoot = output.toAbsolutePath().normalize();
        int written = 0;
        for (Map.Entry<String, String> entry : pages.entrySet()) {
            Path pageFile = output.resolve(entry.getKey()).normalize();
            if (!pageFile.toAbsolutePath().startsWith(outputRoot)) {
                throw new MojoExecutionException(
                        "refusing to write site page outside output directory: "
                                + entry.getKey() + " -> " + pageFile);
            }
            Files.createDirectories(pageFile.getParent());
            Files.writeString(pageFile, entry.getValue(), StandardCharsets.UTF_8);
            written++;
        }

        written += copyMatterAssets(bookCtx, output, watermark(bookCtx));
        written += copyContentAssets(layout, bookCtx, output);

        getLog().info("Built site " + bookDir + " -> " + output
                + " (" + written + " pages, " + cards.size() + " cards)");
    }

    /**
     * The watermark the site should paint, or null for none.
     *
     * <p>Resolved here rather than left to the layout engine's own
     * {@code vars.watermark} fallback so that the knob parameters reach a stamp
     * the book's yaml declared: {@code -Dpaperband.watermarkOpacity=0.3} on a
     * yaml {@code DRAFT} has to mean the same thing for the site as it does for
     * the PDF.
     *
     * <p>{@code pages:} and {@code font:} have no meaning for a web page —
     * there is no page one and no embedded font — so the site declares neither.
     *
     * @param bookCtx the resolved book context, for its vars
     * @return the resolved spec, or null
     * @throws MojoExecutionException if a parameter carries an unusable value
     */
    private Watermark watermark(RenderContext bookCtx) throws MojoExecutionException {
        Watermark base = Watermarks.base(watermark, watermarkText, watermarkImage);
        if (base == null && bookCtx != null) {
            base = Watermark.fromYaml(bookCtx.vars().get("watermark"));
        }
        if (base == null) return null;
        return base.withOverrides(Watermarks.overrides(watermark,
                watermarkColor, watermarkOpacity, watermarkAngle, watermarkFontSize, watermarkBold,
                watermarkScale, watermarkFit, watermarkBehind, watermarkTile, null, null));
    }

    /**
     * Copy the content images the cards reference into the site's
     * {@code assets/} directory.
     *
     * <p>The counterpart to {@code LayoutEngine.withContentAssets}, which
     * already rewrote every local {@code <img src>} to
     * {@code <urlPrefix>assets/<path>} and recorded what it pointed at. Only
     * referenced files are copied, and the book's tree is mirrored under
     * {@code assets/} so that two cards can each keep a {@code diagram.png}
     * beside them.
     *
     * <p>A ref with no file behind it was left in the markup as the author
     * wrote it, so it is a warning rather than a failure — the same call the
     * cover makes. A missing screenshot should not cost you the docs, and the
     * broken image is visible on the page it belongs to.
     *
     * @param layout  the engine that rendered the pages
     * @param bookCtx the resolved book context
     * @param output  the site output directory
     * @return the number of assets copied
     * @throws IOException if a referenced image exists but can't be copied
     */
    private int copyContentAssets(LayoutEngine layout, RenderContext bookCtx, Path output)
            throws IOException {
        for (String missing : layout.siteMissingAssets()) {
            getLog().warn("content image not found, left as written: " + missing);
        }
        Set<String> refs = layout.siteContentAssets();
        if (refs.isEmpty()) return 0;

        Path bookRoot = bookCtx.book().bookRoot().toAbsolutePath().normalize();
        Path assetsRoot = output.resolve(LayoutEngine.SITE_ASSET_DIR).toAbsolutePath().normalize();
        int copied = 0;
        for (String rel : refs) {
            Path src = bookRoot.resolve(rel).normalize();
            Path dest = assetsRoot.resolve(rel).normalize();
            // The engine already refused anything resolving outside the book,
            // but the destination is a fresh join and gets its own check —
            // the same defence in depth the page writer above applies.
            if (!dest.startsWith(assetsRoot)) {
                throw new IOException("refusing to write asset outside "
                        + LayoutEngine.SITE_ASSET_DIR + ": " + rel);
            }
            if (!Files.isRegularFile(src)) {
                // Vanished between render and copy; nothing to fail over.
                getLog().warn("content image disappeared during the build: " + src);
                continue;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }

    /**
     * Copy the images a book's {@code cover:}, {@code back:} and
     * {@code watermark:} declare into the site's {@code assets/} directory.
     *
     * <p>The PDF resolves those images to absolute {@code file:} URIs, which a
     * served site can't follow. The site references
     * {@code assets/&lt;filename&gt;} instead (see {@code LayoutEngine.siteMatter})
     * and this puts the file there.
     *
     * @param bookCtx the resolved book context
     * @param output  the site output directory
     * @param watermarkOverride the resolved watermark, when the book or the
     *                          goal declared one
     * @return the number of assets copied
     * @throws IOException if a declared image exists but can't be copied
     */
    private int copyMatterAssets(RenderContext bookCtx, Path output, Watermark watermarkOverride)
            throws IOException {
        Path bookRoot = bookCtx.book().bookRoot();
        List<dev.noregressions.paperband.model.PageMatter> matters = new ArrayList<>();
        if (bookCtx.book().cover() != null) matters.add(bookCtx.book().cover());
        if (bookCtx.book().back() != null) matters.add(bookCtx.book().back());

        int copied = 0;
        // An image watermark is referenced the same way — LayoutEngine writes
        // assets/<filename> into every page — so it needs the same copy.
        if (watermarkOverride != null && watermarkOverride.hasImage()) {
            Path src = bookRoot.resolve(watermarkOverride.image()).normalize();
            if (Files.isRegularFile(src)) {
                Path dest = output.resolve(LayoutEngine.SITE_ASSET_DIR).resolve(src.getFileName());
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } else {
                getLog().warn("watermark image not found, site pages will show no watermark: " + src);
            }
        }
        for (dev.noregressions.paperband.model.PageMatter matter : matters) {
            if (matter.image() == null) continue;
            Path src = bookRoot.resolve(matter.image()).normalize();
            if (!Files.isRegularFile(src)) {
                // A cover image the PDF would also fail on: warn rather than
                // fail the site, so a broken image doesn't cost you the docs.
                getLog().warn("cover/back image not found, skipping for the site: " + src);
                continue;
            }
            Path dest = output.resolve(LayoutEngine.SITE_ASSET_DIR).resolve(src.getFileName());
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }
}
