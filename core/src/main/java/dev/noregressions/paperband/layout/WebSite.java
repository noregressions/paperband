package dev.noregressions.paperband.layout;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a web document ({@link SiteHtmlRenderer} in {@link SiteHtmlRenderer.Mode#WEB}) into a
 * site: one HTML file per page, sharing one stylesheet and one script.
 * <ul>
 *   <li>The first page is the output's own file, e.g. {@code index.html}. Every other page goes
 *       beside it at its source's path, {@code .md} replaced by {@code .html}
 *       ({@code 01-shipping/02-sbom.md} → {@code 01-shipping/02-sbom.html}); a generated page,
 *       with no source, is named by its id.</li>
 *   <li>The document's styles go into {@value #CSS} and its scripts (the Copy buttons) into
 *       {@value #JS}, both beside the first page, and every page links them.</li>
 *   <li>A link to an id on another page points at that page ({@code 01-shipping/02-sbom.html}, or
 *       {@code ...html#id} for an element inside it), relative to the page it's on.</li>
 *   <li>Each page has a header linking the first page, and links to the previous and next pages,
 *       in sequence order; its title is its first {@code h1}, then the site's title.</li>
 *   <li>With side navigation ({@link Style#SIDE}), every page has the contents beside it:
 *       the contents page's own content ({@link Options#contents}), which then isn't a page of its
 *       own; or, without one, a list of every page. The current page is marked
 *       ({@code aria-current="page"}). On narrow screens a Contents button in the header shows it.</li>
 *   <li>With a navigation bar ({@link Style#TOP}), every page has a bar under the header: one entry
 *       per group of pages, opening a list of them. The groups are the contents page's headings
 *       (which then isn't a page of its own either), each a label shortened to what's before its
 *       colon ({@code Part 1: You think...} → {@code Part 1}); links before the first heading are
 *       entries of their own. Without a contents page, the groups are the pages' folders, or, all
 *       in one folder, every page is an entry. The current page, and its group, are marked.</li>
 * </ul>
 * A web output of one page isn't split: it stays one file that stands alone. The document is
 * rendered whole first, so element ids are already unique across the site and the link check
 * ({@code SiteBuilder}) sees every page.
 */
public final class WebSite {

    /** The shared stylesheet, beside the first page. */
    public static final String CSS = "site.css";
    /** The shared script, beside the first page; written only when there is one. */
    public static final String JS = "site.js";

    /** Where the navigation goes. */
    public enum Style {
        /** The contents beside every page. */
        SIDE,
        /** A bar under the header, a dropdown per group of pages. */
        TOP,
        /** No navigation but the previous and next links; the contents stays a page. */
        NONE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * How the site is navigated.
     *
     * @param style    where the navigation goes
     * @param contents the page shown as the navigation: its source file, or its layout's name;
     *                 null for a page whose layout is {@value #DEFAULT_CONTENTS}
     */
    public record Options(Style style, String contents) {

        /** The layout name a contents page has, unless an output names another page. */
        public static final String DEFAULT_CONTENTS = "contents";

        public static final Options DEFAULT = new Options(Style.SIDE, null);

        boolean side() {
            return style == Style.SIDE;
        }

        boolean top() {
            return style == Style.TOP;
        }

        /** Whether a page from {@code source} (or null) with layout {@code layout} is the contents page. */
        public boolean isContents(String source, String layout) {
            String c = contents == null ? DEFAULT_CONTENTS : contents;
            return c.equals(source) || c.equals(layout);
        }

        boolean isContents(Element section) {
            return isContents(section.attr("data-path"), section.attr("data-layout"));
        }
    }

    private WebSite() {
    }

    /** {@link #split(String, String, String, Options)} with the default side navigation. */
    public static Map<Path, String> split(String html, String title, String index) {
        return split(html, title, index, Options.DEFAULT);
    }

    /**
     * @param html  the whole web document
     * @param title the site's title
     * @param index the first page's file name, e.g. {@code index.html}
     * @return every file of the site, by path relative to the first page's directory, pages first in order
     */
    public static Map<Path, String> split(String html, String title, String index, Options options) {
        return split(html, title, index, options, Theme.DEFAULT);
    }

    /** @param theme where the styles of the site's own parts (header, previous and next, navigation) come from */
    public static Map<Path, String> split(String html, String title, String index, Options options, Theme theme) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);

        StringBuilder css = new StringBuilder(theme.css(Theme.Part.SITE)).append('\n');
        for (Element style : doc.select("style")) {
            css.append(style.data().strip()).append("\n\n");
            style.remove();
        }
        StringBuilder js = new StringBuilder();
        for (Element script : doc.select("script:not([src])")) {
            js.append(script.data().strip()).append("\n\n");
            script.remove();
        }

        List<Element> sections = new ArrayList<>(doc.select("body > section.page"));
        Element contents = null;
        if (options.style() != Style.NONE) {
            contents = sections.stream().filter(options::isContents).findFirst().orElse(null);
            if (contents != null && sections.size() > 1) {
                sections.remove(contents);
            } else {
                contents = null;
            }
            css.append(theme.css(options.side() ? Theme.Part.NAV_SIDE : Theme.Part.NAV_TOP)).append('\n');
            js.append(options.side() ? NAV_JS : TOPNAV_JS);
        }

        List<Path> files = new ArrayList<>();
        Set<Path> taken = new HashSet<>();
        Map<String, Path> fileOf = new HashMap<>();
        Set<String> pageIds = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            Element section = sections.get(i);
            Path file = unique(i == 0 ? Path.of(index) : fileFor(section), taken);
            files.add(file);
            pageIds.add(section.id());
            for (Element e : section.select("[id]")) {
                fileOf.putIfAbsent(e.id(), file);
            }
        }
        if (contents != null) {
            // The contents is in the navigation now: a link to it goes to the first page.
            for (Element e : contents.select("[id]")) {
                fileOf.putIfAbsent(e.id(), files.getFirst());
                pageIds.add(e.id());
            }
            contents.select("[id]").removeAttr("id");
        }

        List<String> headings = sections.stream().map(s -> {
            Element h1 = s.selectFirst("h1");
            return h1 == null || h1.text().isBlank() ? title : h1.text();
        }).toList();
        List<Entry> bar = options.top() ? bar(contents, headings, files, fileOf, pageIds) : null;

        Map<Path, String> out = new LinkedHashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            Element section = sections.get(i);
            Path file = files.get(i);
            links(section, file, fileOf, pageIds);
            String pageTitle = headings.get(i).equals(title) ? title : headings.get(i) + " · " + title;
            Path prev = i > 0 ? files.get(i - 1) : null;
            Path next = i + 1 < files.size() ? files.get(i + 1) : null;

            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                    .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                    .append("<title>").append(esc(pageTitle)).append("</title>\n")
                    .append("<link rel=\"stylesheet\" href=\"").append(href(file, Path.of(CSS))).append("\">\n");
            if (prev != null) {
                sb.append("<link rel=\"prev\" href=\"").append(href(file, prev)).append("\">\n");
            }
            if (next != null) {
                sb.append("<link rel=\"next\" href=\"").append(href(file, next)).append("\">\n");
            }
            if (options.side()) {
                // Without the script, the Contents button can't show it on a narrow screen: show it anyway.
                sb.append("<noscript><style>.site-nav { display: block; } .site-nav-toggle { display: none; }</style></noscript>\n");
            }
            String bodyClass = (doc.body().className() + (options.side() ? " has-nav" : options.top() ? " has-topnav" : "")).strip();
            sb.append("</head>\n<body class=\"").append(esc(bodyClass)).append("\">\n")
                    .append("<header class=\"site-header\"><a href=\"").append(href(file, files.getFirst())).append("\">")
                    .append(esc(title)).append("</a>");
            if (options.side()) {
                sb.append("<button class=\"site-nav-toggle\" type=\"button\" aria-controls=\"site-nav\""
                        + " aria-expanded=\"false\">Contents</button>");
            }
            sb.append("</header>\n");
            if (options.side()) {
                sb.append(sideNav(contents, sections, headings, files, i, fileOf, pageIds));
            } else if (bar != null) {
                sb.append(topNav(bar, file));
            }
            sb.append("<main>\n").append(section.outerHtml()).append("\n</main>\n");
            if (prev != null || next != null) {
                sb.append("<nav class=\"site-pager\" aria-label=\"Pages\">\n");
                if (prev != null) {
                    sb.append("<a class=\"prev\" rel=\"prev\" href=\"").append(href(file, prev))
                            .append("\"><span>Previous</span>").append(esc(headings.get(i - 1))).append("</a>\n");
                }
                if (next != null) {
                    sb.append("<a class=\"next\" rel=\"next\" href=\"").append(href(file, next))
                            .append("\"><span>Next</span>").append(esc(headings.get(i + 1))).append("</a>\n");
                }
                sb.append("</nav>\n");
            }
            if (!js.isEmpty()) {
                sb.append("<script src=\"").append(href(file, Path.of(JS))).append("\" defer></script>\n");
            }
            sb.append("</body>\n</html>\n");
            out.put(file, sb.toString());
        }
        out.put(Path.of(CSS), css.toString());
        if (!js.isEmpty()) {
            out.put(Path.of(JS), js.toString());
        }
        return out;
    }

    /** Points each in-document link in {@code root} at the page its target is on, from {@code file}. */
    private static void links(Element root, Path file, Map<String, Path> fileOf, Set<String> pageIds) {
        for (Element a : root.select("a[href^=#]")) {
            String id = URLDecoder.decode(a.attr("href").substring(1), StandardCharsets.UTF_8);
            Path target = fileOf.get(id);
            if (target != null && !target.equals(file)) {
                a.attr("href", href(file, target) + (pageIds.contains(id) ? "" : "#" + id));
            } else if (target != null && pageIds.contains(id)) {
                a.attr("href", href(file, target));
            }
        }
    }

    /**
     * The side navigation for page {@code current}: the contents page's content, as its layout
     * (so stylesheets for that layout still apply), or a list of every page.
     */
    private static String sideNav(Element contents, List<Element> sections, List<String> headings, List<Path> files,
                                  int current, Map<String, Path> fileOf, Set<String> pageIds) {
        Path file = files.get(current);
        Element body;
        if (contents != null) {
            body = contents.clone();
            body.tagName("div").removeAttr("data-path").removeClass("page");
            links(body, file, fileOf, pageIds);
        } else {
            body = new Element("div").addClass("site-nav-pages");
            Element ul = body.appendElement("ul");
            for (int i = 0; i < sections.size(); i++) {
                ul.appendElement("li").appendElement("a").attr("href", "#" + sections.get(i).id()).text(headings.get(i));
            }
            links(body, file, fileOf, pageIds);
        }
        String self = href(file, file);
        for (Element a : body.select("a[href]")) {
            if (a.attr("href").equals(self)) {
                a.attr("aria-current", "page");
            }
        }
        Document holder = Document.createShell(""); // so it's written as is, like the pages, not pretty-printed
        holder.outputSettings().prettyPrint(false);
        holder.body().appendChild(body);
        return "<nav class=\"site-nav\" id=\"site-nav\" aria-label=\"Contents\">\n" + body.outerHtml() + "\n</nav>\n";
    }

    /** An entry in the navigation bar: a page (label, one link), or a group of pages (label, its pages). */
    private record Entry(String label, Path page, List<Entry> pages) {
    }

    /**
     * The navigation bar's entries: the contents page's links, grouped under its headings (the
     * highest level below its title), or without one the pages grouped by folder.
     */
    private static List<Entry> bar(Element contents, List<String> headings, List<Path> files,
                                   Map<String, Path> fileOf, Set<String> pageIds) {
        List<Entry> out = new ArrayList<>();
        if (contents != null) {
            int level = 7;
            for (Element h : contents.select("h2, h3, h4, h5, h6")) {
                level = Math.min(level, h.tagName().charAt(1) - '0');
            }
            Set<Path> seen = new HashSet<>();
            List<Entry> group = null;
            for (Element e : contents.select("h" + level + ", a[href^=#]")) {
                if (!e.tagName().equals("a")) {
                    group = new ArrayList<>();
                    out.add(new Entry(e.text(), null, group));
                    continue;
                }
                String id = URLDecoder.decode(e.attr("href").substring(1), StandardCharsets.UTF_8);
                Path page = pageIds.contains(id) ? fileOf.get(id) : null;
                if (page != null && !e.text().isBlank() && seen.add(page)) { // a page's number links it again
                    (group == null ? out : group).add(new Entry(e.text(), page, null));
                }
            }
            out.removeIf(entry -> entry.pages() != null && entry.pages().isEmpty());
            return out;
        }
        Map<Path, List<Entry>> byFolder = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            Path folder = files.get(i).getParent();
            Entry page = new Entry(headings.get(i), files.get(i), null);
            if (folder == null) {
                out.add(page);
            } else {
                byFolder.computeIfAbsent(folder, f -> {
                    List<Entry> group = new ArrayList<>();
                    out.add(new Entry(null, null, group)); // labelled by its first page, below
                    return group;
                }).add(page);
            }
        }
        if (byFolder.size() == 1 && out.size() == 1) { // one folder: no groups, just its pages
            return byFolder.values().iterator().next();
        }
        out.replaceAll(e -> e.pages() == null ? e : e.pages().size() == 1 ? e.pages().getFirst()
                : new Entry(e.pages().getFirst().label(), null, e.pages()));
        return out;
    }

    /** The navigation bar on page {@code file}: its page and the group it's in marked. */
    private static String topNav(List<Entry> bar, Path file) {
        StringBuilder sb = new StringBuilder("<nav class=\"site-topnav\" id=\"site-nav\" aria-label=\"Contents\"><ul>\n");
        for (Entry e : bar) {
            if (e.pages() == null) {
                sb.append("<li>").append(navLink(e, file, true)).append("</li>\n");
                continue;
            }
            boolean here = e.pages().stream().anyMatch(p -> p.page().equals(file));
            sb.append("<li><details").append(here ? " class=\"current\"" : "").append("><summary title=\"")
                    .append(esc(e.label())).append("\">").append(esc(shortLabel(e.label()))).append("</summary><ul>\n");
            for (Entry p : e.pages()) {
                sb.append("<li>").append(navLink(p, file, false)).append("</li>\n");
            }
            sb.append("</ul></details></li>\n");
        }
        return sb.append("</ul></nav>\n").toString();
    }

    private static String navLink(Entry e, Path file, boolean titled) {
        return "<a href=\"" + href(file, e.page()) + "\"" + (titled ? " title=\"" + esc(e.label()) + "\"" : "")
                + (e.page().equals(file) ? " aria-current=\"page\"" : "") + ">" + esc(e.label()) + "</a>";
    }

    /** A group's label for the bar: what's before its colon, if that's short ({@code Part 1}), else all of it. */
    static String shortLabel(String label) {
        int colon = label.indexOf(':');
        return colon > 0 && colon <= 24 ? label.substring(0, colon).strip() : label;
    }

    /** A page's file: its source's path as {@code .html}, or its id for a generated page. */
    private static Path fileFor(Element section) {
        String source = section.attr("data-path");
        if (source.isEmpty()) {
            return Path.of(section.id() + ".html");
        }
        int dot = source.lastIndexOf('.');
        int slash = source.lastIndexOf('/');
        return Path.of((dot > slash + 1 ? source.substring(0, dot) : source) + ".html");
    }

    /** {@code file}, or {@code file-2.html}, {@code file-3.html}, ... if it's taken (or is the stylesheet or script). */
    private static Path unique(Path file, Set<Path> taken) {
        String name = file.toString();
        String base = name.substring(0, name.length() - ".html".length());
        Path out = file;
        for (int n = 2; out.equals(Path.of(CSS)) || out.equals(Path.of(JS)) || !taken.add(out); n++) {
            out = Path.of(base + "-" + n + ".html");
        }
        return out;
    }

    /** The relative URL from the page {@code from} to {@code to}, both relative to the site's root. */
    static String href(Path from, Path to) {
        Path dir = from.getParent();
        String rel = (dir == null ? to : dir.relativize(to)).toString().replace('\\', '/');
        return esc(rel.replace("%", "%25").replace(" ", "%20").replace("#", "%23").replace("?", "%3F"));
    }

    private static String esc(String s) {
        return SiteHtmlRenderer.esc(s);
    }

    /**
     * The navigation bar's groups: one open at a time, closed by Escape or a click elsewhere, and
     * kept on screen (one near the right edge opens leftwards).
     */
    private static final String TOPNAV_JS = """
            (function () {
              var nav = document.querySelector(".site-topnav");
              if (!nav) return;
              var groups = Array.prototype.slice.call(nav.querySelectorAll("details"));
              function close(except) {
                groups.forEach(function (d) { if (d !== except) d.open = false; });
              }
              groups.forEach(function (d) {
                d.addEventListener("toggle", function () {
                  if (!d.open) return;
                  close(d);
                  var list = d.querySelector("ul");
                  list.style.left = "";
                  list.style.right = "";
                  if (list.getBoundingClientRect().right > document.documentElement.clientWidth - 8) {
                    list.style.left = "auto";
                    list.style.right = "0";
                  }
                });
              });
              document.addEventListener("click", function (e) {
                if (!nav.contains(e.target)) close(null);
              });
              document.addEventListener("keydown", function (e) {
                var open = nav.querySelector("details[open]");
                if (e.key === "Escape" && open) {
                  close(null);
                  open.querySelector("summary").focus();
                }
              });
            })();

            """;

    /** The Contents button, and the current page's entry scrolled into view in the side navigation. */
    private static final String NAV_JS = """
            (function () {
              var button = document.querySelector(".site-nav-toggle");
              if (button) {
                button.addEventListener("click", function () {
                  button.setAttribute("aria-expanded", String(document.body.classList.toggle("nav-open")));
                });
              }
              // The current page's entry, scrolled to the middle of the side navigation: vertically only,
              // and only the navigation (scrollIntoView can scroll it sideways, or the page).
              var nav = document.querySelector(".site-nav");
              var current = nav && nav.querySelector('[aria-current="page"]');
              if (current && window.matchMedia("(min-width: 1000px)").matches) {
                nav.scrollTop += current.getBoundingClientRect().top - nav.getBoundingClientRect().top - nav.clientHeight / 2;
                nav.scrollLeft = 0;
              }
            })();

            """;
}
