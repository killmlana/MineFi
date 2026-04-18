# MineFi

![Minecraft](https://img.shields.io/badge/Minecraft-1.20%2B-62B47A?style=flat-square&logo=minecraft&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Vault](https://img.shields.io/badge/Vault-API-4B8BBE?style=flat-square)
![Build](https://img.shields.io/badge/build-gradle-02303A?style=flat-square&logo=gradle&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

Payment gateway plugin for Minecraft. Accept crypto, cards, and UPI — all through one plugin that plugs into Vault so existing shop plugins just work.

## What it does

Players run `/wallet` to open an in-game book. From there they can connect a crypto wallet (WalletConnect v2), deposit via Stripe or Razorpay, spend their balance through any Vault-compatible plugin (ShopGUI+, EssentialsX, ChestShop, and so on), and withdraw crypto back to their wallet.

Deposited balances are tracked in USD. The crypto side uses an on-chain escrow with EIP-712 withdrawals and periodic Merkle root publishing, so if the server disappears players can still recover their on-chain funds.

## Features

- WalletConnect v2 — scan a QR from an in-game map to pair any wallet
- Stripe + Razorpay out of the box, easy to add more
- Registers as a Vault `Economy` provider — every shop plugin keeps working
- Book GUI for everything (no command memorization)
- EIP-712 withdrawals signed by the player, relayed on-chain by the server
- Merkle balance root published hourly; `/wallet verify` and `emergencyWithdraw` as fallback
- Live ETH/INR ↔ USD conversion via CoinGecko
- Persistent sessions, pending payments, and FIFO deposit ledger across restarts

## Install

```bash
./gradlew shadowJar
cp build/libs/MineFi-0.1.0.jar /path/to/server/plugins/
```

Restart the server once so the default config is written, then edit `plugins/MineFi/config.yml` and restart again.

## Docs

- [docs/FEATURES.md](docs/FEATURES.md) — feature rundown for server owners
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) — full `config.yml` reference and commands
- [docs/REDIRECT_PAGES.md](docs/REDIRECT_PAGES.md) — hosting your own success/cancel pages for Stripe and Razorpay
- [docs/PROVIDERS.md](docs/PROVIDERS.md) — how to add a new payment provider
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how everything fits together

## Commands

| Command | What it does |
|---------|--------------|
| `/wallet` | Open the main book menu |
| `/wallet connect` | Generate a QR to link a crypto wallet |
| `/wallet disconnect` | Unlink the wallet |
| `/wallet info` | Show address, chain, and spendable balance |
| `/wallet approve <amount>` | Deposit ETH into the vault contract |
| `/wallet withdraw <chain> <amount>` | Withdraw deposited crypto |
| `/wallet history` | Recent transactions |
| `/wallet verify` | Check your off-chain balance against the last Merkle root |
| `/wallet send <address> <amount>` | Send ETH directly from the linked wallet |
| `/wallet sign <message>` | Ask the wallet to sign an arbitrary message |

## Requirements

- Spigot or Paper 1.20+
- Java 17+
- Vault (optional, for economy integration)
- WalletConnect Cloud project ID (for crypto)
- Stripe and/or Razorpay keys (for cards/UPI)

## License

MIT.
