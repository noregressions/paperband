---
title: A back-of-book index
---
# A back-of-book index

Mark a term where the text discusses it, and the index page gathers every mark. {! index="index markers"}

A marker at the end of a paragraph or heading indexes that element. Several terms go in one
marker, separated by `;`, and `!` puts a subentry under a term. {! index="index markers!several terms; subentries"}

With no value, the marked element's own text is the term: [JAXB]{! index} here, and
`jdeps`{! index} as code. In a value, write `` \` `` for a backtick: {! index="\`jdeps\`!in a value"}

Terms can also be found without markers: a manifest's `index:` file lists them, and each file is
indexed where it first discusses each one (`IndexSpec` has the format). Markers then cover only
what the rules miss.

## Index

The index is built in the post pass from `site.index`: terms sorted ignoring case, Markdown and
leading punctuation, grouped by letter, each with the places it's marked. A link tagged
`pageref` shows the page number in PDF output; elsewhere it keeps its own text.

[% set mark = "{" ~ "! " %]
[% for group in site.index.letters %]
### [[ group.letter ]]

[% for t in group.terms %]
[% set at = "" %]
[% for l in t.locators %][% set at = at ~ " [" ~ l.file.page.title ~ "](#" ~ l.anchor ~ ")" ~ mark ~ "pageref}" %][% endfor %]
- [[ t.term ]][[ at ]]
[% for s in t.subentries %]
[% set sat = "" %]
[% for l in s.locators %][% set sat = sat ~ " [" ~ l.file.page.title ~ "](#" ~ l.anchor ~ ")" ~ mark ~ "pageref}" %][% endfor %]
  - [[ s.term ]][[ sat ]]
[% endfor %]
[% endfor %]
[% endfor %]
