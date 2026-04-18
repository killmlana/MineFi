# Features

What you actually get when you install MineFi.

## Multi-provider payments

Three providers ship in the box. Enable any combination in `config.yml`.

| Provider | Accepts | Withdrawals | Notes |
|---|---|---|---|
| `crypto` | ETH via WalletConnect v2 | Yes (back to wallet) | Needs an RPC URL and a vault contract deployment |
| `stripe` | Cards (global) | No | Stripe Checkout, polled for completion |
| `razorpay` | UPI, cards, netbanking (India) | No | Payment Links; UPI QR rendered on an in-game map |

Providers are independent. You can run crypto-only for a Web3 server, Stripe-only for a vanilla donation store, or mix all three. The Book GUI only shows providers that are enabled.

## Vault economy bridge

MineFi registers as a Vault `Economy` provider. Every plugin that reads balances through Vault picks up MineFi's balance for free:

- EssentialsX — `/balance`, `/pay`, sign shops
- ShopGUI+ — GUI shops
- ChestShop, QuickShop-Hikari — chest shops
- CMI — economy commands
- Jobs Reborn, mcMMO reward hooks, and anything else that calls `Economy#depositPlayer`

The contract is simple: players deposit once (crypto / Stripe / Razorpay), the balance is dollar-denominated in MineFi, and any Vault consumer can deduct from it atomically.

## Book GUI

`/wallet` opens a written-book interface with clickable buttons.

- Main menu: connect/disconnect, add funds, withdraw, balance, history, verify
- Deposit menu: one page per enabled provider, preset amounts from config
- Withdraw menu: grouped by origin chain, shows how much is withdrawable where
- Info page: wallet address, chain ID, spendable balance, deposit breakdown
- History: last 50 transactions, paginated three per page
- Verify: checks the player's off-chain balance against the last on-chain Merkle root

All menus re-render dynamically based on which providers are enabled and whether the player has a wallet connected.

## WalletConnect v2

Players run `/wallet connect` and get a map item with a QR code on it. Scanning from any WC v2 wallet app pairs the session. The plugin speaks the raw WalletConnect v2 protocol directly (Ed25519 JWT auth, HKDF session keys, tag routing), so there's no companion web page and the connection survives server restarts.

Signing flows:

- Transactions — deposit/withdraw calls to the vault contract, plus arbitrary `/wallet send`
- Typed data (EIP-712) — for withdrawal authorization
- Personal sign — for `/wallet sign <message>`

## On-chain vault with emergency withdrawal

Crypto deposits go into a smart contract (`MineFiVault.sol`) that the server operates. The server key can authorize withdrawals, but the player's signature is required for every withdrawal (EIP-712) — the server can't move funds unilaterally.

Every hour (configurable) the server publishes a Merkle root of all off-chain balances to the contract. If the server goes offline permanently, any player with their balance proof can call `emergencyWithdraw` directly on the contract after the dispute window (1 hour) and recover their on-chain deposit.

Players can check that their balance is included in the latest root at any time via `/wallet verify`.

## Live price conversion

ETH is converted to USD via CoinGecko so crypto deposits credit in dollars. INR is converted to USD for Razorpay. Prices are cached and refreshed in the background; the Vault economy is always dollar-denominated.

Withdrawal conversion runs the other way. If a player has $50 credited from Razorpay and withdraws via crypto, MineFi converts USD to ETH at the current rate.

## FIFO deposit ledger

Every deposit is recorded with its origin (`stripe`, `razorpay`, `crypto:eip155:1`, and so on) and amount remaining. Spends — Vault deductions and withdrawals — burn down the oldest deposits first.

Two practical consequences:

- Withdrawals are source-restricted. You can't withdraw a Stripe deposit back to ETH — only crypto-origin funds are withdrawable.
- The Withdraw menu shows per-chain availability ("$50 on eip155:1, $20 on eip155:137") rather than a single blended number.

## Persistence

Everything that can outlive a restart does:

- WalletConnect sessions (topic, symmetric key, expiry) in SQLite
- Pending Stripe and Razorpay sessions — polled and credited even if confirmation lands after a restart
- Deposit ledger, balances, transactions in SQLite
- The Merkle tree is rebuilt from balances on each publish

## Security model

- The server hot wallet only pays gas and relays withdrawals — it's not a custody wallet.
- EIP-712 signatures mean the server cannot withdraw without the player's wallet.
- Nonces (per-player, tracked in-contract) prevent signature replay.
- The Merkle root anchors the off-chain ledger, giving players a trustless escape hatch.
- A per-player withdrawal cooldown (configurable, default 5 minutes) rate-limits withdrawals.
- `tryDeduct` is atomic and synchronized, which prevents double-spend across concurrent shop purchases.

## Developer ergonomics

- One interface (`PaymentProvider`) for adding new payment methods — see [PROVIDERS.md](PROVIDERS.md).
- Shaded fat JAR: drop and run, no dependency management.
- SQLite: zero external database.
- All networking is async; nothing blocks the main thread on HTTP.
