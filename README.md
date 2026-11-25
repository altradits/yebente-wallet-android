# YeBente Wallet (Android)

Offline-first Bitcoin wallet for the African household. Send and receive sats
over QR, NFC or Bluetooth with no internet, keep spending from local balance,
and rebalance with mainnet over Lightning when reconnected.

This repo is the Android companion to [yebente](../yebente). The web backend
acts as a Lightning gateway: this app never holds server keys and passes the
user's own node credentials through for every settlement call.

## Status

v0.1 scaffold: full offline promise protocol, QR/NFC/BLE transports, deposit
anchoring, Lightning settlement engine, rebalance worker, Stan Style UI.
Spendable is anchor plus trusted-sender inbound; fork proofs block
double-spending senders.

## Build

Requirements: Android Studio Koala or newer, JDK 17, Android SDK 34.

1. Copy `local.properties.example` to `local.properties` and set `sdk.dir`.
2. Open this folder in Android Studio and let it sync Gradle.
3. Run `app` on a device (NFC and BLE need hardware; emulator covers QR).

Command line without Android Studio:

```sh
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## How it works

- No internet: phones exchange signed offline promises. The receiver verifies
  the signature, sequence chain and caps, then holds the amount as pending;
  only settled anchor and trusted-sender inbound count as spendable.
- Reconnect: a WorkManager job fires on network restore. It polls verified
  deposits to anchor the balance, then settles each queued outbound promise
  over Lightning through the user's own node via the Yebente gateway.
- Proof: Lightning preimages and payment hashes sync back as receipts when
  phones next meet, so receivers can confirm real settlement.

Read [docs/OFFLINE_PROTOCOL.md](docs/OFFLINE_PROTOCOL.md) for the wire format
and trust model, and [docs/REBALANCE.md](docs/REBALANCE.md) for settlement.

## Design rules

- Stan Style: action-only buttons, textless back nav, borderless readouts,
  monospace tabular figures, zero emojis, zero em-dashes.
- Zero simulation: every displayed digit comes from verified deposits,
  verified promises or live gateway responses. Nothing is mocked.
