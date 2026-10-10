# Entitler SDK for Kotlin

## Copy

- All prose (README, docs, doc comments, error messages, CLI output, changelog) is Australian
  English, sentence case, no emoji.
- Never write "get" in prose; name the thing instead ("the customer's entitlements", "Quick start").
  No vague "what" clauses. Identifiers follow Kotlin, so a method may be named `get`.

## Comments

- Every public type, function, method, property and constant has a doc comment; Dokka fails on any
  that is missing.
- No other comments. If code needs one, rename, extract or restructure it. Tool directives (lint
  suppressions) are the only exception, each with a reason the tool accepts.

## Conformance suite

- `entitler/src/test/resources/conformance/` holds the specification's conformance suite, copied
  unchanged with its manifest, and the tests check every file's SHA-256 before running a case.
- Never edit a case. A case the SDK cannot pass is a question about the specification.

## Modules

- `entitler`: the library. `entitler-generator` (`generator/`): the `entitler` command-line tool, with
  `generate` and `snapshot-keys`. `entitler-testing` (`testing/`): `fakeCustomer` and the `Answers`
  builders apps use in their tests; it never goes into the library. `examples`: the runnable examples
  and the sources of every documentation snippet.
- Every Kotlin snippet in `README.md` and `docs/` is copied from a file under `examples/src`;
  `SnippetsTest` fails otherwise.

## Before pushing

Run, and fix everything they report:

```sh
./gradlew ktlintCheck detekt build dokkaGenerate publishToMavenLocal :examples:build
ENTITLER_TEST_KEY=... ENTITLER_TEST_PUBLISHABLE_KEY=... ./gradlew :entitler:integrationTest
```

The repository's only required check is `All checks passed`.

## Authorship and pull requests

- Every commit is authored and committed by Romain Francez
  `<1330814+romainfrancez@users.noreply.github.com>`, with no attribution trailers, generated-by lines,
  session links or model names. The repository never names the tools that wrote it, in files, commit
  messages or branch names. Check `git log --format='%an <%ae>%n%b'` before every push.
- Never write to GitHub except by `git push`: open no pull requests and post no comments, reviews or
  issues. The owner opens pull requests.
