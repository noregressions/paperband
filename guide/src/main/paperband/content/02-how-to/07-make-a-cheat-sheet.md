---
id: make-a-cheat-sheet
oneliner: "Build a second document from the same cards: each step's title, the paragraph that says what to do, and its command."
index: [cheat sheet, steps, instructions]
---

# Make a Cheat Sheet

A cheat sheet is a guide's steps without the explanation: each step's heading, the one
paragraph that says what to do, and the command that does it. Paperband builds it from the
same cards as the guide, as a second build, so the two can't drift apart.

## Mark the steps and what to keep

Number each step heading with `{!step}`, end the paragraph that says what to do with
`{.instructions}`, put the command in a ` ```command ` fence, and put what it prints, if a
reader needs to see it, in a ` ```console ` fence after it:

````markdown
## {!step} Install the tools

Paperband runs on the JVM, so it needs a JDK and Maven.

Install a JDK, version 21 or later, and Maven. {.instructions}

```command
java -version
```

```console
openjdk version "21.0.4" 2024-07-16
```
````

A ` ```command ` block holds the command as it's typed, with no `$` prompt, since its copy
button copies it whole. A ` ```console ` block is a terminal session: output, or prompt and
output together. Keep them apart and the cheat sheet shows the command on its own, ready to
copy, with the output under it.

`{.instructions}` doesn't change how the paragraph looks in the guide, because no bundled
stylesheet targets it. Everything else under the heading stays in the guide and out of the
cheat sheet.

## Add the build

Add an execution with its own `<output>` that names the `cheatsheet` view:

```xml
<execution>
  <id>cheatsheet</id>
  <phase>package</phase>
  <goals><goal>build</goal></goals>
  <configuration>
    <book>
      <title>Setup cheat sheet</title>
      <includes>
        <include>setup/**/*.md</include>
      </includes>
      <vars>
        <toc>false</toc>
      </vars>
    </book>
    <view>cheatsheet</view>
    <output>${project.build.directory}/cheatsheet.pdf</output>
  </configuration>
</execution>
```

`<includes>` picks the files, relative to the book's `content/` directory. Leave it out to
use the whole book. In this build:

- Each card shows its title, then one entry per step: the heading, the `.instructions`
  paragraph, and any ` ```command ` or ` ```console ` block. A step inside a step is
  indented under its parent.
- Cards flow on, one after another, rather than each starting a page.
- A card with no `{!step}` is left out, and the build log names it. A `card:` link to it
  prints as plain text, the same as a link to a card a `select:` leaves out.
- Section dividers still come before each section's first card, with the section's
  `_section.md` text. To drop them, give the view a `dividers.html` that prints nothing (see
  [Change the view](card:make-a-cheat-sheet#change-the-view)).

The guide's own execution is untouched: the view is named only in this one.

## Change what each step contributes

`cheatsheetSelect` is a CSS selector for the parts of each step to keep. The default is
`.instructions, pre.command, pre.console`. To keep figures too, set it in the cheat-sheet
execution's `<book><vars>`:

```xml
<vars>
  <cheatsheetSelect>.instructions, pre.command, pre.console, figure</cheatsheetSelect>
</vars>
```

The selector can also go in `paperband.yaml`, for the whole book or one folder. Only the
cheatsheet view reads it, so the full guide's build is unaffected:

```yaml
vars:
  cheatsheetSelect: ".instructions, pre.console"
```

## A cheat-sheet site

The view works for the `site` goal too. Add a second execution with the same `<book>`,
`<view>cheatsheet</view>` and an `<outputDirectory>` of its own, and each card's page becomes
its steps.

## Change the view

A view is a folder of templates: the bundled `cheatsheet/` has two, and anything it doesn't
have comes from the defaults. To change one, put a file of the same name in the book's
`layouts/cheatsheet/`, or in a theme's `cheatsheet/`:

| Template | What it decides | Bundled |
|---|---|---|
| `keep.html` | Which cards the build holds: prints `true` or `false` for each card | `{{ card.steps is not empty }}` |
| `_card-body.html` | What each card becomes | The title, then per step its heading and the parts `cheatsheetSelect` picks |

Both see the card's model, including:

| Key | What's in it |
|---|---|
| `card.steps` | Each stepped block in document order, as `{block, depth}`: `depth` is 0 for a top-level step, 1 for a step inside one |
| `card.vars` | The card's own cascaded vars, such as `card.vars.cheatsheetSelect` |
| `step.block.html \| select(...)` | The parts of a step's HTML matching a selector (see [Themes](card:themes#picking-parts-out-of-a-block)) |
| `step.block \| find(...)` | The same parts as data: text, a fence's `code` (see [Themes](card:themes#reading-a-block-as-data)) |

A card `keep.html` leaves out has no page and no contents entry, and no divider fires for
it, because the build leaves it out before working any of that out. Keep
`id="card-{{ card.id }}"` on what `_card-body.html` writes: `card:` links and the PDF
outline land there, and a card the view keeps but nothing prints fails the build.

This `layouts/cheatsheet/_card-body.html` writes each card as a table: a row per step, with
the instruction as text and the command as the guide prints it. `| html` writes the command
through its block template, so it keeps its label and copy button (see
[Themes](card:themes#changing-what-a-block-prints)):

```
<article class="cheatsheet-card" id="card-{{ card.id }}">
  <h2 class="cheatsheet-card-title">{{ card.title }}</h2>
  <table class="steps">
  {% for s in card.steps %}
    {% set what = s.block | find('.instructions') | first %}
    {% set cmd = s.block | find('pre.command') | first %}
    <tr class="depth-{{ s.depth }}">
      <th>{{ s.block.heading }}</th>
      <td>{% if what is not null %}{{ what.text }}{% endif %}</td>
      <td>{% if cmd is not null %}{{ cmd | html | raw }}{% endif %}</td>
    </tr>
  {% endfor %}
  </table>
</article>
```

To drop the section dividers from the cheat sheet only, add `layouts/cheatsheet/dividers.html`
with nothing in it: it decides the dividers in this view, and the full guide keeps its own
(see [Themes](card:themes#dividers)).

A view of your own is a new folder: `layouts/handout/keep.html` (even just `true`) makes
`<view>handout</view>` a view, and every other template it ships replaces the default of the
same name for that build.

## Check

```command
mvn package
```

The log lists the cards the view left out, as `View 'cheatsheet': left out 3 card(s) its
keep.html doesn't keep: …`. Open `target/cheatsheet.pdf`: each card should appear under its title with
its steps in order. If none of the chosen cards has a step, the build fails and says so.

## Watch Out

Put `{.instructions}` on the paragraph, not on the step's heading. A class on a heading
replaces the class paperband makes from its text, which a theme may be using.

A ` ```bash ` fence, or any other language, isn't a `pre.command`, so the default selector
leaves it out and the step appears without its command. Use ` ```command `, or keep the
language and add the class: ` ```bash {.command} `.

The view replaces the default card body, a book's own included: a `layouts/_card-body.html`
doesn't apply to the cheat sheet, because the view's `cheatsheet/_card-body.html` is found
first. Put what the cheat sheet should have in `layouts/cheatsheet/_card-body.html`.

A build that still sets `vars.cheatsheet` fails and says what to write instead. Cheat-sheet
mode became the view: replace `<cheatsheet>true</cheatsheet>` in `<vars>` with
`<view>cheatsheet</view>`.
