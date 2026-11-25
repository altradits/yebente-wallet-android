# Rebalance With Mainnet

What happens when the phone reconnects: deposits anchor the balance, queued
offline payments settle over Lightning, and proofs flow back as receipts.

## Balance model

```
spendable = anchor + trustedInbound - outboundPending, floored at zero
pendingInbound = inboundAccepted - trustedInbound, floored at zero
```

- anchor: sats verified on the user's own Lightning node through paid deposit
  invoices. The only number that represents settled mainnet funds.
- inboundAccepted: offline promises accepted from other phones. Pending until
  a receipt confirms settlement, unless the sender is on the trusted list.
- trustedInbound: the accepted inbound portion sent by trusted senders. The
  only inbound slice that counts as spendable before settlement.
- outboundPending: offline promises this phone created and has not settled yet.

Failed, expired, or settled outbound promises leave the pending sum, so the
sats return to spendable automatically. Expired inbound promises leave the
accepted sum. No number is ever typed in by hand or mocked.

## Node configuration (required for settle)

Settlement pays from the user's own Lightning node through the Yebente gateway.
Settings holds the node type (Phoenixd, LNbits, LND), node URL and node key.
The app passes these as `customNodeType`, `customNodeUrl` and `customNodeKey`
on every gateway call, and the key lives in encrypted storage.

Guard rule: without a configured node the worker does nothing. The gateway
falls back to operator environment nodes when credentials are absent, so the
app refuses to create deposits or pay invoices until the user configures their
own node. No payment can ever touch operator funds by accident.

## Deposit anchoring

1. Deposit screen calls `POST /api/lightning/create-invoice` with `satsAmount`,
   `memo` and the node credentials. The invoice belongs to the user's node.
2. The user pays it from any Lightning wallet.
3. The app polls `GET /api/lightning/invoice-status/:paymentHash` with the
   same credentials until `settled` or `paid` is true.
4. The credited sats add to the anchor. Polling also runs in the rebalance
   worker, so deposits confirm even if the screen closed.

## Settlement engine

On reconnect the worker processes queued outbound promises oldest first:

1. Resolve an invoice: use a stored recipient invoice if one was scanned, else
   resolve the recipient Lightning address from the handshake over LNURL-pay
   (works while the recipient is offline). No invoice means skip, not fail.
2. Mark the promise SETTLING and call `POST /api/lightning/disburse` with the
   invoice, amount and node credentials.
3. On success store the payment hash and preimage as the receipt, mark SETTLED
   and subtract sats plus routing fee from the anchor.
4. On transient failure (network error, HTTP 5xx) requeue, stop the run and
   ask WorkManager to retry later. Order is preserved.
5. On permanent failure (no route, bad invoice, refused) mark FAILED and
   continue with the next promise. The sats return to spendable.

Manual settle from History follows the same path with a scanned invoice. It is
the fallback when the recipient has no Lightning address.

## Worker scheduling

- Periodic work every 15 minutes with a network constraint.
- One-shot work fired by the connectivity monitor on every offline to online
  transition.
- `pruneExpired` runs after each pass and ages out dead promises.

## Receipt sync and confirmation

Inbound promises stay ACCEPTED until a receipt proves settlement. When the two
phones meet again, the sender shares YBR1 receipts (History export path plus
the receive scanner). Importing a receipt marks the matching inbound promise
RECEIPT_CONFIRMED. A preimage is proof the Lightning payment completed; a
payment hash alone is a reference to check against the recipient node.

## Gateway contract used by the app

Base URL from Settings, default `https://yebente.onrender.com`.

- `GET /api/health`: reachability check.
- `POST /api/lightning/create-invoice`: body `satsAmount`, `memo`,
  `customNodeType`, `customNodeUrl`, `customNodeKey`. Returns `invoice` and
  `paymentHash` on success.
- `GET /api/lightning/invoice-status/:paymentHash`: same credentials as query
  params. Returns `settled`, `paid`, `satsAmount`, `preimage`.
- `POST /api/lightning/disburse`: body `invoice`, `satsAmount`, `destination`,
  plus node credentials. Returns `paymentHash`, `preimage`, `routingFeeSat`.

On-chain settlement is intentionally out of v0.1: the gateway on-chain
endpoint currently spends from operator environment wallets with no
per-user credential passthrough. Promises already carry the recipient on-chain
address so the rail can switch on once the server supports it.
