package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One declared section of a book: a titled group of top-level folders, from a
 * book root {@code sections:} key.
 *
 * <p>Declaring sections makes the book's top-level structure <em>declared</em>
 * rather than inferred. Without a declaration, a section is discovered — every
 * top-level folder under the book root becomes its own group, labelled from
 * that folder's own {@code paperband.yaml} {@code title:} (see
 * {@code LayoutEngine.sectionIdFor}). A declared section instead names several
 * folders and gives the group a single title, so one divider page can front a
 * run of folders that belong together:
 *
 * <pre>
 * sections:
 *   - title: "Foundations"
 *     folders:
 *       - 01-getting-started
 *       - 02-authoring
 *   - id: reference
 *     title: "Reference"
 *     folders:
 *       - 03-configuration
 *     landing:
 *       template: minimal
 *   - title: "Appendix"
 *     folders:
 *       - 99-appendix
 *     landing: false        # group and order, but no divider/landing page
 * </pre>
 *
 * <p>Declared and discovered sections share one id namespace and one rendering
 * path: a declared section's id is used exactly where a discovered one's would
 * be (divider pages, site landing pages, nav and sidebar entries), so a folder
 * claimed by a declaration reports the declared id instead of its own folder
 * name. Top-level folders no declaration claims keep behaving as discovered
 * sections — that's the "mix of discovery and declaration": declare the
 * folders whose grouping matters, let the rest fall out of the directory
 * layout.
 *
 * <p>The declaration order in {@code sections:} also drives the order the book
 * walker emits those folders in, ahead of any unclaimed top-level content (see
 * {@code BookWalker}).
 *
 * @param id      section identifier, used wherever a section id appears — so
 *                it must be filename-safe (it becomes {@code <id>.html} on the
 *                static site). Defaults to {@link #slug(String)} of the title
 *                when {@code sections:} doesn't declare one explicitly.
 * @param title   human-readable section title, shown on the divider and
 *                landing page. Takes the place of a discovered section's
 *                per-folder {@code title:}.
 * @param folders top-level folder names this section claims, relative to the
 *                book root (or to a {@code content/} wrapper, matching the
 *                section id derivation), in the order they should be emitted
 * @param landingTemplate optional per-section landing/divider template, already
 *                resolved to a bare Pebble template name by
 *                {@link NamedTemplates#resolveSectionTemplate}; null falls
 *                back to the book-wide {@code sections.landing.template}
 *                default and then the built-in {@code site-section}. Mirrors
 *                a section folder's own {@code landing.template} override.
 * @param cards   absolute paths of the individual card files this section
 *                claims, for sections whose membership is declared by
 *                <em>pattern</em> rather than by folder — the Maven plugin's
 *                {@code <book><sections>} element resolves its globs to
 *                exactly these files. Empty for a yaml-declared
 *                {@code sections:} entry, which claims whole folders. A
 *                claimed card reports this section's id regardless of which
 *                folder it sits in, so two declared sections can draw
 *                different files out of one directory — something
 *                {@link #folders} alone cannot express (see
 *                {@code LayoutEngine.sectionIdFor}, which consults this
 *                first).
 * @param landingPage whether this section gets a page of its own — the PDF
 *                divider before its first card, and the {@code <id>.html}
 *                landing page on the static site. True for every section
 *                unless a declaration opts out ({@code landing: false} on a
 *                yaml {@code sections:} entry, or the Maven plugin's
 *                {@code <section><landingPage>false</landingPage></section>}),
 *                in which case the section still groups and orders its cards,
 *                and still labels them in the nav and sidebar, but contributes
 *                no page and no link to one. Discovered sections are always
 *                true — only a declared section can opt out.
 */
public record Section(
        String id,
        String title,
        List<String> folders,
        String landingTemplate,
        List<Path> cards,
        boolean landingPage
) {

    public Section {
        folders = folders == null ? List.of() : List.copyOf(folders);
        cards   = cards   == null ? List.of() : List.copyOf(cards);
    }

    /**
     * Convenience constructor for a section that gets its own page — the
     * default for every declaration that doesn't say otherwise.
     *
     * @param id              section identifier
     * @param title           human-readable section title
     * @param folders         top-level folder names this section claims
     * @param landingTemplate optional resolved landing template name
     * @param cards           card files this section claims by pattern
     */
    public Section(String id, String title, List<String> folders, String landingTemplate,
                   List<Path> cards) {
        this(id, title, folders, landingTemplate, cards, true);
    }

    /**
     * Convenience constructor for a folder-claiming section — the shape every
     * {@code sections:} entry in a {@code paperband.yaml} produces, and of
     * this record before pattern-declared sections existed.
     *
     * @param id              section identifier
     * @param title           human-readable section title
     * @param folders         top-level folder names this section claims
     * @param landingTemplate optional resolved landing template name
     */
    public Section(String id, String title, List<String> folders, String landingTemplate) {
        this(id, title, folders, landingTemplate, List.of());
    }

    /**
     * Does this section claim {@code source} as one of its declared card
     * files? Both sides are compared as normalised absolute paths, so a plan
     * built from relative patterns still matches the absolute source path a
     * loaded {@link Card} carries.
     *
     * @param source a card's source path; may be null
     * @return true when {@link #cards} contains it
     */
    public boolean claims(Path source) {
        if (source == null || cards.isEmpty()) return false;
        Path abs = source.toAbsolutePath().normalize();
        for (Path c : cards) {
            if (c.toAbsolutePath().normalize().equals(abs)) return true;
        }
        return false;
    }

    /**
     * Derive a filename-safe id from a section title — {@code "Getting
     * Started!"} &rarr; {@code "getting-started"}. Runs of characters that
     * aren't ASCII letters or digits collapse to a single {@code -}, which is
     * then trimmed from both ends.
     *
     * @param title the section title; may be null
     * @return the slug, or null when {@code title} is null or slugs to nothing
     */
    public static String slug(String title) {
        if (title == null) return null;
        String s = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? null : s;
    }
}
