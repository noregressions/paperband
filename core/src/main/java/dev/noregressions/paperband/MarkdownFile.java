package dev.noregressions.paperband;

import org.commonmark.node.CustomBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A block in the combined AST that represents one source markdown file.
 * Its children are the top-level blocks parsed from that file (starting with a
 * {@link FrontMatterBlock}, if the file had front matter), and the front matter
 * is also exposed here as a map for convenient access. It also holds the handles
 * ({@link Attributes}) that {@code {! ...}} markers gave its nodes.
 */
public class MarkdownFile extends CustomBlock {

    private final Path path;
    private final Map<String, Object> frontMatter;
    private final Map<Node, Attributes> attributes;
    private Map<Heading, String> headingIds;

    public MarkdownFile(Path path, Map<String, Object> frontMatter) {
        this(path, frontMatter, Map.of());
    }

    public MarkdownFile(Path path, Map<String, Object> frontMatter, Map<Node, Attributes> attributes) {
        this.path = path;
        this.frontMatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontMatter));
        this.attributes = Collections.unmodifiableMap(new IdentityHashMap<>(attributes));
    }

    public Path getPath() {
        return path;
    }

    /** Parsed YAML: values are strings, numbers, booleans, dates, lists or maps. */
    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    /** The handles a marker gave {@code node}, or null. */
    public Attributes getAttributes(Node node) {
        return attributes.get(node);
    }

    /** The handles a marker gave {@code node}, looked up in the file it belongs to; null if none. */
    public static Attributes attributesOf(Node node) {
        for (Node p = node; p != null; p = p.getParent()) {
            if (p instanceof MarkdownFile f) {
                return f.getAttributes(node);
            }
        }
        return null;
    }

    /**
     * Every id a link within this file can target: explicit {@code id=} handles and the ids of
     * top-level headings ({@link #headingId}).
     */
    public java.util.Set<String> anchors() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        attributes.values().stream().map(Attributes::id).filter(java.util.Objects::nonNull).forEach(out::add);
        for (Node n = getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h) {
                out.add(headingId(h));
            }
        }
        return out;
    }

    /**
     * The id of one of this file's top-level headings: its explicit {@code id=}, or else a slug of
     * its text ({@code "Key points"} → {@code key-points}), numbered if repeated, never clashing
     * with an explicit id. Used for section ids by {@link Site} and the layout selectors.
     */
    public String headingId(Heading heading) {
        if (headingIds == null) {
            Map<Heading, String> ids = new IdentityHashMap<>();
            Set<String> taken = new HashSet<>();
            for (Node n = getFirstChild(); n != null; n = n.getNext()) {
                Attributes a = attributes.get(n);
                if (n instanceof Heading h && a != null && a.id() != null) {
                    ids.put(h, a.id());
                    taken.add(a.id());
                }
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Node n = getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof Heading h && !ids.containsKey(h)) {
                    String slug = Site.slug(Site.text(h));
                    String id = slug;
                    while (taken.contains(id)) {
                        id = slug + "-" + counts.merge(slug, 1, Integer::sum);
                    }
                    taken.add(id);
                    ids.put(h, id);
                }
            }
            headingIds = ids;
        }
        String id = headingIds.get(heading);
        return id != null ? id : Site.slug(Site.text(heading));
    }
}
