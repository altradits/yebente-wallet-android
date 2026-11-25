# Contributing to YeBente Wallet

Thanks for helping build offline-first Bitcoin payments. This repo keeps
every change small, tested, and reviewable.

## One-command build

Requirements: JDK 17 and the Android SDK (`sdk.dir` in `local.properties`).

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

CI runs the unit suite on every pull request.

## Pull request rules

- One branch per fix, one fix per pull request, merged with `--no-ff`.
- Every behavior change ships with a unit test or a note explaining why only
  on-device testing can cover it (NFC, BLE, camera).
- Room changes ship with a migration (`WalletDatabase`) and never lose
  settled data. Pre-release chain resets must be called out in the PR body.
- No mocked balances: every displayed digit comes from verified deposits,
  verified promises, or live gateway responses.
- UI follows Stan Style: action-only buttons, textless back nav, borderless
  readouts, monospace tabular figures, zero emojis.

## Where to start

- `docs/OFFLINE_PROTOCOL.md` is the wire format and trust model. Read it first.
- `docs/REBALANCE.md` explains deposits, settlement, and receipts.
- Good first areas: scanner edge cases, Settings screens, unit tests for
  `domain/protocol`, and Swahili translations.
