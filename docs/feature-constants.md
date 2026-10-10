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

Each constant's type carries the feature's type: `Feature.OnOff`, `Feature.Config`, `Feature.Metered`
or `Feature.Group` (with its leaf members). Archived features are marked `@Deprecated("Archived in
Entitler.")`.

## Running it

Download `entitler-generator-0.1.0-all.jar` from Maven Central and run it:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --package com.example
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--out <file>` | `src/main/kotlin/EntitlerFeatures.kt` | the file to write |
| `--package <name>` | none | the Kotlin package to declare |
| `--key <key>` | `ENTITLER_KEY` | an API key with `plans:read` |
| `--base-url <url>` | `https://api.entitler.dev` | the API's base URL |
| `--check` | | fail when the file is out of date, without writing it |
| `--allow-empty` | | write the file even when Entitler lists no features |
| `--help` | | show the usage |

Options take `--flag value` or `--flag=value`. It exits with 0 on success or when the file is up to
date, and 1 otherwise. When Entitler lists no features and the file already holds some, it leaves the
file as it is and fails, since an empty list usually means a key from the wrong environment; pass
`--allow-empty` when you mean it.

Generate from the live environment, with a key holding only `plans:read`: a test environment's All
customers track may follow an open change.

Or run it from Gradle, with a `JavaExec` task:

```kts
val entitlerGenerator by configurations.creating

dependencies {
    entitlerGenerator("com.entitlerhq:entitler-generator:0.1.0")
}

tasks.register<JavaExec>("generateFeatures") {
    classpath = entitlerGenerator
    mainClass = "com.entitlerhq.entitler.generator.MainKt"
    args("generate", "--package", "com.example")
}
```

## Stable names

A constant is named by converting its key to camel case (`export_pdf` becomes `exportPdf`). When the
file exists, each feature keeps the name it has there, so adding a feature never renames another.
Reserved words take a trailing underscore (`in_`), and a name already taken takes a number (`exportPdf2`),
decided in key order after the kept names. Features no longer listed disappear.

## In CI

`--check` compares the file it would write with the one you have, ignoring the `Read from` line, so a
new release with the same features stays up to date. Run it nightly and on `main`, not as a required
pull request check, since pull requests from forks have no key:

```sh
java -jar entitler-generator-0.1.0-all.jar generate --check --package com.example
```

## From build tooling

`renderFeatures(features, packageName, existing)` is public, for build tooling that fetches the
features itself.

## Snapshot keys

The same JAR writes the keys an app pins to verify [offline snapshots](offline-snapshots.md). It needs no
key, and writes nothing when Entitler publishes no keys:

```sh
java -jar entitler-generator-0.1.0-all.jar snapshot-keys --out src/main/resources/snapshot-keys.json
```

`--out` defaults to `entitler-snapshot-keys.json`. Run it from a `JavaExec` task like the one above,
with `args("snapshot-keys", "--out", "src/main/resources/snapshot-keys.json")`, before each release
build.
