# Writing a Payment Provider

A payment provider is anything that processes external transactions and credits or debits a player's MineFi balance. Crypto, Stripe, and Razorpay all implement the same interface — this doc walks through adding your own (PayPal, Coinbase Commerce, Paddle, a regional gateway, whatever).

## The interface

`src/main/kotlin/com/minefi/provider/PaymentProvider.kt`:

```kotlin
interface PaymentProvider {
    val name: String
    val currencies: List<String>

    fun initialize(plugin: MineFiPlugin, config: ConfigurationSection)
    fun shutdown()

    fun connect(player: Player): Boolean
    fun disconnect(player: Player)
    fun isConnected(player: Player): Boolean

    fun startDeposit(player: Player, amount: Double, currency: String): CompletableFuture<DepositResult>
    fun startWithdraw(player: Player, amount: Double, currency: String): CompletableFuture<WithdrawResult>
    fun supportsWithdraw(): Boolean
}
```

### Fields

`name` is a stable lowercase identifier. It doubles as the config key (`providers.<name>`), the deposit ledger column, and the menu label.

`currencies` is every currency string this provider ever emits. `StripeProvider` returns `["stripe_usd"]`; `CryptoProvider` returns `["eip155:1"]`. This is what ends up in the `deposits.chain` column and drives the Withdraw menu grouping.

### Lifecycle

`initialize(plugin, config)` runs once on plugin enable, on the main thread. Read your config section with `config.getString(...)` and friends, open HTTP clients, schedule polling tasks, and restore any pending sessions from `plugin.db`. Save the `plugin` reference — you'll need it for the scheduler, logger, and database.

`shutdown()` runs on plugin disable. Complete any dangling `CompletableFuture`s with a failure result so commands don't hang, and close any open resources.

### Connection state

`connect` / `disconnect` / `isConnected` exist for crypto-style providers where there's a per-player session (wallet pairing). For web payment providers like Stripe or Razorpay, there's no session — return `true` from `connect` and `isConnected`, and leave `disconnect` as a no-op. See `StripeProvider.kt` for the trivial shape.

### Deposits

`startDeposit(player, amount, currency)` is called when a player confirms a deposit amount. Your job is to:

1. Start the payment with the upstream provider — create a checkout session, a payment link, a contract call, whatever applies.
2. Show the player how to complete payment. The house style is a chat message with a clickable link plus a QR on a filled map. See `StripeProvider.sendClickableLink` and `giveQrMap`.
3. Poll, webhook, or subscribe until you know whether the payment succeeded or failed. Persist the pending session to `plugin.db.savePendingPayment(...)` so it survives a restart.
4. Complete the returned `CompletableFuture` with a `DepositResult`.

```kotlin
data class DepositResult(
    val success: Boolean,
    val txRef: String,       // provider transaction ID — saved to the deposits table
    val amount: Double,      // amount in the deposit's native currency
    val currency: String,    // must be one of your declared currencies
    val message: String? = null,  // error message when !success
)
```

Don't credit the balance yourself. `WalletCommand.handleDeposit` does that — it converts to USD via `PriceService`, writes the balance row, records the deposit ledger entry, and notifies the player. You just tell it whether payment succeeded.

### Withdrawals

If your provider can send money back to the player, return `true` from `supportsWithdraw()` and implement `startWithdraw`. The USD-to-native conversion has already been done by the time you're called, so you receive the native amount (ETH, tokens, etc.) and return a `WithdrawResult`.

If the provider is one-way (Stripe, Razorpay), return `false` and complete the future with a failure. The Withdraw menu won't offer withdrawal for currencies from one-way providers anyway, but the check is there as a safeguard.

## Registering the provider

`MineFiPlugin.kt` has a registration map:

```kotlin
val providerTypes = mapOf<String, () -> PaymentProvider>(
    "crypto"   to { CryptoProvider() },
    "stripe"   to { StripeProvider() },
    "razorpay" to { RazorpayProvider() },
)
```

Add your provider there. The plugin reads `providers.<name>.enabled` from config and calls your factory if it's true. That's the only wiring required.

## Config convention

Your config section lives under `providers.<name>`. Always support `enabled: false` as the default. A hypothetical PayPal provider:

```yaml
providers:
  paypal:
    enabled: false
    client-id: ""
    client-secret: ""
    currency: "usd"
    poll-interval-seconds: 10
    success-url: ""
```

Read the section in `initialize`:

```kotlin
override fun initialize(plugin: MineFiPlugin, config: ConfigurationSection) {
    this.plugin = plugin
    clientId = config.getString("client-id") ?: ""
    clientSecret = config.getString("client-secret") ?: ""
    if (clientId.isBlank() || clientSecret.isBlank()) {
        plugin.logger.warning("PayPal credentials not configured")
        return
    }
    // ...
}
```

If your provider needs a redirect page after payment, don't hardcode a URL — require `success-url` in config and log a warning if it's blank. See [REDIRECT_PAGES.md](REDIRECT_PAGES.md) for what a success page needs to do.

## Threading

`initialize` and `shutdown` run on the main thread.

`startDeposit` and `startWithdraw` are called from command execution — treat them as main-thread code and immediately hop off with `plugin.server.scheduler.runTaskAsynchronously(plugin) { ... }` before doing any HTTP.

Polling tasks must use `runTaskTimerAsynchronously`. Never block the main thread on network I/O.

When you need to touch Bukkit state (sending messages, manipulating inventories), hop back to the main thread with `plugin.server.scheduler.runTask(plugin) { ... }`.

Both `StripeProvider` and `RazorpayProvider` are good templates for the async/main-thread dance.

## Persistence

Use the shared DB (`plugin.db`) for anything that needs to survive a restart:

- Pending payments: `savePendingPayment`, `getPendingPayments`, `deletePendingPayment`. Restore these in `initialize` so in-flight payments still complete if the server restarted mid-flow.
- Don't create your own tables for provider-specific state. Extend the shared tables or keep ephemeral state in a `ConcurrentHashMap`.

## Testing checklist

Before shipping a provider, verify:

- A successful deposit credits the balance and writes both a `deposits` and `transactions` row
- A failed or cancelled deposit does not credit the balance
- A server restart mid-payment still credits the balance when the upstream eventually confirms
- `shutdown()` completes dangling futures so commands don't hang
- A disabled provider (`enabled: false`) is not registered and not shown in the Book GUI
- The withdraw path (if supported) restores the balance on failure — see how `WalletCommand.handleWithdraw` calls `adjustApprovedBalance` on the error path

## Reference implementations

- `StripeProvider.kt` — HTTP plus polling, pending session restore, QR map. Shortest end-to-end example.
- `RazorpayProvider.kt` — similar to Stripe but with UPI QR rendering and a pixel-art receipt on the map.
- `CryptoProvider.kt` — persistent per-player sessions, EIP-712 signing, contract interaction. The heavier end of the spectrum.
