---
title: Dashboards
---
# Dashboards

A dashboard page is a few figures a reader can take in at a glance. Three things make one: facts,
so each figure is written once; `tiles` blocks, which set figures out in a grid; and charts. Tiles
and charts come with the `chart` module.

## Facts

A fact is a figure in the data file `facts` (`data/facts.yml`), with what a reader needs to trust
it:

```yaml
dependency-lag:
  value: 278
  unit: days
  was: 215          # what it is compared with
  good: down        # which direction is the good one
  label: Median lag behind the latest major version.
  source: Datadog
  url: https://www.datadoghq.com/state-of-devsecops
  kind: vendor      # where it comes from: vendor, self-reported, derived...
cves-published: 93081   # a fact that is only a value
```

`fact("name")` writes it, with its unit, wherever it is quoted: the median dependency is
{{ fact("dependency-lag") }} behind, up from {{ fact("dependency-lag").was }}
({{ fact("dependency-lag").source }}), and {{ fact("cves-published") }} CVEs were published. Its
number can be calculated with: that is {{ fact("dependency-lag").value - 215 }} days more. A name
that isn't in the file stops the build, so a figure can't quietly go missing.

## Tiles

Each tile has a `value` and may have a `label`, a `source`, a `kind`, a `ref` to where the figure
is explained, a `tag`, and a `was` with its `trend`. `label`, `source` and `ref` are Markdown. One tile can take the `highlight`.

```tiles
tiles:
  - value: 12 minutes
    label: Average time to turn a patch into a working exploit.
    source: "[UC Riverside](https://arxiv.org/abs/2602.07287)"
    highlight: true
  - value: 23%
    label: Exploited flaws attacked on or before the day they were published.
    source: VulnCheck
    ref: "[the report](https://www.vulncheck.com/blog/state-of-exploitation-1h-2026)"
    tag: 2027 watch
  - value: 13%
    was: 14%
    trend: down
    good: down
    label: Downloads that are still a vulnerable version.
    source: Sonatype
    kind: vendor
```

A templated block takes a tile from a fact: `fact("name").tile` is the fact's value, label,
source, kind and trend, and the tile's own keys add to it or replace it.

```tiles {! template}
columns: 2
tiles:
  - { fact: {{ fact("dependency-lag").tile }}, tag: 2027 watch }
  - { fact: {{ fact("scored-share").tile }}, label: "Share of new CVEs with a severity score, third quarter." }
```

A group of tiles is never broken across printed pages, and on a narrow screen it is two across.
