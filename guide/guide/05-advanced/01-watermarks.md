---
id: watermarks
oneliner: "Stamp text like DRAFT or SAMPLE across every page after rendering."
---

# Watermarks

A watermark overlays text such as `DRAFT` or `SAMPLE` on every page once the PDF has been
rendered. It's applied as a PDFBox post-pass on the finished file, so it behaves identically
under all three renderers, never disturbs the rendered content underneath, and preserves the
named destinations that page-count enforcement relies on.

## Declaring in yaml

The watermark lives under `vars:` in the root `pagewright.yaml`. A bare string takes all
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

| Key | CLI flag | Default | Notes |
|---|---|---|---|
| `text` | `--watermark` | — (off) | Required; single line, centred on each page |
| `color` | `--watermark-color` | `#888888` | `#RRGGBB`, `RRGGBB`, or short `#abc`; malformed falls back to mid-grey |
| `opacity` | `--watermark-opacity` | `0.12` | Must be within 0–1 |
| `angle` | `--watermark-angle` | `-30` | Degrees, rotated about the page centre |
| `font_size` | `--watermark-font-size` | `96` | Points; minimum 8 |
| `bold` | *(yaml only)* | `true` | Helvetica-Bold vs Helvetica |

## Precedence

`--watermark` on the command line **replaces** the yaml declaration entirely — when the
flag is present, the yaml map isn't consulted at all, so a release build can stamp
`REVIEW COPY` over a book whose yaml says `DRAFT` without inheriting the yaml's colour or
angle. The per-knob flags (`--watermark-color` and friends) then layer over whichever base
was chosen, yaml or CLI. Passing only knob flags with no text anywhere produces no
watermark.

```bash
# yaml says DRAFT; this build says otherwise
pagewright build mybook out.pdf --watermark "REVIEW COPY" --watermark-opacity 0.2
```

A successful application prints `Applied watermark: "REVIEW COPY"` after rendering.

## Watch Out

The text is one line — there's no wrapping, so a long phrase at the default 96pt will
overflow the page edges. Drop `font_size` as the text grows. Vertical centring is
approximate (it nudges by a quarter of the font size rather than measuring cap height),
which is invisible at watermark opacities but worth knowing if you crank opacity up for
proofs.
