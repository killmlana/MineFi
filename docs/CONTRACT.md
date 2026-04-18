# MineFiVault contract

The on-chain escrow that holds player deposits, relays server-signed withdrawals, and anchors off-chain balances via a Merkle root so players can recover funds if the server goes dark.

Source: [`contracts/MineFiVault.sol`](../contracts/MineFiVault.sol)

## Model

Players deposit ETH by calling `deposit(player)` directly — nothing leaves their control except the deposit itself. Balances are tracked on-chain per address. When a player wants to withdraw, they sign an EIP-712 `Withdraw` struct; the server submits it. The contract verifies the signature, pays gas out of the withdrawal amount, and sends the rest to the player.

Separately, the server publishes a Merkle root of every player's off-chain (spendable) balance on a regular cadence. If the server disappears or refuses a withdrawal, any player can prove their last-known balance against the root and pull their on-chain funds directly with `emergencyWithdraw`.

## Roles

| Role | Set | What it can do |
|---|---|---|
| `server` | Constructor (`msg.sender` at deploy) | Relay withdrawals, publish the balance root |
| Anyone | — | Deposit, verify balances, emergency-withdraw |

The server role is single-address and immutable after deployment. If you need to rotate keys, deploy a new vault and migrate.

## State

| Variable | Type | Meaning |
|---|---|---|
| `balances[player]` | `uint256` | On-chain ETH balance per player address, in wei |
| `server` | `address` | The relayer / root publisher |
| `balanceRoot` | `bytes32` | Latest Merkle root of off-chain balances |
| `lastRootUpdate` | `uint256` | Unix timestamp of the last root publish |
| `_nonces[player]` | `uint256` | OZ `Nonces` — prevents withdrawal replay |

## Entry points

### `deposit(address player)` — payable
Adds `msg.value` to `balances[player]`. Anyone can call this for any player — deposits on behalf of another address are fine. Zero-value deposits revert.

Emits `Deposited(player, amount)`.

### `withdraw(address player, uint256 amount, bytes signature)`
Called by the server after the player signs a `Withdraw` typed-data payload.

The signature covers:
```solidity
Withdraw(address player, uint256 amount, uint256 nonce)
```
with domain `EIP712("MineFiVault", "1")` at the deployed contract address and current chain ID.

The contract:
1. Reads the current nonce for `player`, increments it (prevents replay)
2. Recovers the signer and asserts it equals `player`
3. Measures gas consumed so far, adds a 50,000 gas buffer for the transfers below, computes `gasCost = gasUsed * tx.gasprice`
4. Requires `amount > gasCost` — no withdrawals smaller than the gas to send them
5. Decrements `balances[player]` by the full `amount`
6. Sends `amount - gasCost` to `player` (the player receives payout net of gas)
7. Sends `gasCost` back to `server` (the server is reimbursed for the tx it just paid for)

Emits `Withdrawn(player, payout, gasCost)`.

**Gas model:** the server pays gas upfront, then takes it back from the player's withdrawal. So the hot wallet only needs a small ETH float to float tx fees — not the full withdrawal amount. Withdrawals that don't cover their own gas simply revert.

### `updateBalanceRoot(bytes32 root)`
Server-only. Overwrites `balanceRoot` and sets `lastRootUpdate = block.timestamp`.

The plugin publishes this on a timer (default: every 60 minutes, configurable via `vault.merkle-root-interval-minutes`). The root is computed off-chain from the full `(player, balance)` ledger in `minefi.db` using OpenZeppelin's leaf format:

```
leaf = keccak256(bytes.concat(keccak256(abi.encode(player, claimedBalance))))
```

Emits `BalanceRootUpdated(root, timestamp)`.

### `verifyBalance(address player, uint256 claimedBalance, bytes32[] proof) → bool`
Pure read. Returns whether `(player, claimedBalance)` is a leaf in the currently published `balanceRoot`. Anyone can call this; it's what `/wallet verify` runs under the hood.

### `emergencyWithdraw(uint256 claimedBalance, bytes32[] proof)`
The escape hatch. Called directly by the player when the server refuses to relay their withdrawal.

Gates:
1. A root must have been published (`balanceRoot != 0`)
2. At least one hour must have passed since the last root update (`block.timestamp > lastRootUpdate + 1 hours`) — this is the dispute window. It prevents a player from racing a fresh root to withdraw stale balances.
3. The caller's `(msg.sender, claimedBalance)` must verify against the proof

If it all checks out, the contract sends the player `min(claimedBalance, balances[msg.sender])` — capped at their actual on-chain deposit because the claimed off-chain balance may include funds the server already paid out.

Emits `DisputeWithdrawal(player, amount)`.

### `balanceOf(address player) → uint256`
Read-only accessor for `balances[player]`.

### `getNonce(address player) → uint256`
Read-only accessor for the player's current withdrawal nonce. The plugin reads this before asking the player to sign.

## Events

```solidity
event Deposited(address indexed player, uint256 amount);
event Withdrawn(address indexed player, uint256 amount, uint256 gasCost);
event BalanceRootUpdated(bytes32 root, uint256 timestamp);
event DisputeWithdrawal(address indexed player, uint256 amount);
```

The plugin listens for `Deposited` to credit deposits to the off-chain ledger. The other events are purely informational for the server operator.

## Deploying

The contract is chain-agnostic — any EVM network works. `test-server/hardhat/scripts/deploy.js` is the reference deploy script for a local Hardhat node:

```bash
cd test-server/hardhat
npm install
npx hardhat node &
npx hardhat run scripts/deploy.js --network localhost
```

For a real network, set `RPC_URL` and `PRIVATE_KEY` in the script (or rewrite it to read from env) and point it at your target. The address it prints is what you put in `plugins/MineFi/config.yml`:

```yaml
providers:
  crypto:
    chain:
      rpc-url: "https://mainnet.infura.io/v3/..."
      chain-id: 1
    vault:
      escrow-contract: "0x..."          # deployed address
      server-hot-wallet: "0x..."        # deployer address (= server role)
      server-hot-wallet-key: "..."      # deployer private key
```

The account that deploys the contract becomes the `server`. Keep its key in `server-hot-wallet-key` and fund it with a little ETH for gas.

## Threat model

**Server is honest, online.** Normal flow. Players deposit, spend off-chain through Vault, withdraw via `withdraw()`. The Merkle root is published for auditability but rarely needed.

**Server is dishonest or offline.** Players can't spend off-chain balance — the plugin is what authorizes shop purchases. But players *can* recover their on-chain deposit by waiting at least an hour past the last root publish, then calling `emergencyWithdraw` with a proof generated against that root.

**Server tries to front-run emergency withdrawals.** They can't — once a root is published, a new root can't shrink it retroactively (balances are only ever decremented when a deposit is withdrawn, which itself burns on-chain funds). The one-hour dispute window exists so a cooperative server can push a corrected root before the escape hatch opens.

**Replay attacks.** Each `withdraw()` consumes the player's nonce (OpenZeppelin `Nonces`). The same signature cannot be submitted twice.

**Signature forgery / phishing.** The signed payload is a full EIP-712 typed struct bound to the contract address and chain ID. WalletConnect-compatible wallets display `player`, `amount`, and `nonce` before the player signs.

## What's missing

The contract intentionally doesn't handle ERC-20 deposits — only native chain gas token. If you want stablecoin deposits, that's a new contract (add `IERC20 token` per vault instance, use `safeTransferFrom` in `deposit`, track per-token balances). Same threat model applies.

There's also no admin upgrade path, no pausing, no fee accrual. It's deliberately minimal — the trust assumption is "server holds the hot wallet key," and anything beyond that gets layered on top off-chain.
