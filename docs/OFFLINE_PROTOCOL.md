# Offline Promise Protocol (YB1)

How YeBente Wallet moves sats between phones with no internet, and what the
receiver actually trusts when it accepts a payment offline.

## Roles

- Sender: creates a signed offline promise and transmits it over QR, NFC or BLE.
- Receiver: verifies the promise and credits it as spendable immediately, so
  spending continues with no internet.
- Settler: the sender's phone, once back online, pays real sats over Lightning
  and keeps the payment proof as a receipt.

## Wire format

Promises are JSON, base64url encoded (no padding), framed for transport:

```
YB1:<total>:<index>:<payload-chunk>
```

- `total`: number of frames, 1 to 64.
- `index`: 1-based frame position. Frames decode in any order.
- Joining all chunks yields the base64url of the promise JSON.

Typical promises fit in one frame. The QR scanner collects multi-frame codes
automatically; NFC and BLE move the frames joined with newlines.

### OfflinePromise fields

| Field | Meaning |
| :--- | :--- |
| v | Protocol version, always 1 |
| id | 16 hex chars, random per promise |
| sender | Sender wallet key, compressed secp256k1 public key hex |
| recipient | Receiver wallet key, same format |
| sats | Amount in sats, positive |
| seq | Sender sequence number, starts at 1, increments by 1 |
| createdAt | Sender clock, epoch millis |
| expiresAt | Hard expiry, epoch millis |
| prevHash | SHA-256 hex of the previous promise canonical form, or GENESIS |
| settleAddress | Receiver on-chain address from the handshake, for records |
| memo | Optional note, max 120 chars |
| sig | 128 hex chars, ECDSA r and s over the canonical hash |

### Canonical form and signature

The signed bytes are the UTF-8 string of pipe-joined fields, excluding `sig`:

```
YB1|v|id|sender|recipient|sats|seq|createdAt|expiresAt|prevHash|settleAddress|memo
```

Keys and hashes lowercase. The signer hashes this once with SHA-256 and signs
with the secp256k1 identity key. Any edit to any field breaks the signature.

### Receive handshake (YBH1)

The receiver shares one code before payment:

```
YBH1:<base64url of ReceiveHandshake JSON>
```

Fields: `recipient` key, fresh BIP-84 on-chain `address`, optional
`lightning` address for auto-settlement, supported `transports`, optional
`maxSats` cap. The sender embeds the address in the promise and stores the
Lightning address for the rebalance worker.

### Settlement receipt (YBR1)

After the sender pays over Lightning, the phone keeps the payment hash and
preimage. Receipts sync phone to phone when the two meet again:

```
YBR1:<base64url of SettlementReceipt JSON>
```

A preimage is cryptographic proof that the Lightning payment completed.

## Acceptance rules

The receiver accepts a promise only if every rule holds:

1. Version is 1, keys are valid compressed secp256k1 points, sender differs
   from recipient, amount is positive and within the promise cap.
2. Timestamp is not in the future beyond 5 minutes of clock skew.
3. The promise is not expired and its lifetime is within 7 days.
4. The signature verifies against the sender key.
5. Sequence is exactly last accepted seq plus 1 from that sender (1 for a new
   sender). Gaps are rejected: the sender must deliver the missing promise.
6. `prevHash` chains to the last accepted promise hash (GENESIS for seq 1).
7. The sender total and the wallet inbound total stay within caps.

Default caps: 50,000 sats per promise, 200,000 per sender, 500,000 inbound
total. Duplicates are rejected by promise id.

## Trust model (read this)

An offline promise is a signed IOU, not a settled payment. The receiver trusts
that the sender will pay on reconnect. What the protocol does and does not
guarantee:

- Guaranteed: authenticity (only the sender key could sign), integrity (any
  edit breaks the signature), ordering (seq plus hash chain exposes hidden or
  reordered promises), replay safety (ids are unique per wallet).
- Bounded: total exposure per sender and per wallet is capped, and every
  acceptance is aged out by expiry.
- NOT guaranteed: the sender cannot double-spend the same sats to two offline
  receivers who never compare notes. The hash chain makes the fork provable
  after the fact (two promises with the same seq), but prevention needs either
  online settlement or receivers who sync with each other.
- Fork proofs: when this wallet sees two valid promises from one sender
  sharing a seq with different hashes, it stores a fork proof, blocks the
  sender from all future inbound, and drops them from the trusted list.
  Blocked senders stay blocked until the wallet is wiped.

Consequences for the design:

- Caps default low. Raise them only for senders you trust.
- Inbound stays pending until a receipt confirms settlement, unless the
  sender is on the trusted list in Settings. Only anchor plus
  trusted-sender inbound counts as spendable, so unconfirmed money can
  never fund new offline sends.
- Receivers who meet often should scan each other's receipts: confirmed
  receipts prove past settlement.
- High value payments should wait for online settlement before the receiver
  treats them as final. The app marks inbound funds as pending until a receipt
  confirms them.

## Transports

- QR: ZXing encoding, CameraX plus ML Kit scanning. Works on any two phones
  with a camera. Multi-frame codes scan part by part.
- NFC: sender emulates a card (HCE, AID F059423101), receiver reads with
  reader mode. Chunked reads, max 8 KB payload.
- BLE: sender advertises a GATT service and pushes notify chunks (180 byte
  payload with total and index headers). Receiver scans, connects, requests
  247 MTU and reassembles. Max 8 KB payload.

All three move identical bytes, so a payment started on one transport can
finish on another by rescanning.
