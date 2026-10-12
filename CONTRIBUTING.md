# Contributing

## Setup

Install JDK 21 (CI also tests 17 and 25). The Gradle wrapper downloads Gradle itself:

```sh
./gradlew build
```

The build produces JVM 11 bytecode with `-Xjdk-release=11`, so any JDK from 17 runs it.

## Commands

| Command | What it runs |
| --- | --- |
| `./gradlew ktlintCheck detekt` | lint and format check (`ktlintFormat` fixes formatting) |
| `./gradlew build` | compile, unit tests, coverage verification, API check, Android API check |
| `./gradlew test koverVerify` | unit tests with the 90% line coverage floor |
| `./gradlew apiCheck` | the public API against `*/api/*.api`; run `apiDump` after an intended change |
| `./gradlew animalsnifferMain` | no API that Android API 21 lacks |
| `./gradlew dokkaGenerate` | the API reference, failing on undocumented public API |
| `./gradlew :examples:build` | the examples, and the check that every snippet in the docs is copied from one |
| `./gradlew publishToMavenLocal` | the artefacts Maven Central would receive |
| `./gradlew :entitler:integrationTest` | the live API tests (below) |

## Integration tests

The live tests run against `https://api.entitler.dev` with the key in `ENTITLER_TEST_KEY`, a test
environment key with the server preset plus `tracks:assign`, and the publishable key of the same
environment in `ENTITLER_TEST_PUBLISHABLE_KEY` for the signed-out pricing test. They are skipped when
`ENTITLER_TEST_KEY` is unset. They
create customers named `sdk-kotlin-<random>` and erase them at the end, and never write the
catalogue, tracks or keys.

```sh
ENTITLER_TEST_KEY=... ENTITLER_TEST_PUBLISHABLE_KEY=... ./gradlew :entitler:integrationTest
```

## Releasing

1. Move the `## [Unreleased]` entries in `CHANGELOG.md` to a new version section.
2. Set `VERSION_NAME` in `gradle.properties` to the new version.
3. Merge to `main`, then push the tag `v<version>`. `publish.yml` checks the tag against the version,
   runs every check, publishes to Maven Central and creates the GitHub release.
