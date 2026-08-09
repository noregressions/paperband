package dev.noregressions.paperband.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Declares how a pure-YAML card file maps onto the markdown card model, so
 * books whose cards live as structured YAML (one flat document per card, no
 * markdown body) can be built without pre-converting them.
 *
 * <p>Declared once in the book root {@code paperband.yaml}:
 * <pre>
 * cardSchema:
 *   frontmatter: [id, tier, title, effort, series]
 *   sections:
 *     - field: oneliner                  # no heading → intro block
 *     - field: error_output
 *       fence: error-output
 *     - field: what_changed
 *       heading: "What Changed"
 *     - field: diffs
 *       heading: "Fix"
 *       fence: diff-card
 *     - field: watch_out
 *       heading: "Watch Out"
 * </pre>
 *
 * <p>Semantics (implemented by {@code YamlCardTranspiler} in
 * {@code cards}, which turns a YAML card into markdown text and
 * hands it to the ordinary {@code CardLoader}):
 * <ul>
 *   <li>{@code frontmatter:} — fields copied verbatim into the transpiled
 *       card's YAML frontmatter, in declared order. {@code id} and
 *       {@code title} behave exactly as they do for markdown cards; axis
 *       fields (e.g. {@code tier}) resolve through the axis system unchanged.</li>
 *   <li>{@code sections:} — ordered list of body sections. Each entry names
 *       the YAML {@code field} it reads; fields absent from a given card are
 *       skipped silently (so optional fields just work).</li>
 *   <li>{@code heading:} — emitted as an H2 (or {@code level:} 2–6). Every
 *       section carries a stable CSS class: {@code class:} if given, else the
 *       field name kebab-cased. A section with no heading and no fence
 *       becomes intro prose.</li>
 *   <li>{@code fence:} — the field is emitted as a fenced code block of that
 *       info string instead of prose, e.g. {@code error-output} or
 *       {@code diff-card} (both already understood downstream by
 *       {@code DiffCardProcessor}). A list-valued field emits one fence per
 *       item; diff-card items may carry {@code comment}, {@code removed},
 *       {@code added} and {@code lang} keys.</li>
 * </ul>
 *
 * <p>This class lives in {@code core} (alongside
 * {@link NamedTemplates}) so that {@code config} (which parses it
 * from the book yaml) and {@code cards} (which consumes it during
 * transpilation) agree on one type without depending on each other.
 *
 * @param frontmatterFields YAML fields copied into the transpiled frontmatter, in order
 * @param sections          ordered body-section mappings
 */
public record CardSchema(
        List<String> frontmatterFields,
        List<Section> sections
) {

    public CardSchema {
        frontmatterFields = frontmatterFields == null ? List.of() : List.copyOf(frontmatterFields);
        sections          = sections          == null ? List.of() : List.copyOf(sections);
    }

    /**
     * One body-section mapping.
     *
     * @param field    YAML field name to read (required)
     * @param heading  heading text; null for an intro/heading-less section
     * @param cssClass explicit CSS class; null falls back to the kebab-cased field name
     * @param fence    fenced-code info string ({@code error-output}, {@code diff-card}, …);
     *                 null for prose
     * @param level    heading level 2–6; only meaningful when {@code heading} is set
     */
    public record Section(String field, String heading, String cssClass, String fence, int level) {

        public Section {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("cardSchema section missing required 'field'");
            }
            if (level < 2 || level > 6) level = 2;
        }

        /** Effective CSS class: explicit {@code class:} or the kebab-cased field name. */
        public String effectiveClass() {
            if (cssClass != null && !cssClass.isBlank()) return cssClass;
            return kebab(field);
        }
    }

    /**
     * Parse the {@code cardSchema:} node of a book root {@code paperband.yaml}.
     *
     * @param node the already-YAML-parsed value of the {@code cardSchema} key
     * @return parsed schema
     * @throws IllegalArgumentException when the node is malformed
     */
    public static CardSchema fromYaml(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("cardSchema must be a YAML mapping");
        }

        List<String> fm = new ArrayList<>();
        Object fmNode = map.get("frontmatter");
        if (fmNode instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) fm.add(item.toString());
            }
        } else if (fmNode != null) {
            throw new IllegalArgumentException("cardSchema.frontmatter must be a list of field names");
        }

        List<Section> sections = new ArrayList<>();
        Object secNode = map.get("sections");
        if (secNode instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException(
                            "cardSchema.sections entries must be mappings, got: " + item);
                }
                Object field = m.get("field");
                if (field == null) {
                    throw new IllegalArgumentException(
                            "cardSchema section missing required 'field': " + m);
                }
                String heading  = str(m.get("heading"));
                String cssClass = str(m.get("class"));
                String fence    = str(m.get("fence"));
                int level = 2;
                Object levelNode = m.get("level");
                if (levelNode instanceof Number n) {
                    level = n.intValue();
                } else if (levelNode != null) {
                    try { level = Integer.parseInt(levelNode.toString().trim()); }
                    catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "cardSchema section 'level' must be a number: " + levelNode);
                    }
                }
                sections.add(new Section(field.toString(), heading, cssClass, fence, level));
            }
        } else if (secNode != null) {
            throw new IllegalArgumentException("cardSchema.sections must be a list");
        }

        if (fm.isEmpty() && sections.isEmpty()) {
            throw new IllegalArgumentException(
                    "cardSchema declares neither 'frontmatter' fields nor 'sections'");
        }
        return new CardSchema(fm, sections);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    /** {@code error_output} → {@code error-output}; lowercased, non-alphanumerics collapsed to hyphens. */
    static String kebab(String field) {
        String lower = field.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append('-');
        }
        return sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
    }
}
