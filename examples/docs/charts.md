---
title: Charts
---
# Charts

A fenced `chart` block is a few lines of YAML, drawn as SVG when the site is built. It needs the
`chart` module beside the plugin, added as a plugin dependency the way `chromium` is. The drawing
takes the page's font and the theme's colours, so a chart looks like the text around it, on the
web and in print.

Every chart has a `type`, and may have a `title`, a `subtitle` and a `note`.

## Bars and columns

One colour says what the chart is about: `highlight` names the row that takes it.

```chart
type: bar
title: Most downloads of commons-lang 2.6 were the vulnerable version
subtitle: Share of downloads that were a vulnerable version
format: "{}%"
max: 100
highlight: commons-lang 2.6
rows:
  - { label: commons-lang 2.6, value: 99.88, note: "fix: the 3.x series" }
  - { label: jdom2 2.0.6, value: 57.73, note: "fix: 2.0.6.1" }
  - { label: log4j-core, value: 13 }
```

```chart
type: column
title: A reference line and a marker between two columns
format: "{}%"
max: 100
lines: [{ value: 90, label: target }]
markers: [{ after: "2024", label: the policy changed }]
rows:
  - { label: "2023", value: 92.9 }
  - { label: "2024", value: 58.3 }
  - { label: "2025", value: 34.9 }
```

## Dots, timelines, units and funnels

```chart
type: dot
title: Minutes on one clock, months on the other
subtitle: Elapsed time in hours, logarithmic scale
scale: log
min: 0.1
max: 10000
ticks: [{ at: 1, label: 1 hour }, { at: 24, label: 1 day }, { at: 730, label: 1 month }]
groups:
  - title: Attack
    highlight: true
    rows:
      - { label: Exploit from a patch, value: 0.2, text: 12 minutes }
  - title: Defence
    rows:
      - { label: Time to fix, range: [6048, 6672], text: 252 to 278 days }
```

```chart
type: timeline
title: Labels find their own room above and below the line
from: 2024-09-01
to: 2028-02-01
ticks: years
marks: [{ at: 2026-11-08, label: Today }]
events:
  - { at: 2024-12-10, label: Dec 2024, text: the act comes into force }
  - { at: 2026-09-11, label: Sep 2026, text: reporting starts, highlight: true }
  - { at: 2027-12-11, label: Dec 2027, text: full obligations }
```

```chart
type: units
title: Each row of squares is a share of ten
legend: [found, missed]
rows:
  - { label: First tool, value: 38.2 }
  - { label: Second tool, value: 98.4 }
```

```chart
type: funnel
title: A queue narrowing, then splitting in two
filters: [exposure, reachability]
stages:
  - { share: 100, label: Everything }
  - { share: 30, label: Exposed }
  - { share: 4, label: Reachable }
split:
  - { share: 3.4, label: Automate }
  - { share: 0.6, label: Decide, highlight: true }
```

A chart that can't be drawn, or a key the chart doesn't know, stops the build with the reason.
