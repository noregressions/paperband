---
title: A longer tutorial
---
# A longer tutorial

This tutorial is too long for one A4 page. The handbook's print layout lets its body box split between second-level sections, so the rest continues on as many pages as it needs, and no step is ever cut in half. Each step's code block is kept with its step.

## 1. Install a JDK

Paperband needs Java 21 or later. Any distribution works; the examples here were built with a current LTS release. Check what you have before installing anything new, since many machines already carry a JDK for other tools.

```bash
java -version
```

## 2. Install Maven

Maven builds Paperband and runs the plugin that turns these markdown files into pages. Version 3.9 or later is enough. On macOS, Homebrew is the quickest way; on Linux, your package manager's version is usually recent enough.

```bash
mvn -version
```

## 3. Get the sources

Clone the repository, or download and unpack a release archive. Everything below runs from the top-level directory, where the parent pom.xml is.

```bash
git clone https://example.com/paperband.git && cd paperband
```

## 4. Build everything {! break=prefer}

The first build downloads dependencies and, for pdf output, the Chromium that Playwright uses: about 150 MB, once. Later builds are quick. To use a Chrome that's already installed instead, set browser: chrome in the manifest.

```bash
mvn install
```

## 5. Look at the showcase

The examples module builds a showcase of every example as one web page, and this handbook as paged HTML and PDF. Open the showcase in a browser to see each example's source, layout and result side by side.

```bash
open examples/target/showcase.html
```

## 6. Write a page

Add a markdown file under docs, give it a title and some sections, and list it in the manifest's sequence. Markers such as `{! step}` tag blocks so layouts can select them; front matter can hold data the templates use.

```bash
$EDITOR docs/my-page.md
```

## 7. Give it a layout

A layout file under layouts names boxes and what goes in each: a selector for content, fixed text, or nested layouts. For print, give the page a grid or a stack, and say which boxes may shrink, give way or split.

```bash
$EDITOR layouts/my-layout.yml
```

## 8. Build again

Every build measures paged output in Chromium. If a box doesn't fit and its remedies can't fix it, the build fails and writes a fit report beside the output showing exactly which box, on which page, and by how much.

```bash
mvn install -pl examples -am
```

Step 4 carries a `{! break=prefer}` marker: when the box has to break and the page is at least half full by then, it breaks there rather than as late as it could.
