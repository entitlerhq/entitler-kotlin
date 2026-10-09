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

## Before pushing

Run, and fix everything they report:

```sh
./gradlew ktlintCheck detekt build dokkaGenerate publishToMavenLocal :examples:build
ENTITLER_TEST_KEY=... ./gradlew :entitler:integrationTest
```

The repository's only required check is `All checks passed`.

## Authorship and pull requests

- Every commit is authored and committed by Romain Francez
  `<1330814+romainfrancez@users.noreply.github.com>`, with no attribution trailers, generated-by lines,
  session links or model names. The repository never names the tools that wrote it, in files, commit
  messages or branch names. Check `git log --format='%an <%ae>%n%b'` before every push.
- Never write to GitHub except by `git push`: open no pull requests and post no comments, reviews or
  issues. The owner opens pull requests.
