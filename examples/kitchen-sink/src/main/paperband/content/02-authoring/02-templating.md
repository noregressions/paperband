---
id: templating
oneliner: "Vars, a conditional section, and content that differs between the PDF and the site."
---

# Templating

Every card body is a Pebble template. `{{ vars.product }}` in this sentence came from
`vars.product` in the book's `paperband.yaml`
([Vars and Conditionals](https://noregressions.github.io/paperband/cards/vars-and-conditionals.html)).

{% if vars.show_extras %}
## Extras

This section exists because `vars.show_extras` is true. Set it to false and the whole
section, heading included, is removed before the card is split into blocks.
{% endif %}

## Output branching

{% if output == 'print' %}
You are reading the PDF. The site's copy of this card says something else.
{% else %}
You are reading the site. The PDF's copy of this card says something else.
{% endif %}

A card body sees `output` (`print` or `site`) and `target`
([Targets](https://noregressions.github.io/paperband/cards/targets.html)).
