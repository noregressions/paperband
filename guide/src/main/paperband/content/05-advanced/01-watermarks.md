---
id: watermarks
oneliner: "Stamp text like DRAFT or SAMPLE across every page after rendering."
index: [watermarks, PDFBox]
---

# Watermarks

A watermark overlays text such as `DRAFT` or `SAMPLE` on every page once the PDF has been
rendered. It's applied as a PDFBox post-pass on the finished file, so it behaves identically
under all three renderers, never disturbs the rendered content underneath, and preserves the
named destinations that page-count enforcement relies on.

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
```

## The options

| Key | Build parameter | Default | Notes |
|---|---|---|---|
| `text` | `<watermark>` | — (off) | Required; single line, centred on each page |
| `color` | `<watermarkColor>` | `#888888` | `#RRGGBB`, `RRGGBB`, or short `#abc`; malformed falls back to mid-grey |
| `opacity` | `<watermarkOpacity>` | `0.12` | Must be within 0–1 |
| `angle` | `<watermarkAngle>` | `-30` | Degrees, rotated about the page centre |
| `font_size` | `<watermarkFontSize>` | `96` | Points; minimum 8 |
| `bold` | *(yaml only)* | `true` | Helvetica-Bold vs Helvetica |

Each also has a `-D` property — `paperband.watermark`, `paperband.watermarkColor`, and so
on — so a one-off stamp needs no POM edit.

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

A successful application prints `Applied watermark: "REVIEW COPY"` after rendering.

## Watch Out

The text is one line — there's no wrapping, so a long phrase at the default 96pt will
overflow the page edges. Drop `font_size` as the text grows. Vertical centring is
approximate (it nudges by a quarter of the font size rather than measuring cap height),
which is invisible at watermark opacities but worth knowing if you crank opacity up for
proofs.
