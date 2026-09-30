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
`{.instructions}`, and put the command in a ` ```command ` or ` ```console ` fence:

````markdown
## {!step} Install the tools

Paperband runs on the JVM, so it needs a JDK and Maven.

Install a JDK, version 21 or later, and Maven. {.instructions}

```command
java -version
```
````

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

The guide's own execution is untouched: the view is named only in this one.

## Change what each step contributes

`cheatsheetSelect` is a CSS selector for the parts of each step to keep. The default is
`.instructions, pre.command, pre.console`. To keep figures too:

```xml
<cheatsheetSelect>.instructions, pre.command, pre.console, figure</cheatsheetSelect>
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
outline land there.

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
