# Stripe Connector for SAP Commerce Cloud

Version 3.0.0.

Stripe provides payment infrastructure for the internet. This connector adds Stripe payments to SAP Commerce Cloud through the Payment Intents API. It supports cards and other Stripe payment methods, 3D Secure (SCA), and the standard SAP checkout, order process, and cancel and refund flows.

## Release Compatibility

- SAP Commerce Cloud 2211 (tested with 2211.55)
- SAP Commerce REST API (OCC)
- Spartacus 2211.37
- Java 17
- Stripe API version `2025-09-30.clover`, with `stripe-java` 30.0.0. Maven downloads it.
- `gson` 2.11.0. `stripe-java` needs it at run time. It is declared in `stripecore`, and it is the version the platform already uses.

## Features

- **Payment Intents API**: one PaymentIntent for each cart, with 3DS/SCA.
- **Standard SAP flow**: the connector plugs into the SAP checkout facades, the order process, and order cancel. It does not replace them.
- **Server decides**: the server reads the PaymentIntent from Stripe. It never trusts the browser.
- **Webhooks**: a safety net that places the order when the browser does not come back.
- **Capture**: manual (default) or automatic, and partial capture.
- **After purchase**: capture, partial capture, cancel, and refund, with OMS or with the classic fulfilment process.
- **Headless**: OCC endpoints and a Spartacus library.

## Project Structure

The repository holds two things. Only the connector is published.

### Connector (published)

| Path | Content |
|------|---------|
| `core-customize/hybris/custom/stripe/stripecore` | Item types, Stripe gateway, PaymentIntent service, authorization strategy, webhook processing, order process steps, cancel strategy |
| `core-customize/hybris/custom/stripe/stripefacades` | Checkout facades (child beans), checkout authorizer, webhook facade, order payment populator |
| `core-customize/hybris/custom/stripe/stripeocc` | OCC endpoints, error codes, place-order validator advice |
| `js-storefront/stripestore/integrations-libs/stripe` | Spartacus library `sap-commerce-stripe-payment` (version 3.0.0) |

### Reference project (not published)

Use it for demos and to run the end-to-end tests. Do not copy it into a customer project.

| Path | Content |
|------|---------|
| `core-customize/installer/recipes/b2c_occ_stripe` | Installer recipe. It uses the order management (OMS) extensions. |
| `core-customize/scripts/fetch-spartacussampledata.sh` | Downloads the official SAP Spartacus sample data (`electronics-spa`), release `221121-x` of [SAP-samples/cloud-commerce-sample-setup](https://github.com/SAP-samples/cloud-commerce-sample-setup/releases/tag/sampledata), into `core-customize/hybris/custom/spartacus/spartacussampledata`. The extension is a dependency and is not stored in git. It contains sample data for SAP Open Payment Framework, which is imported only when that framework is installed. |
| `core-customize/scripts/stripe-listen.sh` | Forwards Stripe webhook events to the local server with the Stripe CLI |
| `core-customize/hybris/custom/reference/stripesampledata` | Sample data for the Stripe demo (see [stripesampledata](#stripesampledata)) |
| `core-customize/manifest.json`, `js-storefront/manifest.json` | CCv2 manifests |
| `js-storefront/stripestore/src` | Spartacus demo app |
| `js-storefront/stripestore/e2e` | Playwright end-to-end tests |

> **Warning:** `stripesampledata` creates the OAuth client `mobile_android` with the well-known secret `secret` and the `password` and `client_credentials` grants. It is for local demos and tests only. Never deploy the reference project to an environment that other people can reach.

Upgrade guides: [2.0.0 to 3.0.0](docs/migration/standard-connector.md) and [1.0.0 to 2.0.0](docs/migration/cleanup.md). The history is in [CHANGELOG.md](CHANGELOG.md).

# Installation and Usage

## Prerequisites

- SAP Commerce Cloud 2211 (tested with 2211.55)
- Java 17 (SAP Commerce 2211 rejects other versions)
- Node.js 20+ (for Spartacus)
- Stripe account with API keys

## Install the connector in your project

1. Copy the connector extensions to `${HYBRIS_BIN_DIR}/custom/stripe`.

2. Add the extensions to your `localextensions.xml`:

```xml
<!-- Stripe Payment -->
<extension name="stripecore"/>
<extension name="stripefacades"/>
<extension name="stripeocc"/>
```

3. Set `paymentProvider=Stripe` on the BaseStore that uses Stripe.

4. Build. Maven downloads the Stripe Java SDK and gson (`usemaven=true`).

```bash
cd hybris/bin/platform
. ./setantenv.sh
ant clean all
```

5. Start the server and update the system (the update creates the new item types):

```bash
./hybrisserver.sh
ant updatesystem
```

6. In the Stripe Dashboard, add a webhook endpoint `https://<host>/occ/v2/<baseSiteUid>/payments/stripe/webhook`. Send at least these events: `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.processing`, `payment_intent.requires_action`, `payment_intent.partially_funded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`, `refund.updated`. Put the signing secret in `stripe.webhook.secret`.

## Configuration

Add your Stripe settings to `local.properties`. Never store real keys in git.

```properties
stripe.secret.key=sk_test_your_secret_key
stripe.publishable.key=pk_test_your_public_key
stripe.webhook.secret=whsec_your_webhook_secret
stripe.capture.mode=manual
```

### All properties

Every property accepts a site override: `stripe.<name>.<baseSiteUid>`, for example `stripe.capture.mode.electronics-spa=automatic`. The connector reads the site value first, then the global value. In a storefront request, the site is the current base site. In background work (webhook tasks, order process steps, cancel and refund, retry tasks), the connector uses the site of the data: the site of the PaymentIntent record, of the order, or of the cart.

| Property | Default | Meaning |
|----------|---------|---------|
| `stripe.secret.key` | empty | Stripe secret key (`sk_...`) |
| `stripe.publishable.key` | empty | Stripe publishable key (`pk_...`). The backend serves it to the storefront. |
| `stripe.webhook.secret` | empty | Webhook signing secret (`whsec_...`) |
| `stripe.capture.mode` | `manual` | `manual` or `automatic` |
| `stripe.capture.manual.methods` | `card,link,paypal,amazon_pay,revolut_pay,mobilepay,satispay,klarna,sequra,scalapay,billie` | Payment methods that support manual capture, comma-separated. Other methods are captured at once. |
| `stripe.statement.descriptor` | empty | Statement descriptor |
| `stripe.descriptor.suffix` | empty | Statement descriptor suffix |
| `stripe.webhook.graceperiod.seconds` | `300` | Wait before the webhook places an order. It counts from the time of the Stripe event. |
| `stripe.payment.pending.timeout.seconds` | `86400` | A pending payment older than this cancels the order |
| `stripe.payment.pending.retry.seconds` | `300` | Delay between checks of a pending payment |
| `stripe.payment.pending.retry.<type>.seconds` | `3600` for `multibanco` and `customer_balance` | Delay between checks, per payment method type (falls back to the global value) |

## Installing on SAP Commerce Cloud

Follow the [SAP Commerce Cloud documentation](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD) to prepare your repository for deployment.

1. Include the Stripe extensions in the `core-customize` folder
2. Update your `manifest.json` to include the extensions
3. Add the Stripe keys as environment properties in SAP Commerce Cloud Portal

# How the payment works

## Payment flow

1. **Prepare.** The storefront calls `POST .../carts/{cartId}/payments/stripe/intent`. The connector creates one PaymentIntent for the cart, or updates it. It stores the PaymentIntent in its own record (`StripePaymentIntentRecord`) and on the cart payment info.
2. **Sync before confirm.** The storefront calls the endpoint again just before it confirms the payment. The connector sets the PaymentIntent amount and currency to the current cart total. The amount is the total the customer pays: total price, plus tax for net stores.
3. **Confirm.** Stripe.js confirms the payment in the browser (3DS if needed).
4. **Place order.** The storefront calls the standard SAP place-order endpoint. The authorization strategy reads the PaymentIntent from Stripe and decides. It checks the status, the amount, and the currency. The browser result does not count.
5. **Webhook safety net.** If the customer pays but the browser never calls place order, the webhook places the order on the server. It waits a grace period first (`stripe.webhook.graceperiod.seconds`). The wait counts from the time of the Stripe event, not from the creation of the PaymentIntent, so a slow payer does not race the server.

Rules:

- One PaymentIntent pays one order. A lock (`StripeOrderPlacementLock`) stops two orders for the same PaymentIntent. A lock older than 10 minutes with no order is stale and is removed.
- If the cart changes, the PaymentIntent is updated. If it was cancelled, a new one is created. If it is in `processing` and the cart changed, the connector answers `STRIPE_PAYMENT_IN_PROGRESS`.
- A paid PaymentIntent (authorized or captured) is released for a changed cart only when the connector can take its placement lock and no order exists for it. When an order is being placed for it (for example in another browser tab), the connector answers `STRIPE_PAYMENT_IN_PROGRESS` and changes nothing. After the release the lock stays, so no order can use that PaymentIntent later.
- When a PaymentIntent is released or replaced, the connector removes the cart's SAP payment transactions of that PaymentIntent. An order never carries an authorization of a released PaymentIntent.
- After the order is placed, the connector compares the order total with the PaymentIntent amount. When they differ (the cart changed in another tab while the order was placed), it logs an ERROR, cancels the order, and gives the money back.
- When SAP cannot finish the authorization (Stripe did not answer), the connector keeps the payment and answers `STRIPE_UNAVAILABLE`. The customer can try again. Only a rejected authorization releases the money.
- The connector refunds the money (or voids the authorization) when it cannot place an order for a paid PaymentIntent:
  - the cart is missing;
  - the amount does not match the cart;
  - SAP rejects the authorization.
- A PaymentIntent that was refunded is never accepted as a payment.

## Capture timing

| Mode | Behaviour |
|------|-----------|
| `manual` (default, the SAP default) | The payment is authorized at checkout. The order process captures it (`takePaymentAction`). |
| `automatic` | Stripe captures at once. The order process finds the payment captured. |

- Only the methods in `stripe.capture.manual.methods` use manual capture. In manual mode the PaymentIntent is created with automatic capture, and only these methods get `capture_method=manual`. Methods without manual capture (for example Bancontact) are always captured at once.
- If Stripe does not know the manual capture option of a method in the list (`parameter_unknown` on `payment_method_options[<method>]`), the PaymentIntent is created again without that option and a WARN names the method. Mondu is not in the default list for this reason.
- **A card authorization expires after 7 days at Stripe.** Ship and capture before that, or the money is released.

## After purchase

The connector wraps the standard SAP order process. It works with OMS (`yacceleratorordermanagement`) and with the classic fulfilment process (`yacceleratorfulfilmentprocess`).

- **Capture.** `takePaymentAction` captures the current order total. If the order changed, the total is lower than the authorized amount, and Stripe releases the rest.
- **Partial capture.** The same action captures a part of the authorized amount.
- **Cancel.** The cancel strategy reads the live PaymentIntent, not only the SAP entries:
  - authorized and the new total is 0: the authorization is voided;
  - authorized and the new total is above 0: nothing happens now, and the capture takes the current total;
  - captured: the connector refunds the difference between the net captured amount and the new total.
- **Full cancel** (order cancelled, or every quantity 0) releases everything, including the delivery cost. A partial cancel keeps the delivery cost.
- **Retries.** If Stripe fails or does not accept a refund or void, a retry task runs the same idempotent adjustment again with the webhook backoff (30 s, 2 min, 8 min, 30 min). After 5 attempts it stops and logs an ERROR.
- **Return in the classic fulfilment process.** The return action of `yacceleratorfulfilmentprocess` is a SAP mock. **The customer must replace it with an action that calls `paymentService.refundFollowOn`.** The connector then refunds at Stripe. OMS returns already do this.

## Webhooks

- **Endpoint:** `POST /occ/v2/{baseSiteId}/payments/stripe/webhook`.
- **Signature:** the connector checks the `Stripe-Signature` header with `stripe.webhook.secret`. A bad or missing signature gives 400. A signed payload without an id is also refused.
- **Idempotent intake:** the event is stored once (`StripeWebhookEvent`, unique by Stripe event id). A repeated event does not run twice.
- **Processing:** the intake saves the event and schedules a SAP `Task` in one transaction. The task runner (`stripeWebhookEventTaskRunner`) processes it.
- **Events:** `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.processing`, `payment_intent.requires_action`, `payment_intent.partially_funded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`, `refund.updated`. Other events are ignored.
- **Retries:** a failure that can pass is retried after 30 s, 2 min, 8 min, and 30 min. After 5 attempts the event is `FAILED` with `lastError`, and an ERROR is logged.
- **Recovery:** when Stripe sends a `FAILED` event again (a resend from the Stripe Dashboard or the Stripe CLI), the connector sets it to `RECEIVED`, clears `attempts` and `lastError`, and processes it again. See [Operations runbook](#operations-runbook).
- **Unreadable events** (the payload cannot be read) fail at once with no retry.
- **States:** `RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED`.

## Pending payments

Some methods confirm later (the PaymentIntent is `processing`). The order is created with the status `PAYMENT_PENDING`. The first action of the order process waits:

- payment accepted: the process goes on;
- payment failed, cancelled, or refunded: the order is cancelled;
- still pending: the process checks again after `stripe.payment.pending.retry.seconds`;
- still pending after `stripe.payment.pending.timeout.seconds`: the order is cancelled.

Each check reads the PaymentIntent from Stripe. When it is authorized or captured with all its money, the order is authorized and the process goes on, also when the webhook event was lost. When Stripe does not answer, the order is never cancelled: the process checks again later.

The storefront shows the label `paymentPending` for this status (the connector adds it to `acceleratorStatusDisplayMap` when that bean exists).

## OCC endpoints

Base path: `/occ/v2/{baseSiteId}`.

| Method and path | Use |
|-----------------|-----|
| `POST /users/{userId}/carts/{cartId}/payments/stripe/intent` | Creates or updates the PaymentIntent of the cart. Returns `StripeIntent`: `paymentIntentId`, `clientSecret`, `status`. Status 201. |
| `GET /payments/stripe/config` | Returns `StripeConfig`: `publishableKey` of the base site. The key is public. |
| `POST /payments/stripe/webhook` | Stripe webhook. Returns `{status}` (the receipt) with 200. |
| SAP `POST /users/{userId}/orders?cartId=` | Places the order. The connector adds the Stripe checks. |

Carts and orders (all order DTOs) have a new field `stripePaymentInfo`: `paymentMethodType`, `cardBrand`, `cardLast4`, `cardExpMonth`, `cardExpYear`, `paymentStatus` (`ACCEPTED`, `PENDING`, `FAILED`, empty before a result).

### Error codes

The response is an SAP error list. The error has `type` `StripePaymentError` and `reason` set to one of these codes. The message is generic. The detail goes to the log.

| Code | HTTP | Meaning |
|------|------|---------|
| `STRIPE_AMOUNT_MISMATCH` | 400 | The cart total changed. Check the cart and pay again. |
| `STRIPE_PAYMENT_FAILED` | 400 | The payment was not completed (failed, cancelled, or needs action). |
| `STRIPE_ALREADY_PLACED` | 400 | An order for this payment already exists or is in progress. |
| `STRIPE_PAYMENT_IN_PROGRESS` | 400 | The payment is processing. Wait for the result. |
| `STRIPE_UNAVAILABLE` | 503 | Stripe did not answer, or SAP could not finish the authorization. The payment is kept. Try again later. |

## How the connector hooks into SAP

The connector does not replace SAP classes with one alias. It adds to them.

- **Authorization:** the alias `commercePaymentAuthorizationStrategy` points to `stripeCommercePaymentAuthorizationStrategy`. It handles Stripe carts and gives other carts to the SAP strategy.
- **Checkout facades:** `StripeCheckoutFacade` and `StripeAcceleratorCheckoutFacade` are child beans of `defaultCheckoutFacade` and `defaultAcceleratorCheckoutFacade`. They take the aliases `checkoutFacade` and `acceleratorCheckoutFacade`. Both use the same `stripeCheckoutAuthorizer` and `stripePlaceOrderGuard`.
- **Order process:** `StripeProcessActionsPostProcessor` finds the beans by name or alias and replaces each with a type-preserving CGLIB proxy. Other beans can still inject them by their class. The beans are:
  - `checkOrderAction`: Stripe orders go to `stripeAwaitPaymentCheckOrderAction`;
  - `takePaymentAction`: Stripe orders go to `stripeTakePaymentAction`;
  - `OmsOrderCancelPaymentServiceAdapter`: Stripe orders go to `stripeOrderCancelPaymentServiceAdapter`.

  Orders that do not use Stripe go to the original bean. A bean that does not exist is not changed. A final class is replaced by the Stripe bean, with a warning.
- **Cancel strategy:** the alias `orderCancelPaymentServiceStrategy` points to `stripeOrderCancelPaymentServiceStrategy`.
- **Place-order validator:** the SAP validator rejects a cart with no card data (`cart.paymentInfoNotSet`). `StripePlaceOrderCartValidator` is advice on a class proxy of that bean. It drops that one error for a cart with a Stripe PaymentIntent. All other checks stay.
- **Item types:** `StripePaymentInfo`, `StripePaymentIntentRecord`, `StripeOrderPlacementLock`, `StripeWebhookEvent`, and the order status `PAYMENT_PENDING`.

# Spartacus Frontend

The connector supports Spartacus storefronts through OCC APIs. The library is in `js-storefront/stripestore/integrations-libs/stripe/` (version 3.0.0). The library reads the publishable key from the backend (`GET payments/stripe/config`). `StripePaymentConfig` (`stripePayment.publishableKey`) is optional. Set it only to override the backend key.

The library shows the stable error codes as messages, blocks double submit, and shows a pending state for `STRIPE_PAYMENT_IN_PROGRESS`. For `STRIPE_ALREADY_PLACED` it goes to the order history.

Just before it confirms, the library syncs the PaymentIntent with the cart. When the PaymentIntent is the same, the Payment Element fetches the new amount and the payment is confirmed. When the cart got a new PaymentIntent (the old one was released), the library does not confirm the old one: it mounts the Payment Element of the new one, shows `stripePayment.amountChanged`, and the customer pays again.

# Reference project

## 1. Platform

The platform lives outside the repository. Example with the SAP Commerce zip in `/path/to/platform`:

```bash
# Download the official Spartacus sample data (checks the SHA-256 of the release asset)
core-customize/scripts/fetch-spartacussampledata.sh
unzip CXCOMCL221100U_55-*.ZIP -d /path/to/platform
ln -s "$PWD/core-customize/hybris/custom" /path/to/platform/hybris/bin/custom
# Gradle does not follow a symlinked recipe: copy it before every installer run
rsync -a --delete core-customize/installer/recipes/b2c_occ_stripe/ /path/to/platform/installer/recipes/b2c_occ_stripe/
```

The recipe installs the order management (OMS) extensions, so orders use the OMS order process (`order-process`).

## 2. Stripe keys

Put the test keys in a `.env` file in the repository root. Git ignores it.

```bash
STRIPE_SECRET_KEY=sk_test_...
STRIPE_PUBLISHABLE_KEY=pk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...
```

## 3. Build, initialize and start

Use Java 17.

```bash
set -a; . ./.env; set +a
cd /path/to/platform/installer
./install.sh -r b2c_occ_stripe setup -A initAdminPassword=<password>
./install.sh -r b2c_occ_stripe initialize -A initAdminPassword=<password>
./install.sh -r b2c_occ_stripe start
```

## 4. Stripe CLI and webhooks

Install the [Stripe CLI](https://docs.stripe.com/stripe-cli). In a second terminal:

```bash
core-customize/scripts/stripe-listen.sh
```

The script reads `STRIPE_SECRET_KEY` from the environment or from `.env`. It gives the key to the CLI in `STRIPE_API_KEY`, never on the command line, so `ps` does not show it. It prints `STRIPE_WEBHOOK_SECRET=whsec_...` first. Put that value in `.env` before you run `setup`, or set `stripe.webhook.secret` in `local.properties`. It forwards events to `https://localhost:9002/occ/v2/electronics-spa/payments/stripe/webhook`. Set `STRIPE_FORWARD_TO` to change the target.

## 5. Storefront

The `@spartacus/*` packages come from the SAP npm repository. Configure access in `js-storefront/stripestore/.npmrc` (git ignores it).

```bash
cd js-storefront/stripestore
npm ci
npm start
```

The storefront gets the publishable key from the backend, so the front end needs no key. It is at `http://localhost:4200/electronics-spa/en/EUR`.

## stripesampledata

The extension is for the reference project only. It contains:

- `store.impex`: `paymentProvider=Stripe` on the `electronics` BaseStore, the currency EUR added to the store, and `submitOrderProcessCode=order-process` (the OMS process).
- `prices.impex`: EUR prices for the electronics products (same value as the USD price), so the EUR site can sell them. The reference site uses EUR.
- `warehouses.impex`: OMS sourcing data: `Default_Formula`, `SourcingConfig` `Hybris_OMS_Config`, and the delivery modes of the warehouses. Without it OMS cannot source an order and suspends it.
- `oauth-clients.impex`: the demo OAuth client `mobile_android` (secret `secret`). See the warning above.

## 6. Tests

### End-to-end battery (Playwright)

Prerequisites:

- the server is running, with Stripe test keys;
- the storefront is running (`npm start`);
- `core-customize/scripts/stripe-listen.sh` is running (the tests stop at once if it is not);
- Chromium: `npx playwright install chromium`;
- the HAC user `admin` with its password (`E2E_HAC_USER`, `E2E_HAC_PASSWORD`; default `admin` / `nimda`). The tests use the HAC for FlexibleSearch and for back-office steps (ship, cancel, return).

```bash
cd js-storefront/stripestore
npm run e2e
```

The global setup (`e2e/global-setup.ts`) sets short timings on the running server through the HAC: `stripe.webhook.graceperiod.seconds=20`, `stripe.payment.pending.retry.seconds=10`, `stripe.capture.mode=manual`. A server restart reads `local.properties` again.

The battery has the projects `checkout`, `after-purchase`, and `webhooks`. Run one with `npx playwright test --project=checkout`.

Variables: `E2E_BASE_URL` (default `http://localhost:4200`), `E2E_OCC_URL` (default `https://localhost:9002`), `E2E_PRODUCT_PATH` (default `product/816324/DSC-T20-Cyber-shot-Silver`, a product in stock).

### Unit and integration tests

From `hybris/bin/platform`, after `. ./setantenv.sh` with Java 17:

```bash
ant all
ant unittests -Dtestclasses.extensions=stripecore,stripefacades,stripeocc -Dtestclasses.annotations=unittests
ant yunitinit
ant integrationtests -Dtestclasses.extensions=stripecore,stripefacades,stripeocc
```

Use `ant clean all` after a failed build. The Spartacus library tests run with `ng test` in `js-storefront/stripestore`.

# Operations runbook

Do these checks every day, and after an outage of Stripe or of the shop. Run the queries in the HAC (Console, FlexibleSearch).

## Find failed webhook events

```sql
SELECT {e.eventId}, {e.type}, {e.attempts}, {e.lastError}, {e.modifiedtime}
FROM {StripeWebhookEvent AS e JOIN StripeWebhookEventState AS s ON {e.state} = {s.pk}}
WHERE {s.code} = 'FAILED'
ORDER BY {e.modifiedtime} DESC
```

## Find PaymentIntents without an order (orphans)

```sql
SELECT {r.paymentIntentId}, {r.cartCode}, {r.baseSiteUid}, {s.code}, {r.amount}, {r.currencyIso}, {r.creationtime}
FROM {StripePaymentIntentRecord AS r JOIN StripePaymentIntentState AS s ON {r.state} = {s.pk}}
WHERE {r.order} IS NULL AND {s.code} NOT IN ('CANCELED', 'REFUNDED', 'FAILED')
ORDER BY {r.creationtime} DESC
```

A record in `CREATED` state can hold money when its confirmation event failed. Ignore the records that are newer than the grace period (`stripe.webhook.graceperiod.seconds`). For each other record, open the PaymentIntent in the Stripe Dashboard. When its status is `requires_capture`, `succeeded`, or `processing`, Stripe holds money without an SAP order.

## Queue the work again

Send the event again from Stripe. Do not change the tables by hand.

- **Failed event:** in the Stripe Dashboard, open the event (Developers, Events) and click **Resend** for the shop's webhook endpoint. With the Stripe CLI: `stripe events resend <eventId> --webhook-endpoint=<we_...>`. Give the secret key to the CLI in `STRIPE_API_KEY`, never on the command line.
- **Orphan PaymentIntent:** resend its latest `payment_intent.succeeded` or `payment_intent.amount_capturable_updated` event the same way.

The connector sets the event to `RECEIVED` and processes it again. For a paid PaymentIntent without an order, it places the order from the cart, or gives the money back when the cart is gone or its total changed. Run the queries again: the event must be `PROCESSED` or `IGNORED`, and the record must have an order or the state `CANCELED` or `REFUNDED`. If an event fails again, read `lastError` and the ERROR lines of the log for that PaymentIntent.

# Known limitations

- `PAYMENT_PENDING` cannot be tested end to end with a Stripe test account in Spain: no method stays in `processing`. Integration and unit tests cover the retry path.
- There is no reconciliation job for webhook events in `FAILED` state, or for PaymentIntents that have no order (orphans). Follow the [Operations runbook](#operations-runbook).
- A retry of a cancel adjustment can replay a Stripe failure that Stripe saved for the same idempotency key. Stripe keeps keys for 24 hours.
- HSQLDB has no row locks. The locks on the PaymentIntent record and on the webhook event do nothing there. Use a real database (not HSQLDB) to test concurrency.
- A card authorization expires after 7 days.

# Support

For questions, issues, or feature requests, please open an issue on [GitHub Issues](../../issues).

# Contribution

## Our Pledge

In the interest of fostering an open and welcoming environment, we as contributors and maintainers pledge to making participation in our project and our community a harassment-free experience for everyone, regardless of age, body size, disability, ethnicity, sex characteristics, gender identity and expression, level of experience, education, socio-economic status, nationality, personal appearance, race, religion, or sexual identity and orientation.

## Our Standards

Examples of behavior that contributes to creating a positive environment include:

* Using welcoming and inclusive language
* Being respectful of differing viewpoints and experiences
* Gracefully accepting constructive criticism
* Focusing on what is best for the community
* Showing empathy towards other community members

Examples of unacceptable behavior by participants include:

* The use of sexualized language or imagery and unwelcome sexual attention or advances
* Trolling, insulting/derogatory comments, and personal or political attacks
* Public or private harassment
* Publishing others' private information, such as a physical or electronic address, without explicit permission
* Other conduct which could reasonably be considered inappropriate in a professional setting

## Our Responsibilities

Project maintainers are responsible for clarifying the standards of acceptable behavior and are expected to take appropriate and fair corrective action in response to any instances of unacceptable behavior.

Project maintainers have the right and responsibility to remove, edit, or reject comments, commits, code, wiki edits, issues, and other contributions that are not aligned to this Code of Conduct, or to ban temporarily or permanently any contributor for other behaviors that they deem inappropriate, threatening, offensive, or harmful.

## Scope

This Code of Conduct applies both within project spaces and in public spaces when an individual is representing the project or its community.

## Attribution

This Code of Conduct is adapted from the [Contributor Covenant](https://www.contributor-covenant.org), version 1.4.

# License

This repository is open source and available under the MIT license.

Copyright (c) 2025 Sergio Perez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
