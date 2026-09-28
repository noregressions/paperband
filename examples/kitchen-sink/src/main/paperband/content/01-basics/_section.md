# Basics

This is the section body: `_section.md` in the folder, shown on the section's landing page
and on its PDF divider. It lists the section's cards itself
([Book Configuration](https://noregressions.github.io/paperband/cards/book-configuration.html)):

{% for c in section.cards %}- [{{ c.title }}](card:{{ c.id }}): {{ c.oneliner }}
{% endfor %}
