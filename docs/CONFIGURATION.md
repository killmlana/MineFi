# Configuration & Commands

Full reference for `plugins/MineFi/config.yml` and every `/wallet` subcommand.

## `config.yml`

```yaml
providers:
  crypto:
    enabled: false
    walletconnect:
      project-id: ""
      relay-url: "wss://relay.walletconnect.com"
    chain:
      rpc-url: ""
      chain-id: 1
    vault:
      server-hot-wallet: ""
      server-hot-wallet-key: ""
      escrow-contract: ""

  stripe:
    enabled: false
    secret-key: ""
    currency: "usd"
    poll-interval-seconds: 10
    amounts: [5, 10, 25, 50]
    success-url: "https://minefi.pages.dev/success"
    cancel-url: "https://minefi.pages.dev/failure"

  razorpay:
    enabled: false
    key-id: ""
    key-secret: ""
    currency: "inr"
    poll-interval-seconds: 10
    amounts: [100, 500, 1000, 5000]
    success-url: "https://minefi.pages.dev/success"

vault:
  enabled: true
  tx-timeout-seconds: 60
  withdrawal-cooldown-seconds: 300
  merkle-root-interval-minutes: 60
```

### `providers.crypto`

| Key | Default | Notes |
|---|---|---|
| `enabled` | `false` | Master switch |
| `walletconnect.project-id` | `""` | Get one from [cloud.walletconnect.com](https://cloud.walletconnect.com). Required. |
| `walletconnect.relay-url` | `wss://relay.walletconnect.com` | Rarely changed |
| `chain.rpc-url` | `""` | JSON-RPC endpoint (Alchemy, Infura, self-hosted, or a local Hardhat node) |
| `chain.chain-id` | `1` | EVM chain ID. `1` = mainnet, `11155111` = Sepolia, `31337` = Hardhat local |
| `vault.escrow-contract` | `""` | Deployed `MineFiVault` address on the configured chain |
| `vault.server-hot-wallet` | `""` | Address that will relay withdrawals and publish Merkle roots |
| `vault.server-hot-wallet-key` | `""` | **Private key** of the hot wallet. Needs enough ETH for gas. **Never commit this.** |

**Gas model:** the server pays gas for withdrawals, then the contract reimburses the server from the withdrawal amount before sending the rest to the player. So the hot wallet needs a small ETH float for gas headroom, but not a custodial-sized balance.

### `providers.stripe`

| Key | Default | Notes |
|---|---|---|
| `enabled` | `false` | Master switch |
| `secret-key` | `""` | `sk_live_...` or `sk_test_...` |
| `currency` | `usd` | Three-letter ISO code; Stripe uses lowercase |
| `poll-interval-seconds` | `10` | How often MineFi polls Stripe for session completion. Drop to 5 for snappier UX, raise to 30 to cut API calls. |
| `amounts` | `[5, 10, 25, 50]` | Preset buttons in the Book GUI deposit menu. In the configured currency. |
| `success-url` | `https://minefi.pages.dev/success` | Where Stripe redirects the player after payment. Host your own — see [REDIRECT_PAGES.md](REDIRECT_PAGES.md). |
| `cancel-url` | `https://minefi.pages.dev/failure` | Where Stripe redirects the player after cancellation. |

### `providers.razorpay`

| Key | Default | Notes |
|---|---|---|
| `enabled` | `false` | Master switch |
| `key-id` | `""` | `rzp_live_...` or `rzp_test_...` |
| `key-secret` | `""` | Razorpay secret. Never commit. |
| `currency` | `inr` | Razorpay is India-focused; `inr` is typical |
| `poll-interval-seconds` | `10` | Matches Stripe semantics |
| `amounts` | `[100, 500, 1000, 5000]` | Preset amounts in paise-free units (e.g. 100 = ₹100) |
| `success-url` | `https://minefi.pages.dev/success` | Where Razorpay redirects after payment — see [REDIRECT_PAGES.md](REDIRECT_PAGES.md). |

### `vault`

| Key | Default | Notes |
|---|---|---|
| `enabled` | `true` | If false, MineFi does NOT register as a Vault economy. Useful if you want MineFi payments without overriding your existing economy plugin. |
| `tx-timeout-seconds` | `60` | How long to wait for wallet signature responses |
| `withdrawal-cooldown-seconds` | `300` | Per-player cooldown between crypto withdrawals |
| `merkle-root-interval-minutes` | `60` | How often to publish the balance Merkle root on-chain. Lower = fresher proofs, more gas. |

## Commands

All commands are subcommands of `/wallet`. Internal sub-commands (prefixed with `_`) are triggered by Book GUI buttons — players rarely type them directly, but they're listed here for reference.

### Player-facing

| Command | Description |
|---|---|
| `/wallet` | Open the main book menu |
| `/wallet connect` | Generate a QR code map to pair a crypto wallet |
| `/wallet disconnect` | Unpair the wallet |
| `/wallet info` | Show address, chain, spendable balance, deposit breakdown |
| `/wallet approve <amount>` | Deposit `<amount>` ETH into the vault contract (requires wallet connected) |
| `/wallet withdraw` | Open the withdrawal menu |
| `/wallet withdraw <amount>` | Withdraw `<amount>` USD to the connected wallet's chain |
| `/wallet history` | List recent transactions |
| `/wallet verify` | Verify your off-chain balance against the last published Merkle root |
| `/wallet send <address> <amount>` | Send `<amount>` ETH directly from the linked wallet (bypasses the vault) |
| `/wallet sign <message>` | Ask the wallet to sign `<message>` (returns in chat) |

### Internal (Book GUI routing)

| Command | Triggered by |
|---|---|
| `/wallet _connect` | "Connect Wallet" button |
| `/wallet _info` | "Balance & Info" button |
| `/wallet _history` | "History" button |
| `/wallet _verify` | "Verify Balance" button |
| `/wallet _deposit_menu` | "Add Funds" button |
| `/wallet _withdraw_menu` | "Withdraw" button |
| `/wallet _deposit <provider> <amount>` | Amount preset buttons |
| `/wallet _withdraw <chain> <amount>` | Chain-grouped withdraw buttons |

## Permissions

MineFi currently has no permission nodes — any player can use `/wallet`. If you want to lock it down, wrap it with a permissions plugin (LuckPerms, etc.) or block the command in your chat gate until a player reaches some threshold.

## Data files

| File | Contents |
|---|---|
| `plugins/MineFi/config.yml` | Everything above |
| `plugins/MineFi/minefi.db` | SQLite: balances, deposits, sessions, transactions, pending payments |

Back up `minefi.db` before upgrades.

## Common gotchas

- **"Unknown provider type"** on startup → check the provider name in `config.yml` matches `crypto` / `stripe` / `razorpay` exactly.
- **"Insufficient balance"** when players hit Vault shops → confirm `vault.enabled: true` and that `/wallet info` shows a non-zero spendable balance.
- **"Failed to fetch exchange rate"** on non-Stripe deposits → CoinGecko rate-limited you. It backs off and retries; if it keeps happening, set up a proxy or accept the lower refresh rate.
- **Razorpay "Authentication failed"** → double-check `key-id` and `key-secret`. Live and test keys are separate — make sure both come from the same mode.
- **Crypto withdrawals hanging** → the server hot wallet likely ran out of gas. Top it up.
- **Book GUI is empty** → no providers are enabled. Flip at least one to `enabled: true`.
