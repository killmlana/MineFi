<h1 align="center">MineFi</h1>

<p align="center">
  Payment gateway for Minecraft.<br>
  Crypto, cards, UPI — plugs into Vault so your shops just work.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.20%2B-62B47A?style=flat-square&logo=minecraft&logoColor=white" alt="Minecraft">
  <img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Vault-API-4B8BBE?style=flat-square" alt="Vault">
  <img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="MIT">
</p>

<!-- Drop a hero gif here: /wallet opening the book, clicking a provider, QR map appears -->

## Install

```bash
./gradlew shadowJar
cp build/libs/MineFi-0.1.0.jar /path/to/server/plugins/
```

Restart once to write `plugins/MineFi/config.yml`, fill in your keys, restart again.

## Features

- **WalletConnect v2** — scan an in-game QR map to pair any wallet
- **Stripe + Razorpay** — cards, UPI, easy to extend
- **Vault economy** — every shop plugin keeps working, untouched
- **Book GUI** — no commands to memorize
- **EIP-712 withdrawals** — player signs, server relays
- **Merkle root anchoring** — emergency withdraw if the server disappears
- **Live conversion** — ETH / INR ↔ USD via CoinGecko

## Commands

```
/wallet                             open the book
/wallet connect                     QR to link a wallet
/wallet approve <amount>            deposit ETH
/wallet withdraw <chain> <amount>   withdraw crypto
/wallet verify                      check balance against Merkle root
/wallet history                     recent transactions
```

Full list in [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Docs

| | |
|---|---|
| [Features](docs/FEATURES.md) | What it does, for server owners |
| [Configuration](docs/CONFIGURATION.md) | `config.yml` + every command |
| [Redirect pages](docs/REDIRECT_PAGES.md) | Hosting Stripe/Razorpay success pages |
| [Providers](docs/PROVIDERS.md) | Writing a new payment provider |
| [Contract](docs/CONTRACT.md) | `MineFiVault.sol` reference |
| [Architecture](docs/ARCHITECTURE.md) | How the pieces fit |

## Requirements

Spigot/Paper 1.20+ · Java 17 · Vault · WalletConnect project ID · Stripe/Razorpay keys

## License

MIT
