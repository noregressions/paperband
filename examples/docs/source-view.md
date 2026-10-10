---
title: Source view
layout: source-preview
level: beginner
---
# {{ page.title }}

{# The pre pass fills in values from front matter and data. #}
This page is for {{ page.level }} readers of {{ product }} {{ version }}.

## Highlighted syntax {! id=syntax}

{% for item in ["front matter", "templates", "markers"] %}
- {{ item }}
{% endfor %}

There are [[ site.files | length ]] files in this build. {! note}

::: {! tip}
Markers like `{! tip}` tag blocks; write \{! to keep one literal.
:::

```bash {! command}
mvn test
```
