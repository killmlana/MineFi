# MineFi

Spigot plugin connecting crypto wallets to Minecraft servers via WalletConnect v2.

## Features

- **Wallet Connection** — Scan QR code on in-game map to connect MetaMask, Rainbow, etc.
- **On-Chain Balances** — View ETH/token balances in-game
- **Transaction Signing** — Send transactions with wallet approval on your phone
- **Vault Integration** — Works with any Vault-compatible economy plugin
- **Persistent Sessions** — Wallet stays connected across server restarts

## Setup

1. Drop `MineFi-0.1.0.jar` into your server's `plugins/` folder
2. Get a WalletConnect Project ID from https://cloud.walletconnect.com
3. Edit `plugins/MineFi/config.yml`:
   - Set `walletconnect.project-id`
   - Set `chain.rpc-url` (Alchemy/Infura endpoint)
4. Restart server

## Commands

| Command | Description |
|---------|-------------|
| `/wallet connect` | Generate QR code to link wallet |
| `/wallet disconnect` | Unlink wallet |
| `/wallet info` | Show address, chain, balance |
| `/wallet sign <msg>` | Request message signature |

## Building

```bash
./gradlew shadowJar
```

Output: `build/libs/MineFi-0.1.0.jar`

## Requirements

- Spigot/Paper 1.20+
- Java 17+
- Optional: Vault for economy integration
