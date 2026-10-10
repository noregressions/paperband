---
title: Features
---
# Features

## Supported

- Combined AST
- Pre and post template passes
- Cross-file references

## Example

```jsp
<%-- JSP-style tags aren't template syntax, so they survive both passes --%>
<p><%= request.getParameter("name") %></p>
```
