package dev.noregressions.paperband.chromium;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Reads printed PDFs in tests. */
final class PdfText {

    private PdfText() {
    }

    /** All the text, sheet after sheet. */
    static String of(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** The text of one sheet (from 1). */
    static String sheet(Path pdf, int n) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(n);
            s.setEndPage(n);
            return s.getText(doc);
        }
    }

    /** Where the leftmost text on a sheet (from 1) is, in millimetres from the sheet's left edge. */
    static double firstTextLeft(Path pdf, int n) throws IOException {
        double[] left = {Double.MAX_VALUE};
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        left[0] = Math.min(left[0], p.getXDirAdj());
                    }
                }
            };
            s.setStartPage(n);
            s.setEndPage(n);
            s.getText(doc);
        }
        return left[0] * 25.4 / 72;
    }

    /** Where the topmost text on a sheet (from 1) is, in millimetres from the sheet's top edge. */
    static double firstTextTop(Path pdf, int n) throws IOException {
        double[] top = {Double.MAX_VALUE};
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        top[0] = Math.min(top[0], p.getYDirAdj() - p.getHeightDir());
                    }
                }
            };
            s.setStartPage(n);
            s.setEndPage(n);
            s.getText(doc);
        }
        return top[0] * 25.4 / 72;
    }
}
