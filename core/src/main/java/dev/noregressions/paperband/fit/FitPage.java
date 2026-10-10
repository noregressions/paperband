package dev.noregressions.paperband.fit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fit report as a page: the output's paged HTML, with every box and page that has a problem
 * outlined and labelled, and a list of all the problems at the top. It's written next to the
 * output, as {@code NAME.fit.html}, when the output doesn't fit.
 */
public final class FitPage {

    private static final String CSS = """
            .fit-summary { max-width: 210mm; margin: 0 auto 24px; padding: 4mm 6mm; background: #fef2f2;
              border: 0.5mm solid #dc2626; border-radius: 2mm; font: 10pt/1.5 "Inter", sans-serif; color: #7f1d1d; }
            .fit-summary h1 { font-size: 13pt; margin: 0 0 2mm; }
            .fit-summary ol { margin: 0; padding-left: 6mm; }
            .fit-summary a { color: inherit; }
            .fit-problem { outline: 0.6mm dashed #dc2626 !important; outline-offset: -0.3mm; position: relative; }
            .fit-problem::before { content: attr(data-fit); position: absolute; z-index: 1000; top: 0; right: 0;
              max-width: 90%; padding: 0.5mm 1.5mm; background: #dc2626; color: #fff; white-space: pre-line;
              font: 600 7pt/1.3 "Inter", sans-serif; }
            section.page.fit-problem::before { top: auto; bottom: 0; }
            @media print { .fit-summary { display: none; } }
            """;

    private FitPage() {
    }

    /** @param output the output's name, for the heading */
    public static String render(String pagedHtml, String output, List<Problem> problems) {
        Document doc = Jsoup.parse(pagedHtml);
        doc.outputSettings().prettyPrint(false);
        doc.head().appendElement("style").appendText(CSS);
        doc.title("Fit report: " + output);

        Map<Element, StringBuilder> notes = new LinkedHashMap<>();
        Element summary = new Element("aside").addClass("fit-summary");
        summary.appendElement("h1").text("Output " + output + ": " + problems.size() + " problem(s); nothing was written");
        Element list = summary.appendElement("ol");
        for (Problem p : problems) {
            Element item = list.appendElement("li");
            Element target = target(doc, p);
            if (target != null) {
                if (target.id().isEmpty()) {
                    target.id("fit-" + notes.size());
                }
                item.appendElement("a").attr("href", "#" + target.id()).text(p.message());
                StringBuilder note = notes.computeIfAbsent(target, t -> new StringBuilder());
                if (!note.isEmpty()) {
                    note.append('\n');
                }
                note.append(label(p));
            } else {
                item.text(p.message());
            }
        }
        notes.forEach((e, note) -> e.addClass("fit-problem").attr("data-fit", note.toString()));
        doc.body().prependChild(summary);
        return doc.outerHtml();
    }

    /** The element a problem is about: its box, else its page. */
    private static Element target(Document doc, Problem p) {
        if (p.page() == null) {
            return null;
        }
        Element page = doc.getElementById(p.page());
        if (page == null || p.box() == null) {
            return page;
        }
        for (Element e : page.select("[data-box]")) {
            if (e.attr("data-box").equals(p.box())) {
                return e;
            }
        }
        return page;
    }

    /** The problem as a short label on the box itself. */
    private static String label(Problem p) {
        String m = p.message();
        int colon = m.indexOf(": ");
        return (p.box() != null ? p.box() + ": " : "") + (colon < 0 ? m : m.substring(colon + 2));
    }
}
