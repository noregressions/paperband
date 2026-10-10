---
title: Templated Page
audience: developers
tags:
  - pebble
  - templates
  - markdown
---
# {{ page.title }}

Written for {{ page.audience | upper }} using {{ product }} {{ version }}.

## Tags

{% for tag in page.tags %}
{{ loop.index + 1 }}. `{{ tag }}`
{% endfor %}

{% if page.draft %}
> This page is a draft.
{% else %}
This page is published.
{% endif %}

{% verbatim %}
Literal braces survive: {{ not.a.variable }}
{% endverbatim %}

## Code

Code blocks are left as written, braces and brackets included:

```bash
[[ -f build.gradle ]] && echo "{{ not.a.variable }}"
```

A code block that wants templating asks for it after its language:

```bash {! template}
mvn install -Dproduct={{ product }}
```

{% include "footer.md" %}
