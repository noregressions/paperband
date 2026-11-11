# A theme of your own

This site uses a theme that lives in the project, in `themes/paper`. The manifest names it by
its path:

```yaml
theme: themes/paper
```

A theme is a directory, and everything in it is optional. This one has three things.

## A stylesheet

`theme.css` is added after the built-in styles, so it only says what's different. Most of it
sets variables the built-in styles already read, such as these two:

```css
:root {
  --pb-font-body: Charter, "Iowan Old Style", Georgia, serif;
  --pb-page-width: 40rem;
}
```

The serif text and the narrower page on this site come from those lines. The rest of the file
sets the colours, once for light and once for dark.

## A replaced part

The built-in look is made of parts: `web.css`, `paged.css`, `content.css`, `site.css`,
`nav-side.css` and `nav-top.css`. A file of the same name in the theme takes a part's place.
Here `site.css` is replaced, which is why the header above is in small capitals over a double
rule, and the links at the foot of the page are in italics.

## A layout

Layouts in the theme's `layouts` directory are available to every page. The
[next page](#docs-02-layout-md) uses one.
