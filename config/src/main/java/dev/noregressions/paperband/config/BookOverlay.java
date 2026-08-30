package dev.noregressions.paperband.config;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.PageMatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Book-level config declared somewhere other than the book's own
 * {@code paperband.yaml} — in practice a build tool: the Maven plugin's
 * {@code <book>} element.
 *
 * <p>This is the <em>second layer</em> of the book scope, and the only place
 * that decides what happens when both layers speak. Precedence lives here and
 * nowhere else:
 *
 * <ul>
 *   <li><b>Declared wins over yaml</b>, field by field. A declaration is not a
 *       default — the POM is the file the author just edited, and a build that
 *       says {@code <title>} means it. Fields are independent: declaring a
 *       title doesn't clear a yaml-declared cover.</li>
 *   <li><b>vars merge</b> rather than replace, with declared entries on top,
 *       because vars is a map of many independent keys and replacing the map
 *       would silently drop every key the declaration didn't restate.</li>
 *   <li><b>Sections are not handled here.</b> They're resolved from patterns
 *       against the filesystem, not copied across, so the build applies them
 *       separately (see {@code BookConfig.withSections}).</li>
 * </ul>
 *
 * <p>Card-scope config takes the opposite rule and is deliberately not
 * expressible here: {@code <book><vars>} enters the cascade at the book's own
 * level (see {@code ConfigLoader.load}'s {@code declaredVars}), so a folder
 * yaml still overrides it. The POM outranks the root yaml; depth outranks the
 * POM. Book scope has no depth, so the two rules coincide and the declaration
 * simply wins.
 *
 * <p>Build tools keep their own parsing — the Maven plugin's {@code <book>} is
 * still populated from XML by Maven's configurator, and its element classes
 * still live in the plugin. What they hand over is this: values, already in
 * model types, with no opinion about who wins. That opinion is this class's.
 */
public final class BookOverlay {

    private final String title;
    private final List<Axis> axes;
    private final String sectionLandingTemplate;
    private final PageMatter cover;
    private final PageMatter back;
    private final PageMatter footer;
    private final PageMatter header;
    private final Map<String, Object> vars;

    private BookOverlay(Builder b) {
        this.title = b.title;
        this.axes = b.axes;
        this.sectionLandingTemplate = b.sectionLandingTemplate;
        this.cover = b.cover;
        this.back = b.back;
        this.footer = b.footer;
        this.header = b.header;
        this.vars = Map.copyOf(b.vars);
    }

    /** @return a new, empty builder */
    public static Builder builder() {
        return new Builder();
    }

    /** True when this overlay declares nothing at all, so applying it is a no-op. */
    public boolean isEmpty() {
        return title == null && axes == null && sectionLandingTemplate == null
                && cover == null && back == null && footer == null && header == null
                && vars.isEmpty();
    }

    /**
     * Layer this declaration onto the config the book's yaml produced.
     *
     * @param base the yaml-derived book config, or an empty one for a book
     *             with no yaml at all
     * @return the merged config; {@code base} unchanged when this is empty
     */
    public BookConfig applyTo(BookConfig base) {
        if (isEmpty()) return base;
        Map<String, Object> mergedVars = new LinkedHashMap<>(base.vars());
        mergedVars.putAll(vars);
        return new BookConfig(
                base.bookRoot(),
                title != null ? title : base.title(),
                axes != null ? List.copyOf(axes) : base.axes(),
                base.globalCss(),
                mergedVars,
                base.targets(),
                base.theme(),
                sectionLandingTemplate != null ? sectionLandingTemplate : base.sectionLandingTemplate(),
                base.cardSchema(),
                cover  != null ? cover  : base.cover(),
                back   != null ? back   : base.back(),
                footer != null ? footer : base.footer(),
                header != null ? header : base.header(),
                base.sections());
    }

    /** Collects declared values; every setter takes null for "not declared". */
    public static final class Builder {
        private String title;
        private List<Axis> axes;
        private String sectionLandingTemplate;
        private PageMatter cover;
        private PageMatter back;
        private PageMatter footer;
        private PageMatter header;
        private final Map<String, Object> vars = new LinkedHashMap<>();

        private Builder() {}

        /** @param v the declared title, or null @return this */
        public Builder title(String v) {
            this.title = v;
            return this;
        }

        /** @param v the declared axes; null or empty means "not declared" @return this */
        public Builder axes(List<Axis> v) {
            this.axes = v == null || v.isEmpty() ? null : new ArrayList<>(v);
            return this;
        }

        /** @param v the declared section landing template, or null @return this */
        public Builder sectionLandingTemplate(String v) {
            this.sectionLandingTemplate = v;
            return this;
        }

        /** @param v the declared cover, or null @return this */
        public Builder cover(PageMatter v) {
            this.cover = v;
            return this;
        }

        /** @param v the declared back page, or null @return this */
        public Builder back(PageMatter v) {
            this.back = v;
            return this;
        }

        /** @param v the declared running footer, or null @return this */
        public Builder footer(PageMatter v) {
            this.footer = v;
            return this;
        }

        /** @param v the declared running header, or null @return this */
        public Builder header(PageMatter v) {
            this.header = v;
            return this;
        }

        /**
         * Add a declared book var. Null values are ignored so a caller can
         * offer optional keys without branching at every call site.
         *
         * @param key the var name
         * @param value the value, or null to skip
         * @return this
         */
        public Builder var(String key, Object value) {
            if (value != null) vars.put(key, value);
            return this;
        }

        /** @param v declared vars; null is treated as empty @return this */
        public Builder vars(Map<String, Object> v) {
            if (v != null) vars.putAll(v);
            return this;
        }

        /** @return the immutable overlay */
        public BookOverlay build() {
            return new BookOverlay(this);
        }
    }
}
