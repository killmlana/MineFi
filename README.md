<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/media/logo-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/media/logo.png">
  <img alt="MineFi" src="docs/media/logo.png" width="280">
</picture>

<br><br>

Payment gateway plugin for Minecraft servers.
Crypto, cards, and UPI through one plugin that plugs into Vault.

<br>

<a href="https://minefi.pages.dev">Website</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;<a href="docs/CONFIGURATION.md">Configuration</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;<a href="docs/FEATURES.md">Features</a>&nbsp;&nbsp;&bull;&nbsp;&nbsp;<a href="docs/CONTRACT.md">Contract</a>

<br>

<img src="https://img.shields.io/badge/Minecraft-1.20%2B-62B47A?style=flat-square&logo=minecraft&logoColor=white" alt="Minecraft">
<img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
<img src="https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java">
<img src="https://img.shields.io/badge/Vault-API-4B8BBE?style=flat-square" alt="Vault">
<img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="MIT">

<br><br>

<img src="https://minefi-assets.saikia.me/clips/WalletConnect.gif" alt="WalletConnect pairing" width="600">

</div>

## About

MineFi lets players deposit and spend real money in-game. It connects crypto wallets via WalletConnect v2, accepts card and UPI payments through Stripe and Razorpay, and registers as a [Vault](https://github.com/MilkBowl/VaultAPI) economy provider so existing shop plugins work without changes.

Deposited funds are tracked in USD. The crypto side uses an on-chain escrow contract ([`MineFiVault.sol`](contracts/MineFiVault.sol)) with EIP-712 signed withdrawals and periodic Merkle root publishing. If the server disappears, players can recover their on-chain funds directly from the contract.

## Features

**WalletConnect v2** &mdash; scan an in-game QR map to pair any wallet

<img src="https://minefi-assets.saikia.me/clips/AddFunds.gif" alt="Adding funds" width="600">

**Stripe + Razorpay** &mdash; cards, UPI, easy to extend

<img src="docs/media/Razorpay.gif" alt="Razorpay payment" width="600">

**Crypto withdrawals** &mdash; player signs with their wallet, server relays on-chain

<img src="https://minefi-assets.saikia.me/clips/Withdrawal.gif" alt="Withdrawal flow" width="600">

**Transaction history** &mdash; every deposit and withdrawal tracked

<img src="https://minefi-assets.saikia.me/clips/history.gif" alt="Transaction history" width="600">

**Vault economy** &mdash; every shop plugin keeps working, untouched

**Book GUI** &mdash; no commands to memorize

**Merkle root anchoring** &mdash; emergency withdraw if the server disappears

**Live conversion** &mdash; ETH / INR / USD via CoinGecko

<details>
<summary>Architecture</summary>

```mermaid
graph TB
    Player([Player]) --> BookGUI[Book GUI]
    BookGUI --> Providers

    subgraph Providers
        Crypto[CryptoProvider]
        Stripe[StripeProvider]
        Razorpay[RazorpayProvider]
    end

    Crypto --> WC[WalletConnect v2 Relay]
    Crypto --> Chain[ChainService / web3j]
    Chain --> Contract[MineFiVault.sol]
    Stripe --> StripeAPI[Stripe API]
    Razorpay --> RzpAPI[Razorpay API]

    Providers --> DB[(SQLite)]
    Providers --> Vault[Vault Economy]
    Vault --> Shops[Shop Plugins]

    MerklePublisher[Merkle Publisher] --> DB
    MerklePublisher --> Contract
    PriceService[PriceService] --> CoinGecko[CoinGecko API]
```

</details>

<details>
<summary>WalletConnect pairing sequence</summary>

```mermaid
sequenceDiagram
    participant P as Player
    participant S as Server
    participant R as WC Relay
    participant W as Wallet App

    P->>S: /wallet connect
    S->>S: Generate X25519 keypair + symKey
    S->>R: Publish session proposal (tag 1100)
    S->>P: QR map with wc: URI
    P->>W: Scan QR
    W->>R: Session approval + responder pubkey
    R->>S: Approval response
    S->>S: X25519 key agreement, derive session key
    R->>S: Session settle (accounts, chains)
    S->>R: Settle acknowledgment (tag 1103)
    S->>P: Wallet connected!
```

</details>

<details>
<summary>Deposit and withdrawal flow</summary>

```mermaid
sequenceDiagram
    participant P as Player
    participant S as Server
    participant W as Wallet
    participant C as MineFiVault

    Note over P,C: Deposit
    P->>S: /wallet approve 1.0
    S->>W: eth_sendTransaction (deposit)
    W->>C: deposit(player) + 1 ETH
    C-->>S: Deposited event
    S->>S: Credit balance (FIFO ledger)

    Note over P,C: Spend
    P->>S: Buy item at shop
    S->>S: Vault.withdraw(), FIFO deduct

    Note over P,C: Withdraw
    P->>S: /wallet withdraw
    S->>W: eth_signTypedData_v4 (EIP-712)
    W-->>S: Signature
    S->>C: withdraw(player, amount, sig)
    C->>C: Verify sig, deduct gas
    C->>W: Payout (amount - gas)
    C->>S: Gas reimbursement
```

</details>

## Commands

```
/wallet                             open the book
/wallet connect                     QR to link a wallet
/wallet approve <amount>            deposit ETH
/wallet withdraw <chain> <amount>   withdraw crypto
/wallet verify                      check balance against Merkle root
/wallet history                     recent transactions
```

Full reference in [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Requirements

* Spigot or Paper 1.20+
* Java 17+
* [Vault](https://github.com/MilkBowl/VaultAPI) (for economy integration)
* [WalletConnect Cloud](https://cloud.walletconnect.com) project ID (for crypto)
* Stripe and/or Razorpay keys (for cards/UPI)

## Building

```bash
./gradlew shadowJar
cp build/libs/MineFi-0.1.0.jar /path/to/server/plugins/
```

Restart once to write `plugins/MineFi/config.yml`, fill in your keys, restart again.

## Project Structure

```
MineFi/
├── contracts/
├── docs/
│   └── media/
├── site/
├── src/
│   ├── main/kotlin/com/minefi/
│   │   ├── chain/
│   │   ├── commands/
│   │   ├── gui/
│   │   ├── listeners/
│   │   ├── map/
│   │   ├── merkle/
│   │   ├── price/
│   │   ├── provider/
│   │   ├── relay/
│   │   ├── storage/
│   │   └── vault/
│   └── test/
├── build.gradle.kts
└── settings.gradle.kts
```

| Directory | What it does |
|---|---|
| [`contracts/`](contracts/) | Solidity escrow contract, deployable to any EVM chain |
| [`site/`](site/) | Landing page and payment redirect pages (Cloudflare Pages) |
| [`relay/`](src/main/kotlin/com/minefi/relay/) | WalletConnect v2: WebSocket, session management, [X25519](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/deriveKey#ecdh) + [ChaCha20-Poly1305](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/encrypt) encryption |
| [`merkle/`](src/main/kotlin/com/minefi/merkle/) | Keccak-256 Merkle tree for on-chain balance proofs |
| [`provider/`](src/main/kotlin/com/minefi/provider/) | Payment providers (crypto, Stripe, Razorpay) behind a common interface |
| [`map/`](src/main/kotlin/com/minefi/map/) | QR code and receipt rendering on Minecraft maps |
| [`gui/`](src/main/kotlin/com/minefi/gui/) | Book-based in-game menu with clickable buttons |
| [`vault/`](src/main/kotlin/com/minefi/vault/) | Vault economy bridge |
| [`chain/`](src/main/kotlin/com/minefi/chain/) | [web3j](https://github.com/hyperledger/web3j) RPC, transaction signing, contract calls |

## Technologies

| Library | Used for |
|---|---|
| [web3j](https://github.com/hyperledger/web3j) | EVM transaction signing, contract interaction |
| [BouncyCastle](https://www.bouncycastle.org/java.html) | X25519, Ed25519, HKDF, ChaCha20-Poly1305, Keccak-256 |
| [ZXing](https://github.com/zxing/zxing) | QR code generation |
| [OkHttp](https://square.github.io/okhttp/) | HTTP + WebSocket for relay, Stripe, Razorpay, CoinGecko |
| [OpenZeppelin](https://github.com/OpenZeppelin/openzeppelin-contracts) | [EIP712](https://eips.ethereum.org/EIPS/eip-712), ECDSA, MerkleProof, Nonces |
| [Vault API](https://github.com/MilkBowl/VaultAPI) | Economy provider interface |
| [SQLite (xerial)](https://github.com/xerial/sqlite-jdbc) | Balances, sessions, deposits, transactions |
| [Silkscreen](https://fonts.google.com/specimen/Silkscreen) | Landing page pixel font |

## Documentation

| | |
|---|---|
| [Features](docs/FEATURES.md) | What it does, for server owners |
| [Configuration](docs/CONFIGURATION.md) | `config.yml` reference and every command |
| [Redirect pages](docs/REDIRECT_PAGES.md) | Hosting Stripe/Razorpay success pages |
| [Providers](docs/PROVIDERS.md) | Writing a new payment provider |
| [Contract](docs/CONTRACT.md) | `MineFiVault.sol` reference |
| [Architecture](docs/ARCHITECTURE.md) | How the pieces fit |

## License

[MIT](LICENSE)
