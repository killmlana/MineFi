# Architecture

A tour of how MineFi is put together, aimed at anyone modifying the plugin or debugging something weird in production.

## The pieces

**`MineFiPlugin`** is the entry point. It reads the config, enables each provider whose `enabled` flag is true, registers the Vault economy, and schedules the periodic Merkle root publisher.

**`Database`** is a single SQLite file under `plugins/MineFi/minefi.db`. It holds six tables:

- `players` — UUID to wallet address
- `sessions` — persistent WalletConnect sessions (symmetric key, topic, expiry)
- `balances` — per-player spendable balance, always USD-denominated
- `deposits` — a FIFO ledger where every deposit is tagged with its origin (provider + chain) and how much of it is still unspent
- `transactions` — an audit log for the history book
- `pending_payments` — Stripe/Razorpay sessions that are still waiting on the upstream to confirm

Balance writes go through `tryDeduct`, `adjustApprovedBalance`, and `fifoSpend`. They're all `@Synchronized` and transactional, which keeps two concurrent shop purchases from double-spending the same balance.

**`MineFiEconomy`** is the Vault bridge. `withdrawPlayer` calls `tryDeduct` + `fifoSpend`. `depositPlayer` calls `adjustApprovedBalance`. `getBalance` reads the USD balance. Shop plugins never know MineFi exists — they just see a number they can spend against.

**`PaymentProvider`** is the interface every payment method implements. Three implementations ship in the repo (`CryptoProvider`, `StripeProvider`, `RazorpayProvider`). Adding a new one is covered in [PROVIDERS.md](PROVIDERS.md).

**The relay/chain stack** under `relay/` and `chain/` is what makes the crypto provider work. `RelayClient` speaks raw WalletConnect v2 over WebSocket with Ed25519 JWT auth. `SessionManager` owns pairings, session keys (HKDF), tag-routed envelopes, and the two signing flows (EIP-712 and raw transactions). `ChainService` is a web3j wrapper that reads balances, sends transactions, and calls the vault contract.

**`MineFiVault.sol`** is the escrow contract. It has `deposit`, `withdraw` (server-relayed, player-signed via EIP-712), `updateBalanceRoot`, and `emergencyWithdraw`. It's in `test-server/hardhat/contracts/`.

**`BookGui`** is the written-book UI. It builds a `WRITTEN_BOOK` item with clickable `TextComponent` buttons and calls `player.openBook()`. Every button runs an internal `/wallet _<action>` sub-command.

**`PriceService`** is CoinGecko-backed with background refresh. Used both to credit deposits at the current USD rate and to convert USD back to ETH at withdraw time.

**`MerkleTree`** builds a Keccak-256 Merkle tree of `(address, wei-balance)` leaves. Leaves are sorted by address and double-hashed; pairs are sorted before hashing — this matches OpenZeppelin's `MerkleProof` library so on-chain verification works out of the box.

## Key flows

### Wallet connection

A player runs `/wallet connect`. `CryptoProvider.connect` calls `SessionManager.initiatePairing`, which subscribes to a pairing topic on the relay and returns a `wc://` URI. The plugin renders that URI as a QR code on a filled map and places it in the player's hand.

The player scans the QR with a wallet app. The wallet sends a session proposal through the relay, which `SessionManager` handles — it derives a session key via HKDF, approves, and writes the session to the DB. Once the session is established, the map item is cleared and the player sees a "Wallet connected" message.

### Deposit via crypto

Player picks an amount from the book. The internal command `/wallet _deposit crypto 0.1` routes to `WalletCommand.handleDeposit`, which calls `CryptoProvider.startDeposit`. That sends an EIP-155 transaction request through the WalletConnect session asking the player to deposit 0.1 ETH into the vault contract.

The player approves in their wallet app. `ChainService.getTransactionReceipt` confirms the transaction succeeded on-chain, and `startDeposit` resolves its `CompletableFuture` with a `DepositResult`. Back in `handleDeposit`, the amount is converted to USD via `PriceService`, the balance is updated, and a deposit row is written to the FIFO ledger tagged with `crypto` and the chain ID.

### Deposit via Stripe

Player picks an amount. `StripeProvider.startDeposit` POSTs to `/v1/checkout/sessions` with a success URL and the player's UUID in metadata. It saves a `pending_payments` row, gives the player a clickable link and a QR map, and returns a `CompletableFuture` that is not yet completed.

A background task polls each pending session every 10–30 seconds. When Stripe reports `payment_status: paid`, the future completes with success, `WalletCommand.handleDeposit`'s `thenAccept` runs, and the balance is credited through the same code path as crypto.

If the server restarts between session creation and completion, `restorePendingSessions` rehydrates the poll list from `pending_payments` at startup. The original `CompletableFuture` is long gone by then, so `creditRestoredPayment` is called directly — it writes the balance, saves the deposit, and notifies the player if they're online.

### Vault spend

A player buys a diamond sword in ShopGUI+. ShopGUI+ calls `Economy.withdrawPlayer(uuid, 10.00)` through Vault. That hits `MineFiEconomy.withdrawPlayer`, which calls `Database.tryDeduct` (atomic, synchronized) and then `Database.fifoSpend` to burn down the oldest unspent deposit.

There are no network calls and no provider involvement. Vault spends are pure ledger operations.

### Withdrawal via crypto

Player hits "Withdraw $50" in the book. `WalletCommand.handleWithdraw` runs: it converts USD to ETH via `PriceService`, optimistically deducts the USD balance via `tryDeduct`, and then calls `CryptoProvider.startWithdraw`.

`startWithdraw` fetches the player's current nonce from the contract, builds an EIP-712 `Withdraw` struct, and sends it through the WalletConnect session as a typed-data signing request. The player signs in their wallet. The signature comes back to the server, which then calls `vaultWithdraw` on-chain using the server hot wallet. The contract verifies the signature against the player's address, deducts gas from the withdrawal amount, reimburses the server, and pays the rest to the player.

If anything fails after the optimistic `tryDeduct` — signature rejected, contract revert, RPC timeout — `handleWithdraw` calls `adjustApprovedBalance` to restore the balance.

### Merkle root publishing

Every `vault.merkle-root-interval-minutes` (default 60), `MineFiPlugin.publishMerkleRoot` reads every non-zero balance from `db.getAllBalances`, looks up each player's wallet address, converts the USD balance to wei-equivalent, builds a `MerkleTree`, and calls `chainService.vaultUpdateRoot` to publish the root to the contract.

The tree is kept in memory as `plugin.currentMerkleTree` so `/wallet verify` can generate a proof for the calling player without rebuilding.

The on-chain root is the players' escape hatch. If the server goes offline permanently and a player has a copy of their proof from the last published root, they can call `MineFiVault.emergencyWithdraw(claimedBalance, proof)` directly after the 1-hour dispute window. The contract verifies the proof against the last root and pays out whatever's still in the contract for that player.

## Threading model

Commands, event handlers, GUI rendering, and the Vault callbacks all run on the main thread. Anything that does I/O — HTTP to Stripe/Razorpay, WalletConnect relay sends, web3j RPC calls, CoinGecko price fetches, Merkle root publishing — is dispatched to Bukkit's async scheduler. When async code needs to touch the world (send messages, manipulate inventory, open a book), it hops back to the main thread with `runTask`.

Database operations are synchronized rather than pooled. For plugin-scale write volume that's fine and it dodges a lot of complexity around connection pooling in SQLite.

## Shaded dependencies

`build.gradle.kts` relocates these into `com.minefi.shaded.*` so they don't collide with other plugins on the classpath:

- Kotlin stdlib (plenty of plugins ship their own)
- okhttp3, okio
- web3j
- zxing (QR generation)
- bouncycastle (Keccak-256, EIP-712)

## Out of scope right now

- ERC-20 token balances beyond ETH
- More than one chain active at the same time
- L2 / alt-L1 withdrawals (one vault contract, one chain, for now)
- Webhooks — Stripe and Razorpay are polled instead, which keeps the plugin from needing a public HTTP endpoint
- Any kind of web dashboard — everything is in-game
