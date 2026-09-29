# Workshop

Three short sessions that take a book from nothing to a published site. Each step says
why it matters, then what to do, then the command. The `cheatsheet` build keeps only the
last two: one entry per step, for the back of the room
([Make a Cheat Sheet](https://noregressions.github.io/paperband/cards/make-a-cheat-sheet.html)).

{% for c in section.cards %}- [{{ c.title }}](card:{{ c.id }}): {{ c.oneliner }}
{% endfor %}
