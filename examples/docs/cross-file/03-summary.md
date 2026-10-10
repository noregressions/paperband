---
title: Summary
---
# {{ page.title }}

This site has [[ site.files | length ]] files:

[% for f in site.files %]
1. [[ f.page.title ]] (`[[ f.path ]]`)
[% endfor %]

## Glossary terms

[% set terms = site.file("01-glossary.md").section("terms").list.items %]
The glossary defines [[ terms | length ]] terms: [[ terms | join(", ") ]].

## Features

[% for item in site.file("02-features.md").section("Supported").list.items %]
- [[ item | upper ]]
[% endfor %]

The first example is written in [[ site.file("02-features.md").section("example").code[0].language ]].

## Escaping

[% verbatim %]Literal post-pass syntax: [[ not.evaluated ]][% endverbatim %]
