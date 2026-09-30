---
id: workshop-prepare
oneliner: "Check the tools the rest of the workshop needs."
---

# Prepare Your Machine

Everything in this workshop runs through Maven, so the first session only makes sure the
tools are there and new enough. Nothing here writes a file.

## {!step} Check Java

Paperband is a Maven plugin, and the plugin needs Java 21 or later. An older JDK fails
at the first build with an unsupported class-file version, which is a confusing place to
find out.

Check that `java` is on your path and reports version 21 or later. {.instructions}

```command
java -version
```

```console
openjdk version "21.0.4" 2024-07-16
```

If it reports an older version, install a newer JDK and point `JAVA_HOME` at it.

## {!step} Check Maven

Maven 3.9 is the oldest version the plugin is tested with.

Check that `mvn` runs and uses the JDK from the last step. {.instructions}

```command
mvn -version
```

```console
Apache Maven 3.9.9
Java version: 21.0.4
```

The `Java version` line matters more than the Maven one: Maven can run on a different
JDK from the one on your path.

## {!step} Warm the cache

The first build downloads the plugin and a headless Chromium for PDF rendering, which
takes a few minutes. Doing it now keeps the next session quick.

Resolve the plugin once so its dependencies are cached. {.instructions}

```command
mvn dependency:get -Dartifact=dev.noregressions.paperband:paperband-maven-plugin:0.1.3
```

```console
[INFO] BUILD SUCCESS
```
