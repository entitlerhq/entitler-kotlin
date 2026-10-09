# Feature constants

`entitler generate` reads your catalogue (as `features()` does, with a key holding `plans:read`) and
writes an `object Features` with one typed constant per feature, sorted by key:

```kotlin
public object Features {
    /**
     * AI credits
     *
     * Spent on AI actions each month.
     *
     * Counted in credits.
     */
    public val aiCredits: Feature.Metered = Feature.Metered("ai_credits")
```

Each constant's type carries the feature's type: `Feature.OnOff`, `Feature.Setting`, `Feature.Metered`
or `Feature.Group` (with its leaf members). Archived features are marked `@Deprecated("Archived in
Entitler.")`.

## Running it

Download `entitler-generator-0.1.0-all.jar` from Maven Central and run it:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --out src/main/kotlin/Features.kt --package com.example
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--out <file>` | `src/main/kotlin/Features.kt` | the file to write |
| `--package <name>` | none | the Kotlin package to declare |
| `--key <key>` | `ENTITLER_KEY` | an API key with `plans:read` |
| `--base-url <url>` | `https://api.entitler.dev` | the API's base URL |
| `--check` | | fail when the file is out of date, without writing it |
| `--help` | | show the usage |

Options take `--flag value` or `--flag=value`. It exits with 0 on success or when the file is up to
date, and 1 otherwise.

Or run it from Gradle, with a `JavaExec` task:

```kts
val entitlerGenerator by configurations.creating

dependencies {
    entitlerGenerator("com.entitlerhq:entitler-generator:0.1.0")
}

tasks.register<JavaExec>("generateFeatures") {
    classpath = entitlerGenerator
    mainClass = "com.entitlerhq.entitler.generator.MainKt"
    args("generate", "--out", "src/main/kotlin/Features.kt", "--package", "com.example")
}
```

## Stable names

A constant is named by converting its key to camel case (`export_pdf` becomes `exportPdf`). When the
file exists, each feature keeps the name it has there, so adding a feature never renames another.
Reserved words take a trailing underscore (`in_`), and a name already taken takes a number (`exportPdf2`),
decided in key order after the kept names. Features no longer listed disappear.

## In CI

`--check` compares the file it would write with the one you have, ignoring the `Read from` line, so a
new release with the same features stays up to date:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --check --package com.example
```

## From build tooling

`renderFeatures(features, packageName, existing)` is public, for build tooling that fetches the
features itself.
