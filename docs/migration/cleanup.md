# Breaking changes in the cleanup

These changes come from the connector cleanup (October 2026). They can break an installation of the earlier published version.

## Backend

- **`stripestore` is removed.** It was a copy of the electronics sample store. Remove `stripestore` from `localextensions.xml`. Use your own store and set `paymentProvider=Stripe` on its BaseStore.
- **No dependency on SAP Open Payment Framework (OPF).** The connector used `TransactionStatus.WAITING`, which the OPF extension `opfservices` adds. It now uses the standard `TransactionStatus.REVIEW`. Pending payments that the connector stored as `WAITING` keep that value in the database.
- **The Stripe Java SDK jar is not in `stripecore/lib` any more.** Maven downloads it at build time (`external-dependencies.xml`, `usemaven=true`). The build needs access to Maven Central.
- **`stripeocc` template code is removed:** `StripeoccController` and the `StripeConfirmRequest` DTO.
- **IDE helper steps are removed from `buildcallbacks.xml`.** The build no longer creates or edits `.classpath` files.

## Configuration

- The reference recipe now uses the property names that the connector reads: `stripe.secret.key` and `stripe.publishable.key`. The earlier recipe and README used `stripe.api.secret.key` and `stripe.api.public.key`, which the connector never read. Rename them in your `local.properties`.

## Storefront

- **`@spartacus/opf` is removed** from the reference app, together with `OpfFeatureModule`, `OpfOrderModule` from `@spartacus/opf/order`, and the `opf.scss` styles. If your app imported `OpfOrderModule` only for the Stripe connector, remove it and keep `OrderModule` from `@spartacus/order`.
- **The `stripeConfirm` OCC endpoint is removed** from the library config (`defaultStripeOccConfig`) and from the `OccEndpoints` augmentation. It had no backend.
- **The publishable key is not in the code any more.** The backend serves it (`GET payments/stripe/config`), and `StripePaymentConfig` is optional.
