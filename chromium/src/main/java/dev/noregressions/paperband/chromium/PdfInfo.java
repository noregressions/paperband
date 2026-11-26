package dev.noregressions.paperband.chromium;

import com.microsoft.playwright.Page;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * A PDF's Author, Subject and Keywords, which Chromium leaves empty: it fills in only the Title,
 * from the document's {@code <title>}. They're read from the same document's {@code <meta>} tags
 * ({@code author}, {@code description}, {@code keywords}), so the paged HTML and the PDF printed
 * from it say the same, and written into the PDF's document information.
 */
final class PdfInfo {

    private static final String META = """
            () => Object.fromEntries(['author', 'description', 'keywords'].map(name => {
              const m = document.querySelector('head > meta[name="' + name + '"]');
              return [name, m ? (m.getAttribute('content') || '').trim() : ''];
            }))""";

    private PdfInfo() {
    }

    /** {@code pdf}, printed from {@code page}, with what the page's meta tags say; itself if they say nothing. */
    @SuppressWarnings("unchecked")
    static byte[] describe(Page page, byte[] pdf) throws IOException {
        Map<String, String> meta = (Map<String, String>) page.evaluate(META);
        return describe(pdf, meta.get("author"), meta.get("description"), meta.get("keywords"));
    }

    /** @param author,subject,keywords null or empty to leave that one as it is */
    static byte[] describe(byte[] pdf, String author, String subject, String keywords) throws IOException {
        if (empty(author) && empty(subject) && empty(keywords)) {
            return pdf;
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            if (!empty(author)) {
                info.setAuthor(author);
            }
            if (!empty(subject)) {
                info.setSubject(subject);
            }
            if (!empty(keywords)) {
                info.setKeywords(keywords);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 1024);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
