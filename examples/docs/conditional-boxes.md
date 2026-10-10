---
title: Conditional boxes
draft: true
layout:
  boxes:
    - { id: title, select: h1, pick: first }
    - id: banner
      text: "Draft - not for publication"
      when: { page: { draft: true } }          # only on pages whose front matter says draft: true
    - id: notes
      select: ".note"
      when: { not-empty: true }                # left out when the page has no notes
    - id: pdf-only
      select: ".print"
      when: { outputs: [pdf] }                 # only in an output named pdf; this one is "site"
    - { id: body, select: rest }
  css: |
    & > .box-banner { padding: 6px 12px; border-radius: 6px; background: #fef3c7; color: #92400e; font-weight: 600; }
---
# Conditional boxes

A box's `when` decides whether it is on the page at all. This page is a draft, so the banner shows.

The notes box has nothing to show, so it is left out entirely: no empty box, no warning.

Printed copies get an extra line, but only in a pdf output. {! print}
