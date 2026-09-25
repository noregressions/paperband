---
id: includes
oneliner: "A fragment quoted from a source file, and a snippet from layouts/."
---

# Includes

`{% fragment %}` quotes a named region of a real file, here `Hello.java` beside this card
([Includes](https://noregressions.github.io/paperband/cards/includes.html)):

{% fragment "Hello.java:greeting" %}

`{% include %}` inserts a Pebble snippet from the book's `layouts/`. The box below is
`layouts/snippets/support.html`:

{% include "snippets/support" with {"product": vars.product} %}
