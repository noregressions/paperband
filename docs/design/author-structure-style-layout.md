# Design: structure, style and layout without CSS

Status: concept, for discussion. Views (section 4) are built; the rest isn't.
Author: Steve Poole
Date: 2026-10-02, views revised 2026-10-03

## The problem

A paperband author writes Markdown. As soon as they want to change how something
looks or where it sits, they have to switch to a different language.

Keeping a block on one page means knowing the `.pw-avoid-split` class. Shrinking one
wide code block means `{.fs--1}`, a name you learn from a comment in `book.html`. Putting
a card's "what changed" section above its introduction means writing a Pebble template
that calls `card.slots.take(...)`. And a home page with a banner and a grid of sections is
an HTML file. The guide's own `layouts/site-index.html` hard-codes four card URLs,
and its header comment warns whoever renames one of those cards to update it by hand.

None of these are unusual requests. They're the common decisions, and every one of them
currently needs CSS, HTML or Pebble.

This note describes a future paperband where authors make those decisions in Markdown,
frontmatter and yaml. CSS and HTML become the theme's implementation, not something an
author has to touch.

## The governing principle

**Authors say what a thing is and what they want. Themes decide how it looks.**

An author can name a block, say what kind of block it is, choose from a fixed set of
settings, and describe how a page is put together. They can't write CSS, colours as
values, pixel sizes or HTML. Each restriction exists so that switching theme, or
switching from PDF to slides, keeps the author's intent intact.

## Three layers

| Layer | The author writes | Paperband produces | The theme owns |
|---|---|---|---|
| **Structure and identity** | ids, kinds, directives, containers | a model of named, addressable elements | nothing: structure is theme-neutral |
| **Settings** | a fixed set of keys, with values from fixed lists | resolved values on the model, plus `--pb-*` variables and `data-pb-*` attributes | what each value looks like |
| **Layouts** | regions, what goes in them, how they're arranged | the same templates and slots paperband uses today | the CSS for each arrangement |

Each layer uses the previous one. Settings attach to the elements that structure names,
and layouts pick content by the same names.

Alongside the three layers sits one more idea, about editions rather than looks. A
**view** filters and reshapes the data before any layout sees it, so a layout never has
to know whether it's writing the full book, a student edition or a cheat sheet. Section 4
covers it; it's the part that's built.

## 1. Structure and identity

Most of this layer exists already. Paperband has `{#id}` and `{.class}` on headings and
paragraphs, `{.x}` … `{/x}` around part of a sentence, and directives such as `{!step}`
and `{!number}`. The change is to give each mark one job and to make the set of names
something a book declares.

### Three kinds of mark, three jobs

| Mark | Means | Used for | Example |
|---|---|---|---|
| `#id` | **which one**: unique in the card | extracting or linking to one element | `## Upgrade steps {#upgrade}` |
| `.kind` | **what it is**: a role such as `warning`, `aside` or `solution` | selecting, styling, presets | `## Watch out {.warning}` |
| `!directive` | **what paperband does to it** | numbering, steps, behaviour | `### {!step} Install` |

Today a class does two jobs: it names what a block is, and it's a CSS hook. In this
design, a class is only a kind. Whether a kind changes the look is up to the theme and
the book's presets (section 2).

### Declared kinds

A book lists the kinds it uses, once:

```yaml
# paperband.yaml
kinds:
  warning:  { about: "Something that will bite the reader" }
  aside:    { about: "Background the reader can skip" }
  solution: { about: "An exercise answer; the student view hides it" }
```

A kind that isn't declared, such as `{.warnign}`, fails the build and names the card and
line. This works the way mistyped directives are reported today. The declaration also
gives the guide something to list, and gives a layout a known set of names to select from.

Paperband would declare its own kinds, such as `solution` and `command`, so a book only
lists the ones it adds.

### Containers

A heading section can't group three paragraphs and a code block into one aside unless
they get a heading of their own. Paperband already has the answer: a fenced div, Pandoc's
syntax, which the student edition uses for solutions.

```markdown
::: {.aside}
Background the reader can skip. It holds as many paragraphs,
lists and fences as it needs.
:::
```

It loads as a block like any other (`Block.Kind.FENCED_DIV`): it has an id and classes,
slots take it, and a view's transforms select it. What this design adds is settings on it,
from section 2.

### Addresses

Every element with an id or a kind can be addressed from outside its card:

```
card:upgrade-guide#upgrade        one element
card:upgrade-guide .warning       every warning in that card
.warning                          every warning in the current scope
```

Slots, views, the cheat sheet, the slides renderer and the layouts in section 3 should all
use these addresses. Part of that has happened: `query` and every view transform see the
same tree, cards holding blocks holding nodes, through one CSS selector, so
`block.solution`, `p.solution` and `card:has(pre.command)` mean the same thing wherever
they're written. Slots still match by name (`take('watch-out')`), and `select` still reads
HTML. The `#id` and `card:x` forms above would be a thin layer over the selectors, not a
new matcher.

## 2. Settings

Authors get a fixed set of settings, each with a fixed list of values. They don't get CSS.

### Two families

**Structural settings** take direct values, because they mean the same thing in every
theme:

| Key | Values | Today |
|---|---|---|
| `breaks` | `keep-together`, `new-page`, `auto` | `.pw-avoid-split`, `.pw-page-start` |
| `code` | `smaller`, `small`, `normal` | `{.fs--2}`, `{.fs--1}` |
| `measure` | `narrow`, `normal`, `wide`, `full` | `page.measure` (book only) |
| `density` | `compact`, `normal`, `roomy` | nothing; each theme sets its own spacing |
| `align` | `start`, `center`, `end` | nothing |
| `width` (images, figures) | `small`, `half`, `full` | nothing |
| `answerLines` | a number | `--answer-lines` |
| `show` | a target predicate | `where:` in yaml only |

**Appearance settings** take tokens. A token is a role, and the theme decides what it
looks like:

| Key | Tokens |
|---|---|
| `tone` | `info`, `success`, `warning`, `danger`, `muted` |
| `accent` | `1` … `n`, as many as the theme provides |
| `font` | `body`, `display`, `mono` |
| `size` | `smaller`, `small`, `normal`, `large`, `larger` |
| `emphasis` | `quiet`, `normal`, `strong` |

There are no raw colours, font names or point sizes. `tone=warning` stays meaningful in
the dark theme, in `herodevs`, and on a slide, and `color=#c33` wouldn't. Sizes come as
steps rather than units, for the same reason `{.fs--1}` steps today: a theme with a 9pt
body and a theme with an 11pt body can both make "small" mean something sensible.

### One set of keys at every level

The same keys work at every level, and the innermost value wins, which is how `vars`
cascade today:

```yaml
# paperband.yaml or a folder yaml
settings:
  density: compact
  code: small
```

```yaml
# card frontmatter
settings:
  measure: wide
```

```markdown
## Install {breaks=keep-together}

::: {.aside tone=muted size=small}
…
:::

Run it {.x tone=danger}twice{/x} and the second run deletes the cache.
```

### Kinds carry defaults

Most of the time an author shouldn't need settings in the text at all. A kind can carry
default settings, so the decision is made once, in the book's yaml:

```yaml
kinds:
  warning: { about: "…", tone: warning, breaks: keep-together }
  aside:   { about: "…", tone: muted, size: small }
```

`## Watch out {.warning}` then gets the right look in every card. A setting written on
the element still wins over the kind's default. In practice this is how a book would
define a house style: a few kinds with defaults in yaml, and Markdown that only says what
things are.

### Brand values live in the book, not the text

Some books do need a specific colour or typeface, such as a company's brand blue. That
belongs in book-level yaml, as an override of a theme token:

```yaml
theme: classical
tokens:
  accent-1: "#0b5fff"
  font-display: "Inter"
```

This is the only place a literal colour or font name comes in. It applies to the whole
book, and it's set in one file, not repeated across the Markdown.

### What paperband does with a setting

Paperband resolves each setting into the model: the element's own value, then its kind's
default, then the card, folder and book values. Then:

- HTML targets get the resolved value as a `--pb-*` custom property or a `data-pb-*`
  attribute on the element. `page.measure` already works this way for `--card-max-width`.
- The base CSS reads those variables, and themes read them too, with their own defaults.
- Renderers that don't use CSS, such as `render-pptx`, read the resolved values straight
  from the model. Each setting either has a meaning there or is ignored there on purpose,
  and the reference says which.

Because the values are resolved in the model, not only in CSS, slides and other future
renderers can use them.

## 3. Layouts

A layout today is a Pebble template that writes HTML. That works for theme authors. For an
author who only wants the introduction first, two columns for before and after, and the
remaining blocks after that, it's too much.

A declarative layout describes the page as **regions**. Each region says what content it
takes and how that content is arranged. Paperband turns the description into the same
template and slot calls it uses today.

### A card layout

```yaml
# layouts/migration-chapter.yaml
card:
  regions:
    - take: intro
    - take: what-changed
      settings: { tone: info }
    - arrange: columns
      regions:
        - take: before
        - take: after
    - rest: true
    - require: check
      settings: { breaks: keep-together }
```

`take`, `require` and `rest` mean what they mean in `card.slots` today, and they keep the
same checks. A block that no region places fails the build, and so does a missing
`require`. A folder picks the layout with the existing `layout:` key.

### Arrangements

An arrangement is one of a fixed set that paperband implements:

| Arrangement | What it does |
|---|---|
| `stack` | one after another (the default) |
| `columns` | side by side, collapsing to a stack on narrow screens |
| `grid` | tiles, as on a section landing page |
| `sidebar` | a main region and a narrow one beside it |
| `split` | a region per page or per slide |

Paperband implements each arrangement in base CSS and in the slides renderer. Themes
style them but don't change what they do. An author never sees the HTML or the CSS.

### Picking parts of content

A layout shouldn't pick parts of content at all. Choosing that a cheat sheet holds each
step's heading, its instructions and its command is a view's job (section 4). By the time
a layout sees a card, the parts it shouldn't show are gone. The cheat-sheet layout is then
only about arrangement:

```yaml
# layouts/cheatsheet.yaml
card:
  regions:
    - each: "!step"
      settings: { breaks: keep-together }
```

### Page layouts

The same approach covers pages that aren't cards: the home page, section landings and
dividers. As an example, here is the guide's own `site-index.html` written as a layout:

```yaml
# layouts/site-index.yaml
page:
  regions:
    - hero:
        title: book.title
        tagline: book.series
        lede: book.subtitle
        actions:
          - { label: "Take the tutorial", to: "card:your-first-book", emphasis: strong }
          - { label: "Start a new book",  to: "card:start-a-new-book" }
          - { label: "Use existing Markdown", to: "card:use-existing-markdown" }
          - { label: "GitHub", to: "https://github.com/noregressions/paperband" }
    - body: book
    - heading: "Browse the guide"
      arrange: grid
      each: sections
      show: [label, count]
```

The action links are now `card:` links, so the build checks them the way it checks links
in Markdown. The comment in today's template, telling whoever renames a card to update
the template by hand, isn't needed any more.

### Pebble stays as the escape hatch

A declarative layout compiles to the templates paperband already has, so nothing is lost.
When a page needs something the regions can't express, the author writes a Pebble
template exactly as they would today.

## 4. Views: filtering the data

*Built: commits a4b68be, 31c79bd, f5490a4 and the card-choosing and cheat-sheet
commits after them.*

A view used to be a set of templates, so a view that changed what a card holds did it in
whichever template wrote that part. The student edition blanked solution paragraphs in
`_block-content.html` and solution blocks in `_block-section.html`, and the site's page
rail, `card.steps` and `keep.html` still saw the solutions. The split is now:

| | Decides | Written as |
|---|---|---|
| **View** | which cards the edition holds, and what each one holds | `keep.html`, and `transform.html` |
| **Layout** | how whatever survives is arranged and written | templates today; the regions of section 3 later |

A view's `transform.html` runs once per card, as the card's model is made, so every
template sees the changed card: the body, the rail, `card.steps`, `card.slots` and
`keep.html` itself. The student edition is now one statement:

```
blank .solution as answer-space
```

and a book that also wants to leave out instructor notes and add space after an open
exercise writes:

```
drop .instructor-note
insert {.answer-space} after block.exercise:not(:has(.solution))
blank .solution as answer-space
```

### One set of operations, two ways to write them

The operations are Pebble filters, `drop`, `keep`, `addClass`, `removeClass`, `set`,
`replace`, `insertBefore`, `insertAfter`, `prepend`, `append`, `wrap` and `blank`. They
take a card or a list of cards and return a changed one, so a template author chains them:

```
{{ result(card | drop('.instructor-note') | blank('.solution', 'answer-space')) }}
```

The statements are a front end over the same filters, for an author who doesn't write
Pebble. Their form follows XQuery Update, which reads as a sentence and needs no quoting:

| Statement | Filter |
|---|---|
| `drop SEL`, `keep SEL` | `drop`, `keep` |
| `blank SEL as NAME` | `blank` |
| `replace SEL with BLOCK` | `replace` |
| `insert BLOCK before\|after SEL` | `insertBefore`, `insertAfter` |
| `insert BLOCK first\|last in SEL` | `prepend`, `append` |
| `wrap SEL in BLOCK` | `wrap` |
| `add .NAME to SEL`, `remove .NAME from SEL` | `addClass`, `removeClass` |
| `set ATTRS on SEL` | `set` |

A `transform.html` that calls `result(...)` uses the filters; one that doesn't is read as
statements. It's still a template either way, so `{% if output == 'site' %}` chooses
statements for free.

### Decisions made along the way

- **Empty blocks of a kind, not blanks.** A student edition needs to *add* an answer block
  of a particular kind, not only hollow out a solution. A new block is written the way a
  card writes attributes, `{.answer-space lines=4}`, and is an empty block or `div` with
  that id, classes and attributes and nothing else. It can't carry what the content policy
  strips from a card, or a URL attribute, so a view can't add markup a theme didn't ask for.
- **Innermost wins.** `replace` keeps the id and attributes of what it replaces, and the
  match's values beat the statement's. The statement gives a default; `{lines=8}` on one
  solution still sizes its space.
- **In order, not as a snapshot.** XQuery Update applies all its changes together at the
  end. These run in order, as a pipe does: each statement finds every match before it
  changes anything, so it never matches what it added, and the next statement sees the
  result. It's easier to follow, and the cost is that order matters. The student example
  above has to insert before it blanks.
- **A transform can choose cards too.** `drop card[draft=true]` or
  `keep card:has(.exercise)` leaves the card out, and the view doesn't hold it, so a view
  can be a `transform.html` alone. `keep.html` stays for views that want it; with both, a
  card has to pass each. This runs card by card, which is enough for any rule about the
  card itself. A rule comparing cards would need the transform to see the whole book,
  and nothing has asked for that yet.
- **Numbers come from the whole book.** A view doesn't renumber what it keeps, so a
  chapter has one number in every edition. The chapter-numbering design records it, under
  "Editions".
- **The cheat sheet is a view in this sense.** Its `transform.html` cuts each card to its
  steps and each step to the parts `cheatsheetSelect` picks; its card body only arranges
  steps. Every template sees the cut card. One thing changed doing it: the old `select`
  pulled a nested command out of its list, and the transform keeps the whole list,
  because it works on a step's top-level parts. Kitchen-sink's cheat sheet was
  unchanged apart from whitespace between tags.

## The ladder

Each step down needs more skill, and we expect most books to stop at the second:

1. Kinds and settings in Markdown and frontmatter.
2. Kind defaults and token overrides in yaml: the book's house style.
3. Views as statements, for editions of the same book; declarative layouts.
4. Pebble templates in `layouts/`, and filter chains in a view.
5. A theme: CSS and template overrides.

Today, apart from views as statements, everything below step 1 starts at step 4.

## The theme contract

All of this depends on themes doing their part. A theme that hard-codes a block's spacing
makes `density` do nothing, and the author can't tell why.

So a theme must:

- read the `--pb-*` variables, setting its own defaults for them
- give each appearance token a value, and say how many accents it has
- style each arrangement without changing what the arrangement does

Each built-in theme gets a test that checks this, alongside `ThemeTargetLayerTest`. A
book that uses a token the active theme doesn't provide, such as `accent=5` against a
theme with three accents, fails the build and names the theme.

## One definition, three uses

Each key, kind, token and arrangement is declared once, in code, with its type, allowed
values, default and description. That one declaration drives:

- validation, so unknown keys and values fail the build with a clear message
- the guide's reference pages, so the documentation can't drift from the code
- the mapping to `--pb-*` variables and to the slides renderer

The idea comes from Markdoc's schema, but without a separate schema language. Validation
stays in code, as it does for the plugin configuration.

## What stays out

- **CSS, HTML and literal colours in Markdown.** If an author needs those, they use a
  template or a theme.
- **Units.** Sizes are steps. Lengths, if any are ever needed, are book-level yaml.
- **Positioning.** There is no "put this 3cm from the top". Arrangements are the only way
  to place content.
- **`style=` as an attribute.** Attribute passthrough probably lets it through today. We
  need to decide whether to strip it, warn about it, or leave it as an undocumented
  escape hatch.

## Open questions

- **Span syntax.** Extend the existing `{.x}` … `{/x}` to take settings, or adopt Pandoc's
  `[text]{tone=warning}`? Extending is consistent with what authors already write, while
  Pandoc's form is the one other tools recognise.
- **Declaring kinds.** Is a declaration required for every class, which would break
  existing books, or only for kinds that carry defaults? A warning period may be enough.
- **Layout file format.** yaml matches the rest of the config, but deeply nested regions
  get hard to read in yaml. A small dedicated syntax might read better, at the cost of one
  more thing to learn.
- **Existing themes.** Every built-in theme has hard-coded values that would need to
  become `--pb-*` variables. That work is large, but it can be done one theme at a time.

## A first slice

1. Structural settings only (`breaks`, `code`, `measure`, `density`, `align`,
   `answerLines`), set through yaml, frontmatter and block attributes, with validation.
2. Kinds with defaults, for the structural settings.
3. `tone` as the first appearance token, implemented in two themes.
4. One declarative card layout that reproduces an existing slot template, to check that
   the compilation to templates holds up.

Page layouts and the rest of the tokens come after that, once the first slice shows the
cascade, the validation and the theme contract work in practice. Views (section 4) were
built first, ahead of this slice, because the student edition needed them.
