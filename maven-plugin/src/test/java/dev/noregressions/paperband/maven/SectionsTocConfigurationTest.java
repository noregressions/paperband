package dev.noregressions.paperband.maven;

import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.configurator.BasicComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code <toc/>} marker through Maven's REAL configurator, not reflection.
 *
 * <p>The whole feature hangs on a converter detail: for each child of a
 * {@code List<SectionConfig>} parameter, sisu-plexus first tries a class named
 * after the element in the enclosing class's package, and only then falls
 * back to the list's item type. That's what maps {@code <toc/>} to
 * {@link Toc} while {@code <section>} (no {@code maven.Section} class) keeps
 * mapping to {@link SectionConfig} — and it's behavior these tests would miss
 * entirely if they set fields reflectively like the rest of the suite.
 */
class SectionsTocConfigurationTest {

    /** Configure a BookLayout from raw {@code <book>...</book>} XML the way Maven would. */
    private BookLayout configured(String bookXml) throws Exception {
        Xpp3Dom dom = Xpp3DomBuilder.build(new StringReader(bookXml));
        BookLayout book = new BookLayout();
        ClassRealm realm = new org.codehaus.plexus.classworlds.ClassWorld(
                "test", BookLayout.class.getClassLoader()).getRealm("test");
        new BasicComponentConfigurator().configureComponent(
                book, new XmlPlexusConfiguration(dom), evaluator(), realm);
        return book;
    }

    private static ExpressionEvaluator evaluator() {
        return new ExpressionEvaluator() {
            @Override public Object evaluate(String expression) { return expression; }
            @Override public File alignToBaseDirectory(File file) { return file; }
        };
    }

    @Test
    void tocElementBecomesTheMarkerAndSectionsStaySections() throws Exception {
        BookLayout book = configured("""
                <book>
                  <sections>
                    <section><title>A</title><includes><include>a/**</include></includes></section>
                    <toc/>
                    <section><title>B</title><includes><include>b/**</include></includes></section>
                  </sections>
                </book>
                """);

        assertEquals(3, book.getSections().size(), "marker sits in the list, in document order");
        assertInstanceOf(SectionConfig.class, book.getSections().get(0));
        assertInstanceOf(Toc.class, book.getSections().get(1));
        assertInstanceOf(SectionConfig.class, book.getSections().get(2));
        assertEquals("A", book.getSections().get(0).getTitle());
        assertEquals("B", book.getSections().get(2).getTitle());

        assertEquals(1, book.tocAfterSpec(), "one section precedes the marker");
        assertEquals(2, book.toSpecs().size(), "the marker is not a section spec");
    }

    @Test
    void markerFirstMeansContentsUpFrontAndLastMeansAfterEverything() throws Exception {
        BookLayout first = configured("""
                <book>
                  <sections>
                    <toc/>
                    <section><title>A</title><includes><include>a/**</include></includes></section>
                  </sections>
                </book>
                """);
        assertEquals(0, first.tocAfterSpec());

        BookLayout last = configured("""
                <book>
                  <sections>
                    <section><title>A</title><includes><include>a/**</include></includes></section>
                    <toc/>
                  </sections>
                </book>
                """);
        assertEquals(1, last.tocAfterSpec());
    }

    @Test
    void noMarkerMeansNoDeclaredPosition() throws Exception {
        BookLayout book = configured("""
                <book>
                  <sections>
                    <section><title>A</title><includes><include>a/**</include></includes></section>
                  </sections>
                </book>
                """);
        assertNull(book.tocAfterSpec());
    }

    @Test
    void twoMarkersStopTheBuild() throws Exception {
        BookLayout book = configured("""
                <book>
                  <sections>
                    <toc/>
                    <section><title>A</title><includes><include>a/**</include></includes></section>
                    <toc/>
                  </sections>
                </book>
                """);
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, book::validate);
        assertTrue(e.getMessage().contains("<toc/>"), e.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, book::toSpecs,
                "toSpecs validates too — every goal reaches one of the two");
    }

    @Test
    void theOldBookLevelTocElementIsGoneAndSaysSo() {
        // <toc> used to be a Boolean on <book>; a POM still declaring it must
        // fail loudly, not configure a book with no contents page.
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> configured("<book><toc>true</toc></book>"));
        assertTrue(e.getMessage().contains("toc"), e.getMessage());
    }

    @Test
    void markerWithNoSectionsAroundItStopsTheBuild() throws Exception {
        BookLayout book = configured("""
                <book>
                  <sections>
                    <toc/>
                  </sections>
                </book>
                """);
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, book::toSpecs);
        assertTrue(e.getMessage().contains("<toc/>"), e.getMessage());
    }
}
