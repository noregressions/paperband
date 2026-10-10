---
title: Using a Layout
author: Steve Poole
---
{% extends "article.md" %}

{% block summary %}
> This page gets its heading, byline and footer from `article.md`.
{% endblock %}

{% block content %}
## Section from the child

Child content can still use {{ product }} variables and loops:

{% for n in range(1, 3) %}
- item {{ n }}
{% endfor %}
{% endblock %}
