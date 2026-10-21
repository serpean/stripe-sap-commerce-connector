# Upgrade from 2.0.0 to 3.0.0

Version 3.0.0 makes the connector a standard SAP connector. It breaks compatibility with 2.0.0. Read this guide before you upgrade. For the earlier upgrade, see [cleanup.md](cleanup.md).

## Before you start

- Stop taking orders, or upgrade in a quiet period.
- Check for orders that wait for a payment (see [Existing data](#existing-data)).
- Back up the database. `ant updatesystem` creates new item types and a new status.

## Backend

### Extensions

- **`stripefulfilmentprocess` is removed.** Remove it from `localextensions.xml`, from your manifest, and from any `requires-extension`. The connector no longer ships copies of the SAP order processes. It wraps the beans of the SAP process that your project uses (OMS, classic fulfilment, or your own copy). The extension had copies of `order-process`, `consignment-process`, and `return-process` with their Spring files. Remove any such copy from your project and use your standard process.
- **`StripeCheckoutFacade` is no longer the only `checkoutFacade`.** It is now a child bean of `defaultCheckoutFacade`, and `StripeAcceleratorCheckoutFacade` is a child of `defaultAcceleratorCheckoutFacade`. If your project overrides the checkout facade, make your bean extend the SAP one and keep the Stripe child beans in your chain, or give the Stripe aliases (`checkoutFacade`, `acceleratorCheckoutFacade`) to your child bean with the Stripe beans as parent.
- **Old classes are removed:** `StripePaymentProvider`, `StripeWebhookService`, `StripePaymentFacade`. Replace any use. The payment logic is in `StripeGateway`, `StripePaymentIntentService`, and `StripeWebhookFacade`.
- **New dependencies:** `stripe-java` 30.0.0 (API version `2025-09-30.clover`) and `gson` 2.11.0. Maven downloads them. The build needs access to Maven Central.

### Item types

- `StripePaymentInfo` has new attributes: `paymentMethodType`, `paymentMethodId`, `cardBrand`, `cardLast4`, `cardExpMonth`, `cardExpYear`. The attribute `stripePaymentIntentId` stays.
- New item types: `StripePaymentIntentRecord` (table `stripe_pi_record`), `StripeOrderPlacementLock` (`stripe_placement_lock`), `StripeWebhookEvent` (`stripe_webhook_event`).
- New enums: `StripePaymentIntentState` and `StripeWebhookEventState`. The `OrderStatus` enum gets the value `PAYMENT_PENDING`.
- **Removed:** the enum `StripeCaptureMode` and the attribute `PaymentTransaction.stripeCaptureMode`. Use the property `stripe.capture.mode`.
- Run `ant updatesystem` (or a CCv2 deployment with an update) to create them.

### How the connector hooks into SAP

- The alias `commercePaymentAuthorizationStrategy` points to the Stripe strategy. It gives other carts to the SAP strategy.
- `checkOrderAction`, `takePaymentAction`, and `OmsOrderCancelPaymentServiceAdapter` are replaced by type-preserving CGLIB proxies, found by bean name or alias. If your project also wraps these beans, test the order process.
- The alias `orderCancelPaymentServiceStrategy` points to `stripeOrderCancelPaymentServiceStrategy`.
- The SAP place-order cart validator gets advice that drops the error `cart.paymentInfoNotSet` for a cart with a Stripe PaymentIntent.
- **Classic fulfilment:** the return action of `yacceleratorfulfilmentprocess` is a SAP mock. Replace it with an action that calls `paymentService.refundFollowOn`. OMS returns need no change.

## OCC

| Change | From 2.0.0 | To 3.0.0 |
|--------|-----------|----------|
| Intent endpoint response | `intentId`, `clientSecret`, `status` | `paymentIntentId`, `clientSecret`, `status` |
| Intent endpoint behaviour | Created an intent | Creates one PaymentIntent for the cart, or updates its amount and currency. Call it again before you confirm. |
| New endpoint | none | `GET /{baseSiteId}/payments/stripe/config` gives `publishableKey` |
| Cart and order DTO | none | New field `stripePaymentInfo` (`paymentMethodType`, `cardBrand`, `cardLast4`, `cardExpMonth`, `cardExpYear`, `paymentStatus`) |
| Webhook endpoint | `POST /{baseSiteId}/payments/stripe/webhook` | Same path. It stores the event and answers at once. A task processes it. |

### Error codes

Place-order and intent calls now answer with an SAP error list. The error `type` is `StripePaymentError`. The `reason` is a stable code:

| Code | HTTP |
|------|------|
| `STRIPE_AMOUNT_MISMATCH` | 400 |
| `STRIPE_PAYMENT_FAILED` | 400 |
| `STRIPE_ALREADY_PLACED` | 400 |
| `STRIPE_PAYMENT_IN_PROGRESS` | 400 |
| `STRIPE_UNAVAILABLE` | 503 |

Change any client that reads the old generic error.

## Configuration

New properties (all accept the site override `stripe.<name>.<baseSiteUid>`):

| Property | Default |
|----------|---------|
| `stripe.webhook.graceperiod.seconds` | `300` |
| `stripe.payment.pending.timeout.seconds` | `86400` |
| `stripe.payment.pending.retry.seconds` | `300` |

Existing properties keep their names. They now accept the site override too: `stripe.secret.key`, `stripe.publishable.key`, `stripe.webhook.secret`, `stripe.capture.mode`, `stripe.capture.manual.methods`, `stripe.statement.descriptor`, `stripe.descriptor.suffix`.

In the Stripe Dashboard, make sure the webhook endpoint sends: `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.processing`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`, `refund.updated`.

## Order status PAYMENT_PENDING

A payment that confirms later now gives the order the status `PAYMENT_PENDING`. The first step of the order process waits and checks again after `stripe.payment.pending.retry.seconds`. After `stripe.payment.pending.timeout.seconds` the order is cancelled. Add the status to your reports, back-office views, and storefront labels. The connector adds the display value `paymentPending` to `acceleratorStatusDisplayMap` when that bean exists.

## Storefront

- **The backend serves the publishable key** (`GET payments/stripe/config`). `StripePaymentConfig` is optional now. Remove it from your app, or keep it to override the backend key.
- **The option to save the card for later orders is removed.** Remove the translation key `saveForLater` from your overrides.
- Add the OCC endpoint `stripeConfig: 'payments/stripe/config'` if you override `defaultStripeOccConfig`.
- Use the new translation keys: `paymentFailed`, `paymentPending`, `amountChanged`, `verificationFailed`, `alreadyPlaced`.
- Update the library `sap-commerce-stripe-payment` to 3.0.0. The response field is `paymentIntentId`, not `intentId`.

## Existing data

- **Pending orders from 2.0.0.** Orders that wait with the transaction status `WAITING` (or `REVIEW`) have no `StripePaymentIntentRecord`. The webhook cannot link them. Let them finish before you upgrade, or fix them by hand.
- **Open carts.** A cart whose `StripePaymentInfo` has a PaymentIntent from 2.0.0 gets a record when the customer calls the intent endpoint again.
- **Transactions with `stripeCaptureMode`.** The value stays in the database but nothing reads it. The attribute is gone from the model.
- **Authorizations expire after 7 days at Stripe.** Capture old authorizations before you upgrade.

## Known limitations

- `PAYMENT_PENDING` is not testable end to end with a Stripe test account in Spain.
- No reconciliation job yet for webhook events in `FAILED` state, or for orphan PaymentIntents.
- A retry of a cancel adjustment can replay a Stripe failure saved for the same idempotency key, for up to 24 hours.
- HSQLDB has no row locks.
