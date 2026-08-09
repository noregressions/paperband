---
id: quickstart
oneliner: "Build and run Paperband from source in five minutes."
---

# Quickstart

## Requirements

| Requirement | Notes |
|---|---|
| JDK 21+ | Must be a JDK (not JRE) — Maven needs `javac` |
| Maven 3.8+ | Standard Maven install |
| Playwright | The only renderer; downloads ~300 MB Chromium on first use |

## Clone and build

```bash
git clone https://github.com/gruff-dev/pagewright.git
cd pagewright
mvn -DskipTests package
```

The build produces a shaded all-in-one jar. The parent POM version:

{% fragment "../../../pom.xml:version-declaration" %}

The jar lands at:

```
pagewright-cli/target/pagewright-cli-0.1.0-SNAPSHOT-all.jar
```

## Create a shell alias

```bash
alias pagewright='java -jar /path/to/pagewright-cli-0.1.0-SNAPSHOT-all.jar'
```

Add it to your shell profile to persist across sessions.

## Build your first book

```bash
# PDF
pagewright build path/to/your-book out.pdf

# Static site
pagewright site path/to/your-book out-site/
```

A "book" is any directory containing a `pagewright.yaml` at its root. Cards are any
`.md` files found recursively under it.

## Explore what's available

```bash
# List discovered renderers and whether each is available in your environment
pagewright renderers

# List all themes (built-in and any under --theme-dir)
pagewright themes
```

## Watch Out

The first Playwright render downloads headless Chromium to `~/.cache/ms-playwright/`.
In a CI environment without internet access, pre-cache it (or point
`PLAYWRIGHT_BROWSERS_PATH` at an existing download) before the first build.
