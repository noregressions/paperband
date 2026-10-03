---
id: make-a-student-edition
oneliner: "Build a second copy of the book with every solution replaced by space to write the answer in."
index: [student edition, solutions, exercises]
---

# Make a Student Edition

A workbook needs two copies: one with the solutions, for whoever runs the course, and one
without, for the people doing the exercises. Paperband builds both from the same cards. Mark
each solution, and a second build with the `student` view prints an empty box where each
one was.

## Mark the solutions

Put `{.solution}` on whatever holds the answer. It can be a whole block, with its heading
and anything nested under it:

````markdown
## Solution {.solution lines=8}

```java
for (int i = 0; i < 3; i++) {
    System.out.println(i);
}
```
````

It can also be a fenced div, or one paragraph or fence inside a block that stays:

````markdown
::: {.solution}
Start the count at zero.
:::

Write a loop that prints 0, 1 and 2.

The loop starts at zero and stops before three. {.solution}
````

In the full book the class changes nothing, because no bundled stylesheet targets it: the
solutions print as they always did.

## Add the build

Add an execution with its own `<output>` that names the `student` view:

```xml
<execution>
  <id>student</id>
  <phase>package</phase>
  <goals><goal>build</goal></goals>
  <configuration>
    <view>student</view>
    <output>${project.build.directory}/student.pdf</output>
  </configuration>
</execution>
```

In this build:

- A solution block becomes a box labelled "Your answer", with its heading and nested blocks
  left out. `lines=8` sets the box's height in lines; the default is 4.
- A solution paragraph, list or fence becomes a box without a label, 4 lines tall.
- Everything else prints as it does in the full book, under the same theme.

The view keeps every card. The site goal takes the view too, with an `<outputDirectory>` of
its own.

## Change the label or the box

The label is `vars.answerLabel`. Set it in the execution's `<book><vars>`, or in
`paperband.yaml`:

```yaml
vars:
  answerLabel: "Work it out here"
```

The box is `.answer-space`, and the label inside it `.answer-space-label`. Restyle them in
the book's CSS. To change what the box is, put your own `layouts/student/_answer-space.html`
in the book. It sees the space's `block`, which keeps the solution's anchor and attributes,
so `block.attributes` is there to use.

To change what the student edition leaves out, put your own `layouts/student/transform.html`
in the book. The bundled one is one statement, which blanks every solution:

```
blank .solution as answer-space
```

This one also leaves out the notes meant for whoever runs the course, and adds space after
each exercise that has no solution of its own:

```
drop .instructor-note
insert {.answer-space} after block.exercise:not(:has(.solution))
blank .solution as answer-space
```

The statements run in order, so the `insert` comes first: once `blank` has run, no exercise
has a `.solution` left to find.

See [Themes](card:themes#changing-the-cards-a-view-writes) for what a transform can do.

## Check

```command
mvn package
```

Open `target/student.pdf` and search it for a line of one of your solutions. It shouldn't be
there. Each solution should be a dashed box, and the rest of the book should match the full
edition.

## Watch Out

A solution is found by its class, so a solution without one prints in the student edition.
Search the student PDF for a line of each solution before handing it out.

`{.solution}` on a heading replaces the class paperband makes from the heading's text:
`## Answer {.solution}` has the class `solution`, not `answer`. Keep the heading's own
class too if a theme styles it: `## Answer {.answer .solution}`.

A book's own `layouts/_block-section.html` still writes every block that isn't a solution:
the view hands those blocks to it. See [Themes](card:themes#views) for how a view reaches the
template it replaces.
