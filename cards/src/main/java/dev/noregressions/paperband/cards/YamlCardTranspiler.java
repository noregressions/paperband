package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.CardSchema;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transpiles a pure-YAML card file (one flat YAML document per card, no
 * markdown body) into ordinary markdown-card text, driven by the book's
 * {@link CardSchema}. The result feeds straight into
 * {@link CardLoader#parse(Path, String)}, so YAML cards flow through the whole
 * existing pipeline — includes/vars preprocessing, block extraction,
 * {@code DiffCardProcessor}, axis resolution, themes — with no special-casing
 * downstream of this class.
 *
 * <h2>Emission rules</h2>
 * <ul>
 *   <li><b>Frontmatter</b>: the schema's {@code frontmatter:} fields are
 *       copied verbatim (SnakeYAML re-dump) into a frontmatter block, in
 *       declared order. Fields a card doesn't have are skipped.</li>
 *   <li><b>Sections</b>: emitted in schema order; absent/empty fields are
 *       skipped, so optional fields need no special handling.
 *     <ul>
 *       <li>{@code heading:} → {@code ## Heading {.css-class}} (level from
 *           {@code level:}, class from {@code class:} falling back to the
 *           kebab-cased field name).</li>
 *       <li>{@code fence:} + string value → one fenced code block with that
 *           info string ({@code ```error-output} etc.).</li>
 *       <li>{@code fence:} + list value → one fence per item. Map items use
 *           the diff-card convention: {@code comment} → {@code # //} caption
 *           line, {@code removed}/{@code added} → {@code @@removed}/{@code
 *           @@added} bodies, {@code lang} → fence-name suffix
 *           ({@code ```diff-card-xml}).</li>
 *       <li>Prose string → paragraph (embedded inline HTML like
 *           {@code <code>} passes through markdown untouched).</li>
 *       <li>List of strings → bullet list.</li>
 *       <li>List of maps (no fence) → per item: {@code label}+{@code text}
 *           becomes a bolded lead-in followed by the text;
 *           {@code text}+{@code url} becomes a markdown link bullet; any
 *           other map falls back to {@code **key:** value} lines.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Fence lengths adapt to the content (a body containing ``` gets a longer
 * fence), so hostile-ish content can't break out of its code block.
 *
 * <h2>Per-card page-break hints ({@code layout:})</h2>
 * A reserved top-level key, sibling to the schema's own fields, lets one
 * specific card force a page-break preference on one of its own sections
 * without changing the book-wide {@code cardSchema:}:
 * <pre>
 * layout:
 *   how_to_fix: page-start    # force a fresh page before this section
 *   diffs: avoid-split        # keep this section from splitting across pages
 * </pre>
 * See {@link #BREAK_HINT_CLASSES} for the full explanation — it emits the
 * same {@code pw-page-start}/{@code pw-avoid-split} classes a hand-written
 * markdown card gets from Pandoc-attribute syntax ({@code
 * ## Heading {.pw-page-start}}), which every bundled theme's scaffold CSS
 * already understands.
 */
public final class YamlCardTranspiler {

    private final Yaml yaml;

    public YamlCardTranspiler() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(opts);
    }

    /**
     * Is {@code file} a YAML card candidate? True for {@code *.yaml} /
     * {@code *.yml} files that aren't a {@code paperband.yaml}/{@code .yml}
     * config file.
     */
    public static boolean isYamlCard(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) return false;
        return !lower.equals("paperband.yaml") && !lower.equals("paperband.yml");
    }

    /**
     * Transpile {@code yamlText} (the content of {@code source}) into markdown.
     *
     * @throws CardParseException when the YAML is not a flat mapping
     */
    public String transpile(Path source, String yamlText, CardSchema schema) {
        if (schema == null) {
            throw new CardParseException(source + ": YAML card found but the book root "
                    + "paperband.yaml declares no cardSchema — declare one to describe "
                    + "how YAML fields map onto frontmatter and sections");
        }
        Object loaded;
        try {
            loaded = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            throw new CardParseException(source + ": YAML parse failed: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new CardParseException(source + ": YAML card must be a mapping at top level");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) raw;

        StringBuilder md = new StringBuilder();
        emitFrontmatter(md, data, schema);
        for (CardSchema.Section section : schema.sections()) {
            Object value = data.get(section.field());
            if (isEmpty(value)) continue;
            emitSection(md, section, value, source, data);
        }
        return md.toString();
    }

    // ---- frontmatter ----

    private void emitFrontmatter(StringBuilder md, Map<String, Object> data, CardSchema schema) {
        Map<String, Object> fm = new LinkedHashMap<>();
        for (String field : schema.frontmatterFields()) {
            if (data.containsKey(field)) fm.put(field, data.get(field));
        }
        md.append("---\n");
        if (!fm.isEmpty()) md.append(yaml.dump(fm));
        md.append("---\n\n");
    }

    // ---- sections ----

    /**
     * Reserved top-level card key (sibling of the schema's frontmatter/section
     * fields, never itself a frontmatter or section field): a map of section
     * {@code field} name to a break hint, giving one specific card instance a
     * page-break preference on one of its own sections without touching the
     * book-wide {@code cardSchema:}. Same two hint strings {@code
     * SlotTracker.takeWithLayout} accepts, reused for consistency:
     *
     * <pre>
     * layout:
     *   how_to_fix: page-start    # force a fresh page before this section
     *   diffs: avoid-split        # keep this section from splitting across pages
     * </pre>
     *
     * Emits as an extra heading class ({@code pw-page-start}/{@code
     * pw-avoid-split}) alongside the section's normal {@code effectiveClass()}
     * — the same scaffold CSS every bundled theme already ships (see
     * {@code SlotTracker}'s javadoc) picks it up with no further wiring.
     */
    private static final Map<String, String> BREAK_HINT_CLASSES =
            Map.of("page-start", "pw-page-start", "avoid-split", "pw-avoid-split");

    private void emitSection(StringBuilder md, CardSchema.Section s, Object value, Path source,
                              Map<String, Object> data) {
        if (s.heading() != null) {
            String breakClass = breakClassFor(data, s.field(), source);
            md.append("#".repeat(s.level()))
              .append(' ').append(s.heading())
              .append(" {").append(headingClassAttr(s.effectiveClass(), breakClass)).append("}\n\n");
        }
        if (s.fence() != null) {
            emitFenced(md, s, value, source);
        } else {
            emitProse(md, s, value, source);
        }
    }

    /** @return the resolved {@code pw-page-start}/{@code pw-avoid-split} class, or null if this field has no {@code layout:} entry */
    private String breakClassFor(Map<String, Object> data, String field, Path source) {
        Object layoutNode = data.get("layout");
        if (!(layoutNode instanceof Map<?, ?> layout)) return null;
        Object hint = layout.get(field);
        if (hint == null) return null;
        String key = hint.toString().trim();
        String cls = BREAK_HINT_CLASSES.get(key);
        if (cls == null) {
            throw new CardParseException(source + ": layout." + field + ": unknown break hint '"
                    + key + "' (expected page-start or avoid-split)");
        }
        return cls;
    }

    private static String headingClassAttr(String effectiveClass, String breakClass) {
        return breakClass == null
                ? "." + effectiveClass
                : "." + effectiveClass + " ." + breakClass;
    }

    private void emitFenced(StringBuilder md, CardSchema.Section s, Object value, Path source) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    emitDiffFence(md, s.fence(), m);
                } else if (item != null) {
                    emitPlainFence(md, s.fence(), item.toString());
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> m) {
            emitDiffFence(md, s.fence(), m);
            return;
        }
        emitPlainFence(md, s.fence(), value.toString());
    }

    private void emitPlainFence(StringBuilder md, String fence, String body) {
        String content = stripTrailingNewlines(body);
        String marks = fenceFor(content);
        md.append(marks).append(fence).append('\n')
          .append(content).append('\n')
          .append(marks).append("\n\n");
    }

    /**
     * Map-shaped fence item, using the diff-card body convention understood
     * by {@code DiffCardProcessor}: optional {@code comment} caption, then
     * {@code @@removed} / {@code @@added} blocks. An optional {@code lang}
     * key becomes a fence-name suffix ({@code diff-card-xml}).
     */
    private void emitDiffFence(StringBuilder md, String fence, Map<?, ?> item) {
        String lang    = str(item.get("lang"));
        String comment = str(item.get("comment"));
        String removed = str(item.get("removed"));
        String added   = str(item.get("added"));

        StringBuilder body = new StringBuilder();
        if (comment != null) {
            // DiffCardProcessor strips a leading '#' then '//' to form the caption.
            String c = comment.strip();
            if (!c.startsWith("#")) c = "# " + c;
            body.append(c).append('\n');
        }
        if (removed != null) {
            body.append("@@removed\n").append(stripTrailingNewlines(removed)).append('\n');
        }
        if (added != null) {
            body.append("@@added\n").append(stripTrailingNewlines(added)).append('\n');
        }
        String content = stripTrailingNewlines(body.toString());
        String info = lang == null ? fence : fence + "-" + lang;
        String marks = fenceFor(content);
        md.append(marks).append(info).append('\n')
          .append(content).append('\n')
          .append(marks).append("\n\n");
    }

    private void emitProse(StringBuilder md, CardSchema.Section s, Object value, Path source) {
        if (value instanceof List<?> list) {
            boolean allScalar = list.stream().noneMatch(i -> i instanceof Map || i instanceof List);
            if (allScalar) {
                for (Object item : list) {
                    if (item == null) continue;
                    md.append("- ").append(indentContinuations(item.toString().strip())).append('\n');
                }
                md.append('\n');
            } else {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        emitProseMap(md, m);
                    } else if (item != null) {
                        md.append(item.toString().strip()).append("\n\n");
                    }
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> m) {
            emitProseMap(md, m);
            return;
        }
        md.append(value.toString().strip()).append("\n\n");
    }

    /**
     * Prose rendering for a map item. Two common shapes get first-class
     * treatment; anything else degrades to labelled lines.
     */
    private void emitProseMap(StringBuilder md, Map<?, ?> m) {
        String label = str(m.get("label"));
        String text  = str(m.get("text"));
        String url   = str(m.get("url"));

        if (text != null && url != null && label == null) {
            md.append("- [").append(text.strip()).append("](").append(url.strip()).append(")\n\n");
            return;
        }
        if (label != null) {
            md.append("**").append(label.strip()).append("**\n\n");
            if (text != null) md.append(text.strip()).append("\n\n");
            return;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            md.append("**").append(e.getKey()).append(":** ")
              .append(e.getValue() == null ? "" : e.getValue().toString().strip())
              .append("\n\n");
        }
    }

    // ---- helpers ----

    private static boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof List<?> l) return l.isEmpty();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    /** A backtick fence at least one longer than any backtick run in {@code content} (min 3). */
    private static String fenceFor(String content) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                if (run > longest) longest = run;
            } else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    /** Indent embedded newlines so multi-line bullet items stay inside their list item. */
    private static String indentContinuations(String s) {
        return s.replace("\r\n", "\n").replace("\n", "\n  ");
    }
}
