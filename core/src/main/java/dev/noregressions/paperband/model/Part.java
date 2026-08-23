package dev.noregressions.paperband.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One declared part of a book: a titled group of top-level folders, from a
 * book root {@code parts:} key.
 *
 * <p>Parts make the book's top-level structure <em>declared</em> rather than
 * inferred. Without them, a "section" is discovered — every top-level folder
 * under the book root becomes its own group, labelled from that folder's own
 * {@code paperband.yaml} {@code title:} (see
 * {@code LayoutEngine.sectionIdFor}). A part instead names several folders and
 * gives the group a single title, so one divider page can front a run of
 * folders that belong together:
 *
 * <pre>
 * parts:
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
 * </pre>
 *
 * <p>Parts and discovered sections share one id namespace and one rendering
 * path: a part id is used exactly where a section id would be (divider pages,
 * site landing pages, nav and sidebar entries), so a folder claimed by a part
 * reports the part's id instead of its own folder name. Top-level folders no
 * part claims keep behaving as discovered sections — that's the "mix of
 * discovery and declaration": declare the folders whose grouping matters, let
 * the rest fall out of the directory layout.
 *
 * <p>The part order in {@code parts:} also drives the order the book walker
 * emits those folders in, ahead of any unclaimed top-level content (see
 * {@code BookWalker}).
 *
 * @param id      part identifier, used as the group id wherever a section id
 *                would be — so it must be filename-safe (it becomes
 *                {@code <id>.html} on the static site). Defaults to
 *                {@link #slug(String)} of the title when {@code parts:}
 *                doesn't declare one explicitly.
 * @param title   human-readable part title, shown on the divider and landing
 *                page. Takes the place of a discovered section's per-folder
 *                {@code title:}.
 * @param folders top-level folder names this part claims, relative to the
 *                book root (or to a {@code content/} wrapper, matching the
 *                section id derivation), in the order they should be emitted
 * @param landingTemplate optional per-part landing/divider template, already
 *                resolved to a bare Pebble template name by
 *                {@link NamedTemplates#resolveSectionTemplate}; null falls
 *                back to the book-wide {@code sections.landing.template}
 *                default and then the built-in {@code site-section}. Mirrors
 *                a section folder's own {@code landing.template} override.
 * @param cards   absolute paths of the individual card files this part
 *                claims, for parts whose membership is declared by
 *                <em>pattern</em> rather than by folder — the Maven plugin's
 *                {@code <book><parts>} element resolves its globs to exactly
 *                these files. Empty for a yaml-declared {@code parts:} entry,
 *                which claims whole folders. A claimed card reports this
 *                part's id regardless of which folder it sits in, so two
 *                parts can draw different files out of one directory —
 *                something {@link #folders} alone cannot express (see
 *                {@code LayoutEngine.sectionIdFor}, which consults this
 *                first).
 * @param landingPage whether this part gets a page of its own — the PDF
 *                divider before its first card, and the {@code <id>.html}
 *                landing page on the static site. True for every part unless
 *                a declaration opts out (the Maven plugin's
 *                {@code <part><landingPage>false</landingPage></part>}), in
 *                which case the part still groups and orders its cards, and
 *                still labels them in the nav and sidebar, but contributes no
 *                page and no link to one. Discovered sections are always
 *                true — only a declared part can opt out.
 */
public record Part(
        String id,
        String title,
        List<String> folders,
        String landingTemplate,
        List<Path> cards,
        boolean landingPage
) {

    public Part {
        folders = folders == null ? List.of() : List.copyOf(folders);
        cards   = cards   == null ? List.of() : List.copyOf(cards);
    }

    /**
     * Convenience constructor for a part that gets its own page — the default
     * for every declaration that doesn't say otherwise.
     *
     * @param id              part identifier
     * @param title           human-readable part title
     * @param folders         top-level folder names this part claims
     * @param landingTemplate optional resolved landing template name
     * @param cards           card files this part claims by pattern
     */
    public Part(String id, String title, List<String> folders, String landingTemplate,
                List<Path> cards) {
        this(id, title, folders, landingTemplate, cards, true);
    }

    /**
     * Convenience constructor for a folder-claiming part — the shape every
     * {@code parts:} entry in a {@code paperband.yaml} produces, and of this
     * record before pattern-declared parts existed.
     *
     * @param id              part identifier
     * @param title           human-readable part title
     * @param folders         top-level folder names this part claims
     * @param landingTemplate optional resolved landing template name
     */
    public Part(String id, String title, List<String> folders, String landingTemplate) {
        this(id, title, folders, landingTemplate, List.of());
    }

    /**
     * Does this part claim {@code source} as one of its declared card files?
     * Both sides are compared as normalised absolute paths, so a plan built
     * from relative patterns still matches the absolute source path a loaded
     * {@link Card} carries.
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
     * Derive a filename-safe id from a part title — {@code "Getting
     * Started!"} &rarr; {@code "getting-started"}. Runs of characters that
     * aren't ASCII letters or digits collapse to a single {@code -}, which is
     * then trimmed from both ends.
     *
     * @param title the part title; may be null
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
