---
title: Data and Build Info
chapter: 4
draft: false
published: 2026-10-03
author:
  name: Steve Poole
  role: Author
---
# {{ page.title }}

Chapter {{ page.chapter }} (next is {{ page.chapter + 1 }}), {% if page.draft %}draft{% else %}final{% endif %},
published {{ page.published | date("d MMMM yyyy") }} by {{ page.author.name }} ({{ page.author.role }}).

## Releases of {{ data.site.name }}

{% for r in data.site.releases %}
- {{ r.version }} on {{ r.date | date("yyyy-MM-dd") }}
{% endfor %}

## Team

{% for m in data.team.members %}
- **{{ m.name }}**: {{ m.role }}
{% endfor %}

## Build

Built {{ build.date | date("yyyy-MM-dd HH:mm z") }} from {{ build.files }} file(s).

## Other pages (from front matter, pre pass)

{% for f in site.files %}
- {{ f.path }}: {{ f.page.title | default("untitled") }}
{% endfor %}
