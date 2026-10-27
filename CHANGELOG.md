# Changelog

All notable changes to the Stripe connector for SAP Commerce Cloud are listed here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- `StripePaymentInstructionsComponent` now lives in the entry point `sap-commerce-stripe-payment/root`
  (it was in `/components`), and its outlets are provided by `StripePaymentRootModule`, so they exist before the
  lazy payment feature loads. The order details page uses the order overview outlet; the order confirmation page
  uses the outlet of the CMS component `OrderConfirmationThankMessageComponent`.
- `stripefacades` requires `ordermanagementfacades` and overrides the `omsOrderFacade` bean: the SAP `isPartialCancel`
  counted every request as a partial cancel, so a full cancel of an order with a pending payment was denied.

## [3.0.0] - 2026-10-21

The connector is now a standard SAP connector. It plugs into the SAP checkout, order process, and
cancel flows instead of replacing them. It breaks compatibility with 2.0.0.
See [docs/migration/standard-connector.md](docs/migration/standard-connector.md) for the upgrade steps.

### Added

- One Stripe PaymentIntent for each cart. The amount is synced with the cart total before the payment
  is confirmed. The server reads the PaymentIntent from Stripe and decides; it never trusts the browser.
- Webhook safety net: the server places the order when the browser does not come back, after a grace
  period counted from the Stripe event time. Idempotent intake, SAP `Task` processing, retries with
  backoff (30 s, 2 min, 8 min, 30 min, 5 attempts). Unreadable events fail at once.
- Refund or void of the money when an order cannot be placed (cart missing, amount mismatch,
  authorization rejected).
- Item types `StripePaymentIntentRecord`, `StripeOrderPlacementLock`, `StripeWebhookEvent`, and new
  `StripePaymentInfo` attributes (`paymentMethodType`, `paymentMethodId`, `cardBrand`, `cardLast4`,
  `cardExpMonth`, `cardExpYear`).
- Order status `PAYMENT_PENDING` for payments that confirm later, with retry delay and timeout.
- Capture timing: manual (default) or automatic. Methods without manual capture are captured at once.
  Partial capture.
- After purchase with OMS and with the classic fulfilment process: capture of the current total,
  cancel from the live PaymentIntent, full cancel that releases everything including delivery cost,
  retry task for failed refunds and voids.
- OCC: `GET payments/stripe/config` (publishable key), field `stripePaymentInfo` on carts and orders,
  and stable error codes `STRIPE_AMOUNT_MISMATCH`, `STRIPE_PAYMENT_FAILED`, `STRIPE_ALREADY_PLACED`,
  `STRIPE_UNAVAILABLE` (503), `STRIPE_PAYMENT_IN_PROGRESS`.
- Properties `stripe.webhook.graceperiod.seconds`, `stripe.payment.pending.timeout.seconds`,
  `stripe.payment.pending.retry.seconds`. Every `stripe.*` property accepts a site override
  `stripe.<name>.<baseSiteUid>`.
- Stripe API version `2025-09-30.clover` (`stripe-java` 30.0.0) and a `gson` 2.11.0 dependency.
- Reference project: OMS recipe, EUR site, EUR prices and OMS sourcing data in `stripesampledata`,
  `core-customize/scripts/stripe-listen.sh` (Stripe CLI, the key comes from the environment), and a
  Playwright end-to-end battery of 17 payment cases.
- Spartacus library: payment form that handles the error codes, blocks double submit, and shows a
  pending state.

### Changed

- **Breaking:** `StripeCheckoutFacade` and `StripeAcceleratorCheckoutFacade` are child beans of
  `defaultCheckoutFacade` and `defaultAcceleratorCheckoutFacade`. They are no longer the only
  `checkoutFacade`.
- **Breaking:** the OCC intent response has `paymentIntentId` instead of `intentId`.
- **Breaking:** the backend serves the publishable key. `StripePaymentConfig` is optional.
- **Breaking:** pending payments use the order status `PAYMENT_PENDING`.
- **Breaking:** payment errors on place order use the stable codes, not a generic error.
- The connector hooks into SAP by bean name or alias, with type-preserving CGLIB proxies on
  `checkOrderAction`, `takePaymentAction`, and `OmsOrderCancelPaymentServiceAdapter`.
- The place-order validator is advice on a proxy of the SAP validator.
- The Spartacus library `sap-commerce-stripe-payment` is now version 3.0.0.

### Removed

- **Breaking:** the `stripefulfilmentprocess` extension and its copies of the SAP order processes.
  The connector now wraps the SAP process beans.
- **Breaking:** the enum `StripeCaptureMode` and the attribute `PaymentTransaction.stripeCaptureMode`.
  The capture mode is a property.
- **Breaking:** the option to save the card for later orders.
- Old classes: `StripePaymentProvider`, `StripeWebhookService`, `StripePaymentFacade`.

### Fixed

- A failed or cancelled PaymentIntent gives the stable code `STRIPE_PAYMENT_FAILED`, and the payment
  form refreshes its view (OnPush).
- Held funds are released before a payment-failed result.
- A rejected authorization of a paid PaymentIntent releases the money.
- Refund and void idempotency keys survive SAP retries.

## [2.0.0] - 2026-10-19

This release removes everything that is not part of the connector, and every
dependency on SAP Open Payment Framework. It breaks compatibility with 1.0.0.
See [docs/migration/cleanup.md](docs/migration/cleanup.md) for the upgrade steps.

### Removed

- `stripestore` extension (a copy of the electronics sample store).
- Dependency on SAP Open Payment Framework in the backend and the storefront:
  - `@spartacus/opf`, `OpfFeatureModule`, `OpfOrderModule` and the related styles.
  - Use of `TransactionStatus.WAITING`, which only exists when `opfservices` is installed.
- The Stripe Java SDK jar from the repository. Maven downloads it at build time.
- `stripeConfirm` OCC endpoint configuration, which had no backend.
- `stripeocc` template code (`StripeoccController`, `StripeConfirmRequest`) and a test that did not compile.
- IDE files (`.classpath`, `.settings`) and the IDE helper steps in `buildcallbacks.xml`.

### Changed

- **Breaking:** pending payments use the standard `TransactionStatus.REVIEW` instead of `WAITING`.
- **Breaking:** the configuration uses the property names that the connector reads:
  `stripe.secret.key` and `stripe.publishable.key` (not `stripe.api.secret.key` and `stripe.api.public.key`).
- **Breaking:** the Stripe publishable key is no longer in the storefront code. The app gives it to the
  library through `StripePaymentConfig`.
- The connector builds on SAP Commerce 2211.55 with Java 17.
- The Spartacus library `sap-commerce-stripe-payment` is now version 2.0.0.

### Added

- Reference project, separate from the published connector:
  - `stripesampledata` extension: sets `paymentProvider=Stripe` on the `electronics` BaseStore and
    creates the demo OAuth client `mobile_android` (reference only, never deploy it).
  - `core-customize/scripts/fetch-spartacussampledata.sh`: downloads the official Spartacus sample
    data (release `221121-x`) and checks its SHA-256. The sample data is a dependency, not stored in git.
  - The recipe reads the Stripe keys from the environment (`STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`,
    `STRIPE_WEBHOOK_SECRET`).
- Playwright end-to-end test for a card checkout without 3DS (`npm run e2e`).
- README sections for the connector and the reference project, and a migration guide.

## [1.0.0]

- First published version.
