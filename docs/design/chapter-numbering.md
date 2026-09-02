# Design: chapter numbering

Status: accepted, unimplemented
Author: Steve Poole
Date: 2026-09-02

## The problem

A book that numbers its chapters currently has to write those numbers by hand,
in two places at once: the filename that orders the chapter, and every
cross-reference that points at it.

The book that prompted this — *The JDK 8 to JDK 26 Migration Guide* — has 59
chapters and **214 hand-written cross-reference labels** of the form:

```markdown
[Chapter 3.14](card:unsafe-memory-access)
```

Paperband already validates the *target*: `CardLinks` fails the build on an id
that resolves to nothing. It does not validate the *number*, so "3.14" is
unchecked prose. Today all 214 happen to be correct — but only because nobody
has renumbered anything yet. Insert one chapter and an arbitrary number of them
become quietly wrong, with nothing to catch it.

The same numbers are also invisible where a reader needs them: no chapter number
appears in any heading or in the table of contents, so "see Chapter 3.14" is a
pointer to something the reader cannot find.

## The governing principle

**A chapter's number is derived, never authored.**

Everything below follows from collapsing the number to a single source.

## Three concerns, currently conflated

| Concern | Comes from | Changes when |
|---|---|---|
| **Identity** | the `card:` id | never |
| **Order** | the filename prefix | a chapter moves |
| **Number** | *computed* | the book is restructured |

The first two already work. The third is what this design adds — and critically,
it must come from **neither** of the other two.

Tying the number to the filename would mean renaming a file to renumber a
chapter. That is wrong on its own terms, and doubly so in practice: the JDK
book's companion repository has **94 URLs pointing at content filenames**, so
filenames are externally load-bearing. Renumbering must never require a rename.

## Numbering modes

### `sequential` (default when numbering is on)

The number is derived by counting position: group ordinal, then card ordinal
within the group. Gaps close themselves. Nothing is ever renamed.

This is the right mode while a book is still moving. Chapters can be added,
removed and reordered freely, and the numbers simply follow.

### `pinned`

The number is declared in card frontmatter:

```yaml
number: "3.39"
```

Stable forever, independent of position and of filename.

This is the right mode after publication, when chapter numbers have escaped into
talks, blog posts, errata and readers' notes. A number that shifts because
someone inserted a chapter is a defect at that point, not a convenience.

### The transition is the feature

Authors must never hand-type a number. The intended lifecycle is:

1. Write the book in `sequential`. Numbers stay correct through every
   restructure, for free.
2. At v1.0, run a goal that **freezes** the computed numbers into each card's
   frontmatter, converting the book to `pinned` in one mechanical pass.
3. After that, inserting a chapter gives it a new number and disturbs nothing
   already published.

Without step 2 an author eventually types numbers by hand, and the whole design
collapses back to the problem it set out to solve.

## Numbering groups

The unit of numbering is a **group**, not a section.

By default every section is its own group, numbered by section ordinal. That is
what paperband's own guide wants: `01-getting-started` yields 1.1, 1.2, …, and
`02-authoring` yields 2.1, 2.2, ….

But a section is not always the right unit. The JDK book has **nine sections and
four parts** — its "Part 3 — The Failure Catalogue" spans five sibling folders
(`04-wont-start` through `08-environment`), and chapter numbers run *continuously*
across all five: 3.1 … 3.39.

So a section may declare which group it belongs to, in `_section.md`
frontmatter (already parsed — this is where `cards: true` lives):

```yaml
---
part: 3
---
```

Sections sharing a `part` number as one continuous sequence, in book order.
Sections that declare nothing are their own group, as today.

### Opting out

Front matter and appendices are named, not numbered. A section declares:

```yaml
---
numbered: false
---
```

and its cards get no number at all — no group ordinal, nothing rendered, and no
cross-reference label substitution. Without this, a back-matter folder ordered
as `99-appendix` would invent a "Part 99".

## What the model must expose

The number must reach templates as **structured data, not a formatted string**,
so a template can render `3.14`, `Chapter 3.14` or `14` without string surgery:

- group ordinal
- ordinal within group
- whether this card is numbered at all

`Card` already carries `source` and `frontmatter`; this is a computed sibling of
those, resolved once the book walker has established order.

## Rendering

### Headings and the table of contents

Opt-in per book, with the book controlling the format — some want `3.14 Title`,
some want `Chapter 3.14 — Title`, some want none. **A book that does not opt in
must render exactly as it does today.**

### Cross-reference auto-labelling

The piece that retires the hand-written labels. `CardLinks` already rewrites and
validates `href="card:id"` in the same pass, so this rides along:

```markdown
[](card:unsafe-memory-access)         → "Chapter 3.14"
[title](card:unsafe-memory-access)    → "Chapter 3.14 — sun.misc.Unsafe Memory-Access Methods"
[the Unsafe chapter](card:unsafe-…)   → unchanged; the author's words win
```

The third form matters. A scheme that forbids prose from naming a chapter in its
own words would be worse than the problem.

## Validation

**A hand-written number that disagrees with the computed one is a build error**,
exactly as a dangling `card:` id already is.

This is the most valuable single item in this design. Paperband already made
broken cross-reference *targets* impossible; broken *numbers* are the same class
of bug and are currently invisible. With this in place, restructuring a numbered
book stops being risky.

Under `sequential` there is no such thing as an out-of-sequence chapter, so the
check has nothing to say beyond catching stale hand-written labels. Under
`pinned` it earns its keep: duplicate numbers, and numbers contradicting a
declared sequence.

## Non-goals

- **Numbering stays optional.** Unnumbered books are unaffected, and no existing
  book changes output unless it opts in.
- **No renumbering on the author's behalf, ever** — outside `sequential`, where
  that is the explicit contract.
- **No new required frontmatter.**
- **Numbers never leak into identity.** Not into URLs, anchors, site filenames or
  PDF destinations. Renumbering must not break an external link or a bookmark.

## Worked example: what this does to the JDK book

Under `sequential`, 26 of 59 chapters change number:

- **Part 0 only gains.** The introduction becomes 0.1; "The Five Levels of
  Failure" and "Playing Detective" stay 0.2 and 0.3, so every existing reference
  to them remains correct — and a long-standing numbering gap closes by itself.
- **Parts 1 and 2 are unchanged.**
- **Part 3 shifts.** `final-field-mutation`, currently filed out of sequence as
  `03.39` inside `05-runtime-crashes`, becomes **3.16** with no rename; old
  3.16–3.38 each move up one, ending at 3.39. Contiguous, 39 chapters.
- **Back matter** declares `numbered: false`.

**58 of the 214 existing hand-written labels would become wrong.** That is the
argument for shipping auto-labelling and validation together with the numbering
rather than after it: land the numbering alone and the book is silently wrong in
58 places.
