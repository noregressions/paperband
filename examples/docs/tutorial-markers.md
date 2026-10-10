---
title: Install Paperband
layout: quick
---
# Install Paperband

This tutorial gets {{ product }} running on your machine.

## Steps {! id=steps}

### Download the release {! step id=download level=beginner}

The release archive has everything you need, including the launcher script.

Download the latest release archive. {! instruction}

```bash {! command}
curl -LO https://example.com/paperband.zip
```

```console {! output}
100 1024k  100 1024k
```

### Unpack it {! step}

Unpacking creates a [paperband/]{! file} folder.

::: {! instruction}
Unpack the archive in your home directory.
:::

```bash {! command}
unzip paperband.zip -d ~
```

```console {! output}
Archive:  paperband.zip
  inflating: paperband/bin/paperband
```

Literal markers can be escaped: \{! not-a-tag}.
