---
id: watermarks
oneliner: "Stamp text, or a logo, across the PDF and the site."
index: [watermarks, PDFBox]
---

# Watermarks

A watermark overlays text such as `DRAFT` or `SAMPLE`, or an image such as a client logo, on
the pages of a book. One declaration marks both outputs: the PDF gets a PDFBox post-pass on
the finished file, so it behaves identically under every renderer, never disturbs the
rendered content underneath, and preserves the named destinations that page-count
enforcement relies on; the site gets the same mark as a CSS overlay on every page it writes.

That parity is the point. A book whose yaml says `DRAFT` used to produce a stamped PDF and a
clean-looking site, which is the wrong way round — the site is the copy that gets linked and
forwarded.

## Declaring in yaml

The watermark lives under `vars:` in the root `paperband.yaml`. A bare string takes all
the defaults:

```yaml
vars:
  watermark: "DRAFT"
```

Or a map for full control:

```yaml
vars:
  watermark:
    text: "SAMPLE — NOT FOR RESALE"
    color: "#aa0000"
    opacity: 0.15
    angle: -45
    font_size: 72
    bold: false
    pages: except-cover
```

An unknown key is an error rather than a shrug: a misspelled `opacty:` that silently stamped
at the default would be found by whoever printed the proof, which is late.

## The options

| Key | Build parameter | Default | Notes |
|---|---|---|---|
| `text` | `<watermark>` | — (off) | Required unless `image` is set. Newlines break it across lines. |
| `image` | `<watermarkImage>` | — | A logo instead of text; book-root-relative png/jpg/gif. Mutually exclusive with `text`. |
| `color` | `<watermarkColor>` | `#888888` | `#RRGGBB`, `RRGGBB`, or short `#abc`; malformed falls back to mid-grey |
| `opacity` | `<watermarkOpacity>` | `0.12` | Must be within 0–1 |
| `angle` | `<watermarkAngle>` | `-30` | Degrees, rotated about the stamp's centre |
| `font_size` | `<watermarkFontSize>` | `96` | Points; minimum 8. A ceiling unless `fit` is off |
| `bold` | `<watermarkBold>` | `true` | Helvetica-Bold vs Helvetica |
| `scale` | `<watermarkScale>` | `0.5` | Image only: its width as a fraction of the page |
| `fit` | `<watermarkFit>` | `true` | Shrink the stamp until it fits the page |
| `behind` | `<watermarkBehind>` | `false` | Draw under the page content instead of over it |
| `tile` | `<watermarkTile>` | `false` | Repeat across the page instead of one centred stamp |
| `pages` | `<watermarkPages>` | `all` | `all`, `first`, or `except-cover`. PDF only |
| `font` | `<watermarkFont>` | — | TrueType file to embed, for text Helvetica can't set. PDF only |

Each also has a `-D` property — `paperband.watermark`, `paperband.watermarkColor`, and so
on — so a one-off stamp needs no POM edit. `build`, `site` and `render` all take them.

## In the POM

`<watermark>` is a block, mirroring the yaml map key for key:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.1</version>

  <!-- Shared by every goal below: one declaration, both outputs marked. -->
  <configuration>
    <watermark>
      <text>REVIEW COPY</text>
      <color>#aa0000</color>
      <opacity>0.15</opacity>
      <angle>-45</angle>
      <tile>true</tile>
      <pages>except-cover</pages>
    </watermark>
  </configuration>

  <executions>
    <execution>
      <id>pdf</id>
      <goals><goal>build</goal></goals>
      <configuration>
        <output>${project.build.directory}/book.pdf</output>
      </configuration>
    </execution>
    <execution>
      <id>site</id>
      <goals><goal>site</goal></goals>
      <configuration>
        <outputDirectory>${project.build.directory}/site</outputDirectory>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The element names are the yaml keys, so a declaration moves between the two by hand with no
translation table. The one difference Maven forces is `<fontSize>` for yaml's `font_size`:
elements bind to field names, and a field can't be called `font_size`.

`<pages>` and `<font>` mean nothing to a website, and the `site` goal quietly ignores them
rather than refusing the block — which is what lets one shared `<watermark>` feed both goals.
The flat spellings are the exception: `<watermarkPages>` and `<watermarkFont>` are not
parameters of `site` at all, so those two belong in the PDF execution rather than the shared
block. Inside `<watermark>` they can sit anywhere.

A misspelled key inside the block is still an error:

```output
Unable to parse configuration of mojo ...:build for parameter opacty:
Cannot find 'opacty' in class dev.noregressions.paperband.maven.WatermarkConfig
```

### The shorthand

A bare string works, exactly as it does in the yaml:

```xml
<watermark>DRAFT</watermark>
```

### Retuning from the command line

Alongside the block, each key also exists as a flat parameter carrying the `-D` property —
`<watermarkColor>`, `<watermarkOpacity>`, and so on, with `<watermarkText>` for the text.
**The block is the declaration; the flat parameters are the overrides**, and they win:

```bash
# the POM's block says REVIEW COPY at 0.15; this run says otherwise
mvn package -Dpaperband.watermark="DRAFT" -Dpaperband.watermarkOpacity=0.4
```

That is the same layering the yaml gets, one rung further out. All three channels compose in
a fixed order — the book's `vars.watermark`, then the POM's block, then the flat parameters —
so a book can say `DRAFT`, a module restyle it, and one run retune that.

### POM or yaml?

Prefer the yaml when the mark belongs to the book (a manuscript that is a draft until it
isn't), and the POM when it belongs to the build (this module always ships review copies).
The yaml also travels with the book to anyone who builds it without your POM.

## Fitting

`font_size` is a ceiling, not a promise: a stamp too wide for the paper is shrunk until its
rotated bounding box sits inside the page, down to a floor of 8pt. A 46-character phrase
declared at 96pt lands at about 28pt on A4 — visible, diagonal, and on the page, which is
what the author meant.

Turn it off with `fit: false` when the size matters more than the fit; the stamp is then
drawn exactly as declared and may run off the edge. Long text is better broken than shrunk:

```yaml
vars:
  watermark:
    text: "SAMPLE COPY\nNOT FOR RESALE"
```

Both spellings of a line break work — a real newline as above, and the literal two
characters `\n`, which is what a single-quoted yaml scalar and a `-Dpaperband.watermark=...`
command line actually deliver.

## Which pages

`pages: except-cover` leaves page one alone. A cover is usually a designed page — a
full-bleed image, a title block — and a `DRAFT` stamp across it is the one place the mark
reads as damage rather than as status. `pages: first` is the opposite case: a stamp on the
title page only.

The site ignores `pages` entirely. A website has no page one, and no cover to protect.

## Over or under

By default the stamp is drawn over the page content. At the default opacity of 0.12 that is
invisible; crank it up for a proof and the body text starts to sit under grey. `behind: true`
prepends the overlay instead, so the content paints over it.

## Tiling

`tile: true` repeats the stamp in a 3 × 4 grid across each page rather than centring one, the
conventional look for a licensing mark. Each copy is fitted to its own cell, so tiling makes
the individual stamps smaller rather than overlapping them.

## Image watermarks

```yaml
vars:
  watermark:
    image: brand/logo.png
    scale: 0.6
    opacity: 0.18
```

The path resolves against the book root. For the site the file is copied into `assets/`
alongside the cover art, and referenced at the right depth from every page. A missing image
is a warning, not a build failure — a rendered book without its logo beats no book at all.

There is no "logo plus wording" mode: a watermark is text or an image, and a logo with
wording in it is an image.

## Text Helvetica can't set

The default font is Type1 Helvetica, which needs no embedding and keeps the PDF the size it
was. Helvetica is WinAnsi-encoded, so it cannot set CJK, Cyrillic or Greek. Point `font:` at
a TrueType file that covers the characters:

```yaml
vars:
  watermark:
    text: "기밀 자료"
    font: fonts/ArialUnicode.ttf
```

Without it the build warns, names the characters it couldn't set, and renders unmarked
rather than throwing away a book that otherwise rendered:

```output
[WARNING] watermark text contains '기', '밀', '자', '료', which the watermark font cannot
encode — no watermark applied. Point the watermark's 'font:' key at a TrueType file that
covers those characters.
```

The site needs none of this: a browser has its own fonts, so `font:` is a PDF-only key.

## Precedence

`<watermark>` on the build **replaces** the yaml declaration entirely — when it's set, the
yaml map isn't consulted at all, so a release build can stamp `REVIEW COPY` over a book
whose yaml says `DRAFT` without inheriting the yaml's colour or angle. The per-knob
parameters (`<watermarkColor>` and friends) then layer over whichever base was chosen,
yaml or build. Setting only knob parameters with no text anywhere produces no watermark.

```bash
# yaml says DRAFT; this build says otherwise
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.watermark="REVIEW COPY" -Dpaperband.watermarkOpacity=0.2
```

A successful application prints what it stamped after rendering:

```output
[INFO] Applied watermark: "REVIEW COPY"
```

## Per edition

An edition is the natural home for a watermark: `publication:` merges each edition's `vars`
over the defaults, so one source can cut a clean release and a marked review copy.

```yaml
publication:
  editions:
    - id: release
      title: "The Guide"
    - id: review
      title: "The Guide (review)"
      vars:
        watermark: { text: "REVIEW COPY", pages: except-cover }
```

## The emitted HTML

`<emitHtml>` writes the pre-render HTML with the watermark as a **screen-only** overlay: you
see it when you open the file, and it disappears when the page is printed. That file's print
path is the renderer plus the PDFBox stamp, so a mark visible in print would land twice.

It also means re-rendering that file produces an unmarked PDF unless you say otherwise —
which is why `paperband:render` takes the watermark parameters too:

```bash
mvn paperband:render -Dpaperband.input=out.html -Dpaperband.output=out.pdf -Dpaperband.watermark=DRAFT
```

## Watch Out

The site overlay is `position: fixed` and `pointer-events: none`, so it never affects layout
or swallows a click, and it is hidden from assistive technology. It carries the class
`pb-watermark`; a theme that wants it gone on the web can hide that class, and a theme that
wants it different can restyle it.

Vertical centring is approximate (it nudges by a quarter of the font size rather than
measuring cap height), which is invisible at watermark opacities but worth knowing if you
crank opacity up for proofs. On the site, so is the width: CSS cannot measure a string, so
the fitted size is computed from an estimate of about 0.7em per character.
