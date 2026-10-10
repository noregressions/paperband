---
title: Web output keys
---
# Web output keys

| Key        | Values                    | Default                        |
|------------|---------------------------|--------------------------------|
| `single`   | `true`, `false`           | `false`: a page per file       |
| `nav`      | `side`, `top`, `none`     | `side`                         |
| `contents` | a source file or a layout | the page whose layout is `contents` |

Only a `web` output has these keys. In the Maven plugin they're the `<single>`, `<nav>` and
`<contents>` of an `<output>`.
