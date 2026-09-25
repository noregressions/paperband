---
id: watermarks
oneliner: "Stamp text, or a logo, across the PDF and the site."
index: [watermarks, DRAFT stamp]
---

# Watermarks

A watermark overlays text such as `DRAFT` or `SAMPLE`, or an image such as a client logo, on
the pages of a book. One declaration marks both outputs. The PDF is stamped after rendering, on the finished file, so it behaves the same under every renderer, leaves the rendered content
unchanged, and preserves the named destinations that page-count enforcement uses. The site
gets the same mark as a CSS overlay on every page.

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

An unknown key, such as a misspelt `opacty:`, fails the build.

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
  <version>0.1.3</version>

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

The element names are the yaml keys, with one exception: `<fontSize>` for yaml's
`font_size`, because Maven binds elements to field names and a field can't be called
`font_size`.

The `site` goal ignores `<pages>` and `<font>`, so one shared `<watermark>` block serves both
goals. The flat parameters `<watermarkPages>` and `<watermarkFont>` are not parameters of
`site`, so put those two in the PDF execution, not the shared block. Inside `<watermark>`
they can go anywhere.

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

Each key also exists as a flat parameter with a `-D` property: `<watermarkColor>`,
`<watermarkOpacity>` and so on, with `<watermarkText>` for the text. The flat parameters
override the block:

```bash
# the POM's block says REVIEW COPY at 0.15; this run says otherwise
mvn package -Dpaperband.watermark="DRAFT" -Dpaperband.watermarkOpacity=0.4
```

The three sources apply in a fixed order: the book's `vars.watermark`, then the POM's block,
then the flat parameters.

### POM or yaml?

Use the yaml when the mark belongs to the book, and the POM when it belongs to the build (for
example, a module that always ships review copies). The yaml also applies when the book is
built without your POM.

## Fitting

`font_size` is a maximum. A stamp too wide for the page is shrunk until its rotated bounding
box fits, down to 8pt. A 46-character phrase declared at 96pt renders at about 28pt on A4.

`fit: false` draws the stamp exactly as declared, and it may run off the edge. For long text,
use a line break instead of relying on shrinking:

```yaml
vars:
  watermark:
    text: "SAMPLE COPY\nNOT FOR RESALE"
```

Both a real newline, as above, and the literal characters `\n` work. The literal form is
what a single-quoted yaml scalar and a `-Dpaperband.watermark=...` command line deliver.

## Which pages

`pages: except-cover` skips page one, for books whose cover is a designed page. `pages: first`
stamps the first page only.

The site ignores `pages`.

## Over or under

By default the stamp is drawn over the page content. At the default opacity of 0.12 this does
not affect legibility; at higher opacities the text sits under grey. `behind: true` draws
the overlay first, so the content paints over it.

## Tiling

`tile: true` repeats the stamp in a 3 × 4 grid across each page instead of one centred stamp.
Each copy is fitted to its own cell, so tiled stamps are smaller and do not overlap.

## Image watermarks

```yaml
vars:
  watermark:
    image: brand/logo.png
    scale: 0.6
    opacity: 0.18
```

The path resolves against the book root. For the site the file is copied into `assets/`
alongside the cover art. A missing image produces a warning, not a build failure.

A watermark is either text or an image. For a logo with wording, use an image that contains
the wording.

## Text Helvetica can't set

The default font is Type1 Helvetica, which needs no embedding and does not increase the PDF's
size. Helvetica is WinAnsi-encoded, so it cannot set CJK, Cyrillic or Greek. Point `font:` at
a TrueType file that covers the characters:

```yaml
vars:
  watermark:
    text: "기밀 자료"
    font: fonts/ArialUnicode.ttf
```

Without it the build warns, names the characters it couldn't set, and produces an unmarked
PDF:

```output
[WARNING] watermark text contains '기', '밀', '자', '료', which the watermark font cannot
encode — no watermark applied. Point the watermark's 'font:' key at a TrueType file that
covers those characters.
```

`font:` is PDF only; the site uses the browser's fonts.

## Precedence

`<watermark>` on the build replaces the yaml declaration entirely: when it is set, the yaml
map is not read, so a build can stamp `REVIEW COPY` over a book whose yaml says `DRAFT`
without inheriting the yaml's colour or angle. The per-setting parameters
(`<watermarkColor>` and the rest) then apply over whichever base was chosen. Setting only
those parameters, with no text anywhere, produces no watermark.

```bash
# yaml says DRAFT; this build says otherwise
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.watermark="REVIEW COPY" -Dpaperband.watermarkOpacity=0.2
```

A successful application prints what it stamped after rendering:

```output
[INFO] Applied watermark: "REVIEW COPY"
```

## Per edition

`publication:` merges each edition's `vars` over the defaults, so one source can produce an
unmarked release and a marked review copy.

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

`<emitHtml>` writes the pre-render HTML with the watermark as a screen-only overlay, visible
when the file is opened and hidden when it is printed. The PDF gets its mark from the post-render stamp, so a print-visible overlay would apply it twice.

Re-rendering that file therefore produces an unmarked PDF unless the watermark parameters are
passed to `paperband:render`:

```bash
mvn paperband:render -Dpaperband.input=out.html -Dpaperband.output=out.pdf -Dpaperband.watermark=DRAFT
```

## Watch Out

The site overlay is `position: fixed` and `pointer-events: none`, so it does not affect layout
or intercept clicks, and it is hidden from assistive technology. It carries the class
`pb-watermark`, which a theme can hide or restyle.

Vertical centring is approximate: it offsets by a quarter of the font size rather than
measuring cap height, which can show at high opacities. On the site the fitted width is also
approximate, because CSS cannot measure a string; it assumes about 0.7em per character.
