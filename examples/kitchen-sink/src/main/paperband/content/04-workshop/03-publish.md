---
id: workshop-publish
oneliner: "Build the static site and serve it locally."
---

# Publish the Site

The same cards build a static site. This session builds it and looks at it in a browser.

## {!step} Build the site

The `site` goal writes plain HTML: a home page, a page per card, and the theme's CSS.
Nothing needs a server to run.

Build the site into `target/site`. {.instructions}

```console
$ mvn paperband:site -Dpaperband.outputDirectory=target/site
```

## {!step} Serve it

Opening the files directly works, but a local server makes links and search behave as
they will when published.

Serve the site folder and open it in a browser. {.instructions}

```console
$ python3 -m http.server --directory target/site 8000
Serving HTTP on :: port 8000 ...
```

Stop the server with Ctrl-C when you're done.
