package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.model.RenderContext;

import org.apache.maven.plugin.MojoFailureException;

import java.util.List;

/**
 * What the build and the site say about a view (see
 * {@code LayoutEngine#setView}): the cards one left out, a view that kept
 * none, and the var a view replaced.
 */
final class Views {

    private Views() {
    }

    /**
     * Fail a build that still turns cheat-sheet mode on with
     * {@code vars.cheatsheet}. The mode became the cheatsheet view, and a var
     * nothing reads any more would build the full guide without a word.
     */
    static void rejectCheatsheetVar(List<RenderContext> contexts) throws MojoFailureException {
        for (RenderContext ctx : contexts) {
            if (ctx.vars() != null && ctx.vars().containsKey("cheatsheet")) {
                throw new MojoFailureException("vars.cheatsheet is gone: a cheat sheet is now a view."
                        + " In the cheat-sheet execution, replace <vars><cheatsheet>true</cheatsheet>"
                        + "</vars> with <view>cheatsheet</view>. cheatsheetSelect still sets what"
                        + " each step keeps.");
            }
        }
    }

    /** The log line for the cards a view's keep.html or transform.html left out. */
    static String leftOut(String view, List<String> dropped) {
        return "View '" + view + "': left out " + dropped.size() + " card(s) its keep.html or"
                + " transform.html doesn't keep: " + String.join(", ", dropped);
    }

    /** The failure for a view that kept none of the build's cards. */
    static String keptNone(String view, int cards) {
        return "view '" + view + "' keeps none of the " + cards + " cards: its keep.html or"
                + " transform.html left out every one." + ("cheatsheet".equals(view)
                        ? " The cheatsheet view keeps the cards with a {!step}: mark the steps with"
                                + " {!step}, or build without the view."
                        : "");
    }
}
