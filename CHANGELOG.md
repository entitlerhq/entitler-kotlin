# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-09

### Added

- `EntitlerServer`, `EntitlerClient` and `EntitlerIdentityClient`, sharing one `Customer` interface.
- Checks, `isEntitled`, entitlement lists, customer plans, pricing and the usage log.
- Usage in gate and observe modes, holds, `withHold` and batches, each with an idempotency key.
- Customer registration, details, tokens and tracks; self-serve billing and vendor actions.
- Timeouts, retries with backoff, an answer cache honouring `max-age` and `ETag`, stale answers when
  Entitler is unreachable, and refreshing token providers.
- Offline snapshot verification with `verifySnapshot`.
- The `entitler generate` command and `renderFeatures`, in `com.entitlerhq:entitler-generator`.

[Unreleased]: https://github.com/entitlerhq/entitler-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/entitlerhq/entitler-kotlin/releases/tag/v0.1.0
