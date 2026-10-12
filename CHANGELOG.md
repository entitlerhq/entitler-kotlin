# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-09

### Added

- `EntitlerServer`, `EntitlerClient`, `EntitlerIdentityClient` and `EntitlerPublishableClient`, the
  first three sharing one `Customer` interface; each closes with `close()`, and an app holds one client
  per signed-in customer.
- Checks, `isEntitled`, entitlement lists, customer plans and pricing, each with `revalidate`; the
  usage log by page, or as a `Flow` through `usageLog`; server reads `asOf` an instant, per call.
- Usage in gate and observe modes with required idempotency keys, the `startHold` handle, `withHold`,
  holds by hand, and batches whose request keys derive from their events.
- Billing by intent: `subscribe` answering a `SubscribeStep` (done, pay, confirming or manage), with an
  optional return URL; `cancel`, `undoPendingChange`, `billingPortal` and `syncBilling`.
- Company decisions: `setPlan` with `billing` and `until`, store SKUs, `setAddOn`, grants,
  `adjustMeter` and `cancelUsage`, each with `actor` and `reason`.
- Customer registration, details, `erase()`, tokens and tracks by name.
- Timeouts, retries with backoff, an answer cache honouring `max-age` and `ETag`, stale answers when
  Entitler is unreachable, `isUnreachable`, and refreshing token providers.
- Offline snapshot verification with `verifySnapshot` and `SnapshotExpectation`.
- The `entitler` command-line tool with `generate` and `snapshot-keys`, and `renderFeatures`, in
  `com.entitlerhq:entitler-generator`.
- `com.entitlerhq:entitler-testing`, with `fakeCustomer` and answer builders for apps' own tests.
- The specification's conformance suite, run with the unit tests.

[Unreleased]: https://github.com/entitlerhq/entitler-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/entitlerhq/entitler-kotlin/releases/tag/v0.1.0
