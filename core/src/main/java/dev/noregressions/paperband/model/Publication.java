package dev.noregressions.paperband.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code publication:} block of a book root {@code pagewright.yaml}:
 * named editions cut from one card source, each resolving its build settings
 * against a shared {@link Defaults} block (see DESIGN-publications.md).
 *
 * <p>Resolution rule: scalar edition keys override defaults keys;
 * {@code vars} maps merge with edition entries winning; {@code classes} is
 * edition-only; {@code pages} contract fields cascade per field. The
 * resolved settings for one edition are exposed via
 * {@link Edition#resolve(Defaults)} so the CLI never re-implements the
 * cascade.
 *
 * @param defaults  shared settings root; never null (empty defaults if absent)
 * @param editions  declared editions, in declaration order; never null
 */
public record Publication(Defaults defaults, List<Edition> editions) {

    public Publication {
        defaults = defaults == null ? Defaults.empty() : defaults;
        editions = editions == null ? List.of() : List.copyOf(editions);
    }

    /** Find a declared edition by id. */
    public Edition edition(String id) {
        for (Edition e : editions) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }

    /**
     * Card selection for one edition. A card is kept when its id appears in
     * {@link #cards} (explicit inclusion), OR when a query is present
     * ({@link #fields} and/or {@link #where}) and every field clause matches
     * and the predicate evaluates truthy — the union of the hand-picked list
     * with the query result. Empty select means every card.
     *
     * @param fields frontmatter equality clauses (field → value, AND-ed)
     * @param cards  explicit card ids, unioned with the query result
     * @param where  Pebble predicate over {@code card} (id, title,
     *               frontmatter) and {@code vars}; evaluated by
     *               {@code PredicateEvaluator}
     */
    public record Select(Map<String, String> fields, List<String> cards, String where) {

        public Select {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            cards  = cards  == null ? List.of() : List.copyOf(cards);
        }

        public static Select all() {
            return new Select(Map.of(), List.of(), null);
        }

        public boolean isEmpty() {
            return fields.isEmpty() && cards.isEmpty() && (where == null || where.isBlank());
        }
    }

    /**
     * Page contract: build-time enforcement that belongs to the publication,
     * not the session (a page budget is a versioned property of the artefact).
     * Null fields mean "not declared"; per-field cascade against defaults.
     *
     * @param report     print the per-card page-count table after rendering
     * @param maxPerCard fail the edition if any card spans more pages
     */
    public record Pages(Boolean report, Integer maxPerCard) {

        public static Pages empty() {
            return new Pages(null, null);
        }

        /** Per-field cascade: this (edition) wins where declared, else defaults. */
        public Pages over(Pages base) {
            if (base == null) return this;
            return new Pages(
                    report != null ? report : base.report(),
                    maxPerCard != null ? maxPerCard : base.maxPerCard());
        }
    }

    /**
     * Shared settings root. All keys optional.
     *
     * @param theme    theme name (resolved via the normal theme chain)
     * @param size     page size name, e.g. {@code A5} (case-insensitive)
     * @param output   output path pattern; {@code {id}} expands to the edition id
     * @param themeDir user-theme directory, resolved against the book root
     * @param vars     vars merged topmost into the content cascade (see design doc)
     * @param pages    default page contract for every edition
     */
    public record Defaults(String theme, String size, String output, String themeDir,
                           Map<String, Object> vars, Pages pages) {

        public Defaults {
            vars  = vars  == null ? Map.of() : Map.copyOf(vars);
            pages = pages == null ? Pages.empty() : pages;
        }

        public static Defaults empty() {
            return new Defaults(null, null, null, null, Map.of(), Pages.empty());
        }
    }

    /**
     * One declared edition.
     *
     * @param id      required, unique within the publication
     * @param classes extra identity classes stamped on {@code <html>} beside
     *                {@code edition-{id}}; never null
     * @param title   optional display title, exposed as {@code edition.title}
     * @param select  card selection; empty means every card
     * @param theme   overrides {@code defaults.theme}
     * @param size    overrides {@code defaults.size}
     * @param output  overrides {@code defaults.output}
     * @param vars    merged over {@code defaults.vars}, edition wins
     * @param pages   per-field cascade over {@code defaults.pages}
     */
    public record Edition(String id, List<String> classes, String title,
                          Select select, String theme, String size,
                          String output, Map<String, Object> vars, Pages pages) {

        public Edition {
            classes = classes == null ? List.of() : List.copyOf(classes);
            select  = select  == null ? Select.all() : select;
            vars    = vars    == null ? Map.of()  : Map.copyOf(vars);
            pages   = pages   == null ? Pages.empty() : pages;
        }

        /** Effective settings for this edition against the publication defaults. */
        public Resolved resolve(Defaults d) {
            Map<String, Object> mergedVars = new LinkedHashMap<>(d.vars());
            mergedVars.putAll(vars);
            String out = output != null ? output : d.output();
            if (out == null) out = "{id}.pdf";
            return new Resolved(
                    id,
                    classes,
                    title,
                    select,
                    theme != null ? theme : d.theme(),
                    size  != null ? size  : d.size(),
                    out.replace("{id}", id),
                    d.themeDir(),
                    Map.copyOf(mergedVars),
                    pages.over(d.pages()));
        }
    }

    /** One edition's effective settings after resolution against defaults. */
    public record Resolved(String id, List<String> classes, String title,
                           Select select, String theme, String size,
                           String output, String themeDir, Map<String, Object> vars,
                           Pages pages) {
    }
}
