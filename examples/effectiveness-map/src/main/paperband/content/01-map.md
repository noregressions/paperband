---
id: map
oneliner: "Which content types work best for different goals and where to publish them."
---

# Content Effectiveness Map

## How to read the scores {.legend}

{% for s in vars.scale %}<span class="legend-item"><span class="score{% if s.score == '-' %} na{% endif %}">{% if s.score == '-' %}–{% else %}{{ s.score }}{% endif %}</span> {{ s.label }}</span>
{% endfor %}

## The map {.matrix}

{# The two outputs get different markup for the same data. On screen it is
    table.html's single table, with each group's label in a cell spanning its
    rows. Paper can't do that: a spanning cell's text stays on the page where
    the group started, so a group that runs onto the next page arrives with an
    empty label column. In print each group is its own table instead, with the
    column headers and the group's name in its <thead>, which Chromium repeats
    at the top of every page the table continues onto. #}
{% macro metricHeads(metrics) %}
{%- for m in metrics %}
<th class="metric-head"><span class="th-icon">:{{ m.icon }}:</span><span class="th-label">{{ m.label }}</span></th>
{%- endfor %}
{%- endmacro %}
{%- macro rowCells(r) %}
<td class="content"><span class="ico">:{{ r.icon }}:</span>{{ r.name }}</td>
<td class="desc">{{ r.desc }}</td>
{%- for s in r.scores %}
<td class="metric">{% if s == '-' %}<span class="score na">–</span>{% else %}<span class="score">{{ s }}</span>{% endif %}</td>
{%- endfor %}
<td class="best">{{ r.best }}</td>
{%- endmacro %}
{%- if output == 'print' %}
<div class="table-wrap print-groups">
{%- for g in vars.groups %}
<table class="score-matrix group-table group-{{ g.id }}">
<colgroup><col class="c-content"><col class="c-desc">{% for m in vars.metrics %}<col class="c-metric">{% endfor %}<col class="c-best"></colgroup>
<thead>
<tr class="cols">
<th class="lefthead">Content type</th>
<th class="lefthead">Description / examples</th>
{{- metricHeads(vars.metrics) }}
<th class="best-head">Best used for…</th>
</tr>
<tr class="band"><th colspan="{{ (vars.metrics | length) + 3 }}"><span class="ico">:{{ g.icon }}:</span> {{ g.label }} <small>{{ g.blurb }}</small></th></tr>
</thead>
<tbody>
{%- for r in g.rows %}
<tr class="group-{{ g.id }}">
{{- rowCells(r) }}
</tr>
{%- endfor %}
</tbody>
</table>
{%- endfor %}
</div>
{%- else %}
<div class="table-wrap">
<table class="score-matrix">
<thead>
<tr>
<th class="lefthead">Group</th>
<th class="lefthead">Content type</th>
<th class="lefthead">Description / examples</th>
{{- metricHeads(vars.metrics) }}
<th class="best-head">Best used for…</th>
</tr>
</thead>
<tbody>
{%- for g in vars.groups %}{% for r in g.rows %}
<tr class="group-{{ g.id }}">
{%- if loop.first %}
<th class="group" rowspan="{{ g.rows | length }}"><span class="ico">:{{ g.icon }}:</span> {{ g.label }}<small>{{ g.blurb }}</small></th>
{%- endif %}
{{- rowCells(r) }}
</tr>
{%- endfor %}{% endfor %}
</tbody>
</table>
</div>
{% endif %}


## Distribution outlets {.outlets}

<div class="outlet-grid">
{%- for o in vars.outlets %}
<div class="outlet"><div class="ico">:{{ o.icon }}:</div><h3>{{ o.name }}</h3><p>{{ o.desc }}</p></div>
{%- endfor %}
</div>

Scores represent natural fit, not guaranteed performance. Recording, repackaging,
syndication and amplification can change the effective reach and value of a format.
{.note}
