package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.loader.Loader;

import java.io.Reader;

/**
 * The template chain as a view sees it: every name looked up as
 * {@code <view>/<name>} first, through the whole chain, and as itself when no
 * link of the chain has the view's own.
 *
 * <p>A view is a set of templates a build names ({@code <view>cheatsheet</view>})
 * to change what it writes without writing Java: its {@code _card-body.html}
 * replaces the default card body, its {@code keep.html} decides which cards the
 * build holds, and anything it doesn't ship falls through to the defaults. The
 * lookup order inside the chain doesn't change, so a book's
 * {@code layouts/cheatsheet/_card-body.html} overrides the bundled
 * {@code cheatsheet/_card-body.html} exactly as {@code layouts/_card-body.html}
 * overrides the bundled {@code _card-body.html}.
 *
 * <p>A view's template can still reach the one it replaces:
 * {@code {% include "default:_block-section" %}} is {@code _block-section}
 * looked up as if there were no view. A view that changes one case -- a
 * student edition turning solutions into answer space -- hands every other
 * case to the default that way, a theme's or a book's override included,
 * instead of copying it. Anything the default includes goes through the view
 * again, so a recursive template stays the view's all the way down.
 *
 * <p>The choice is made in {@link #createCacheKey}, so the engine caches a
 * view's template and the default under different keys.
 */
final class ViewLoader<T> implements Loader<T> {

    private final Loader<T> chain;
    private final String view;

    ViewLoader(Loader<T> chain, String view) {
        this.chain = chain;
        this.view = view;
    }

    /** How a view's template names the template it replaces: {@code default:<name>}. */
    static final String DEFAULT_PREFIX = "default:";

    /** The name the chain is asked for: the view's own, when some link has it, unless it asks for the default. */
    String resolve(String name) {
        if (name.startsWith(DEFAULT_PREFIX)) return name.substring(DEFAULT_PREFIX.length());
        String own = view + "/" + name;
        return chain.resourceExists(own) ? own : name;
    }

    @Override
    public T createCacheKey(String templateName) {
        return chain.createCacheKey(resolve(templateName));
    }

    @Override
    public boolean resourceExists(String templateName) {
        return chain.resourceExists(resolve(templateName));
    }

    @Override
    public Reader getReader(T cacheKey) {
        return chain.getReader(cacheKey);
    }

    @Override
    public void setCharset(String charset) {
        chain.setCharset(charset);
    }

    @Override
    public void setPrefix(String prefix) {
        chain.setPrefix(prefix);
    }

    @Override
    public void setSuffix(String suffix) {
        chain.setSuffix(suffix);
    }

    @Override
    public String resolveRelativePath(String relativePath, String anchorPath) {
        return chain.resolveRelativePath(relativePath, anchorPath);
    }
}
