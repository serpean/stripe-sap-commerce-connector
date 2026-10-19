# Stripe Connector for SAP Commerce Cloud

Stripe provides payment infrastructure for the internet. This Connector for SAP Commerce Cloud enables a seamless integration with Stripe's Payment Intents API, supporting secure card payments with built-in 3D Secure (SCA) compliance.

## Release Compatibility

This release is compatible with:
- SAP Commerce Cloud 2211
- SAP Commerce REST API (OCC)
- Spartacus 2211.37
- Java 17
- Stripe Java SDK

## Features

- **Card Payments**: Accept credit and debit card payments
- **Payment Intents API**: Modern payment flow with built-in SCA/3DS support
- **Webhooks**: Process Stripe events for payment confirmations and updates
- **Headless Architecture**: OCC-first design for Spartacus storefronts

## Project Structure

The repository holds two things. Only the connector is published.

### Connector (published)

| Path | Content |
|------|---------|
| `core-customize/hybris/custom/stripe/stripecore` | Core services, Stripe API client, payment commands |
| `core-customize/hybris/custom/stripe/stripefacades` | Facade layer with DTOs and converters |
| `core-customize/hybris/custom/stripe/stripeocc` | OCC REST endpoints for payment operations |
| `core-customize/hybris/custom/stripe/stripefulfilmentprocess` | Order process actions for payment authorization and capture |
| `js-storefront/stripestore/integrations-libs/stripe` | Spartacus library `sap-commerce-stripe-payment` |

### Reference project (not published)

Use it for demos and to run the end-to-end tests. Do not copy it into a customer project.

| Path | Content |
|------|---------|
| `core-customize/installer/recipes/b2c_occ_stripe` | Installer recipe |
| `core-customize/hybris/custom/spartacus/spartacussampledata` | Official SAP Spartacus sample data (`electronics-spa`) |
| `core-customize/hybris/custom/reference/stripesampledata` | Sets `paymentProvider=Stripe` on the `electronics` BaseStore |
| `core-customize/manifest.json`, `js-storefront/manifest.json` | CCv2 manifests |
| `js-storefront/stripestore/src` | Spartacus demo app |
| `js-storefront/stripestore/e2e` | Playwright end-to-end tests |

Breaking changes are listed in [docs/migration/cleanup.md](docs/migration/cleanup.md).

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
<extension name="stripefulfilmentprocess"/>
```

3. Set `paymentProvider=Stripe` on the BaseStore that uses Stripe.

4. Build. The Stripe Java SDK is downloaded by Maven (`usemaven=true`).

```bash
cd hybris/bin/platform
. ./setantenv.sh
ant clean all
```

5. Start the server and update the system:

```bash
./hybrisserver.sh
ant updatesystem
```

## Configuration

Add your Stripe settings to `local.properties`. Never store real keys in git.

```properties
stripe.secret.key=sk_test_your_secret_key
stripe.publishable.key=pk_test_your_public_key
stripe.webhook.secret=whsec_your_webhook_secret
stripe.capture.mode=manual
stripe.statement.descriptor=MYSHOP
stripe.descriptor.suffix=ECOM
stripe.capture.manual.methods=card,paypal,klarna
```

## Installing on SAP Commerce Cloud

Follow the [SAP Commerce Cloud documentation](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD) to prepare your repository for deployment.

1. Include the Stripe extensions in the `core-customize` folder
2. Update your `manifest.json` to include the extensions
3. Add the Stripe keys as environment properties in SAP Commerce Cloud Portal

# Spartacus Frontend

The connector supports Spartacus storefronts through OCC APIs. The library is in `js-storefront/stripestore/integrations-libs/stripe/`. Your app gives the library the Stripe publishable key through `StripePaymentConfig` (`stripePayment.publishableKey`).

# Reference project

## 1. Platform

The platform lives outside the repository. Example with the SAP Commerce zip in `/path/to/platform`:

```bash
unzip CXCOMCL221100U_55-*.ZIP -d /path/to/platform
ln -s "$PWD/core-customize/hybris/custom" /path/to/platform/hybris/bin/custom
# Gradle does not follow a symlinked recipe: copy it before every installer run
rsync -a --delete core-customize/installer/recipes/b2c_occ_stripe/ /path/to/platform/installer/recipes/b2c_occ_stripe/
```

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

## 4. Storefront

The `@spartacus/*` packages come from the SAP npm repository. Configure access in `js-storefront/stripestore/.npmrc` (git ignores it).

```bash
set -a; . ./.env; set +a
cd js-storefront/stripestore
npm ci
npm start
```

`npm start` writes the publishable key from `STRIPE_PUBLISHABLE_KEY` into a git-ignored file. The storefront is at `http://localhost:4200/electronics-spa/en/USD`.

## 5. End-to-end tests

With the server and the storefront running:

```bash
cd js-storefront/stripestore
npx playwright install chromium
npm run e2e
```

Variables: `E2E_BASE_URL` (default `http://localhost:4200`), `E2E_OCC_URL` (default `https://localhost:9002`), `E2E_PRODUCT_PATH` (default `product/816324/DSC-T20-Cyber-shot-Silver`, a product in stock).

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
