# Hosting success and cancel pages

Stripe and Razorpay both redirect the player's browser to a URL you control after the payment flow. MineFi doesn't bundle those pages — you host them wherever makes sense (Cloudflare Pages, Vercel, Netlify, your own static host, whatever).

This doc covers what query parameters each provider sends, what a good page does with them, and gives you a minimal HTML starter.

## Why you host this yourself

The redirect isn't where MineFi credits the balance. MineFi polls the provider's API from the server and credits the balance server-side when the payment API confirms `paid`. The redirect page is purely for the player's browser — it just confirms visually that they paid. So the page can be completely static.

That means you can host it anywhere that serves HTML. No backend needed.

Don't trust the redirect query parameters for anything critical. A player can craft a fake URL with `?payment_id=whatever` by hand — the page should look pretty, not gate anything.

## Stripe

### What MineFi sends to Stripe

MineFi passes the `success_url` and `cancel_url` from config to Stripe's Checkout Session API. It appends one placeholder to the success URL:

```
<your-success-url>?session_id={CHECKOUT_SESSION_ID}
```

Stripe substitutes the real session ID at redirect time.

### What your page receives

**On success:**

| Query param | Example | Notes |
|---|---|---|
| `session_id` | `cs_test_a1b2c3...` | Stripe Checkout Session ID. Display it, don't trust it. |

**On cancel:** no params. Stripe just redirects to your cancel URL as-is.

### Minimal success page

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Payment received — MineFi</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 480px; margin: 80px auto; padding: 24px; text-align: center; }
    h1 { color: #16a34a; }
    code { background: #f4f4f5; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
    .muted { color: #666; font-size: 14px; }
  </style>
</head>
<body>
  <h1>Payment received</h1>
  <p>Your MineFi balance will update within 30 seconds. You can close this tab and jump back into the game.</p>
  <p class="muted">Reference: <code id="ref">—</code></p>
  <script>
    const params = new URLSearchParams(location.search);
    const sessionId = params.get("session_id");
    if (sessionId) document.getElementById("ref").textContent = sessionId;
  </script>
</body>
</html>
```

### Minimal cancel page

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Payment cancelled — MineFi</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 480px; margin: 80px auto; padding: 24px; text-align: center; }
    h1 { color: #dc2626; }
  </style>
</head>
<body>
  <h1>Payment cancelled</h1>
  <p>No charge was made. You can try again from the in-game wallet menu.</p>
</body>
</html>
```

## Razorpay

### What MineFi sends to Razorpay

MineFi sends `callback_url` and `callback_method: "get"` when creating the payment link. Razorpay redirects the browser to your success URL after payment with three GET parameters.

### What your page receives

**On successful payment:**

| Query param | Example | Notes |
|---|---|---|
| `razorpay_payment_id` | `pay_N1A2B3C4D5E6F7` | Razorpay payment ID. Display it. |
| `razorpay_payment_link_id` | `plink_N1X2Y3Z4W5V6` | The payment link ID. Matches the `id` MineFi stored in `pending_payments`. |
| `razorpay_payment_link_reference_id` | `""` (empty) | Only set if you passed `reference_id` when creating the link — MineFi doesn't. |
| `razorpay_payment_link_status` | `paid` | String status. |
| `razorpay_signature` | `a1b2...` | HMAC-SHA256 signature. Only useful server-side with your key secret — a static page can't verify it. |

**On cancelled/failed payment:** Razorpay does not redirect — it just keeps the user on the payment page. So you don't need a separate cancel page.

### Minimal success page

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Payment received — MineFi</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 480px; margin: 80px auto; padding: 24px; text-align: center; }
    h1 { color: #16a34a; }
    code { background: #f4f4f5; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
    .muted { color: #666; font-size: 14px; }
    .row { margin: 8px 0; }
  </style>
</head>
<body>
  <h1>Payment received</h1>
  <p>Your MineFi balance will update within 10 seconds. You can close this tab and jump back into the game.</p>
  <div class="row muted">Payment: <code id="pid">—</code></div>
  <div class="row muted">Status: <code id="status">—</code></div>
  <script>
    const params = new URLSearchParams(location.search);
    document.getElementById("pid").textContent = params.get("razorpay_payment_id") ?? "—";
    document.getElementById("status").textContent = params.get("razorpay_payment_link_status") ?? "—";
  </script>
</body>
</html>
```

## Configuring the URLs

MineFi ships with defaults pointing at `minefi.pages.dev`. That's fine for trying it out — your players will land on a generic success page hosted on Cloudflare Pages.

For anything real, host your own. Once your pages are up, point MineFi at them in `plugins/MineFi/config.yml`:

```yaml
providers:
  stripe:
    success-url: "https://yourserver.com/payment/success"
    cancel-url: "https://yourserver.com/payment/cancel"
  razorpay:
    success-url: "https://yourserver.com/payment/success"
```

Stripe and Razorpay can share the same success page if you want — the query params don't clash (Stripe sends `session_id`, Razorpay sends `razorpay_*`). A unified page can check for whichever params it got and render accordingly.

## A quick note on security

These pages are informational, full stop. Never put balance adjustments, rank grants, or any server-side side effects behind a redirect page. All of that happens in the plugin, which polls the provider API directly from the server. The page itself should be safe to deploy as static HTML with no backend and no secrets.
