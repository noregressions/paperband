package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.Sidebar;

/**
 * The {@code <sidebar>} element of {@code <book>}: the static site's navigation
 * sidebar.
 *
 * <pre>
 * &lt;book&gt;
 *   &lt;sidebar&gt;
 *     &lt;enabled&gt;true&lt;/enabled&gt;
 *     &lt;collapsed&gt;false&lt;/collapsed&gt;
 *     &lt;sectionsCollapsed&gt;false&lt;/sectionsCollapsed&gt;
 *   &lt;/sidebar&gt;
 * &lt;/book&gt;
 * </pre>
 *
 * <p>Declaring the element at all means "I want a sidebar", so
 * {@code <sidebar/>} on its own is enough and {@code <enabled>} only needs
 * writing to turn one off from a profile.
 *
 * <p>Book scope, like everything else on {@code <book>}: the site either has a
 * sidebar on every page or none. It was three {@code vars} entries before,
 * which put a whole-site switch on the per-card channel — see {@link Sidebar}.
 */
public class SidebarConfig {

    /** Render the sidebar. Defaults to true: the element's presence is the opt-in. */
    private Boolean enabled;

    /** Start the sidebar itself collapsed. Defaults to false. */
    private Boolean collapsed;

    /** Start each section's card list closed. Defaults to true. */
    private Boolean sectionsCollapsed;

    /**
     * This element as the model type.
     *
     * @return the declared sidebar
     */
    Sidebar toSidebar() {
        return new Sidebar(
                !Boolean.FALSE.equals(enabled),
                Boolean.TRUE.equals(collapsed),
                !Boolean.FALSE.equals(sectionsCollapsed));
    }

    @Override
    public String toString() {
        return "<sidebar>" + toSidebar() + "</sidebar>";
    }
}
