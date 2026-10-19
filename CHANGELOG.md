# Changelog

All notable changes to the Stripe connector for SAP Commerce Cloud are listed here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/).

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
