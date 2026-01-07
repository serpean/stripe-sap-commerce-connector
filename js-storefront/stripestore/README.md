# Stripe Storefront

Spartacus-based storefront with custom Stripe payment integration for SAP Commerce Cloud.

## Tech Stack

- **Angular** 19.2
- **Spartacus** 2211.37
- **Stripe.js** 2.2
- **NgRx** 19
- **SSR** enabled with `@angular/ssr`

## Project Structure

```
stripestore/
├── src/
│   └── app/
│       └── spartacus/           # Spartacus configuration
├── integrations-libs/
│   └── stripe/
│       ├── core/                # Services and adapters
│       │   └── services/
│       │       ├── stripe-payment.service.ts
│       │       └── stripe-payment-occ.service.ts
│       ├── components/          # UI components
│       ├── root/                # Root module and config
│       └── stripe-payment.module.ts
└── dist/                        # Build output
```

## Prerequisites

- Node.js 18+
- npm 9+
- SAP Commerce backend running at `https://localhost:9002`

## Installation

```bash
npm install
```

## Development

```bash
# Start development server
npm start

# Start with SSR
npm run build:ssr && npm run serve:ssr:stripestore
```

Open [http://localhost:4200](http://localhost:4200) in your browser.

## Build

```bash
# Development build
npm run build

# Production build with SSR
npm run build:ssr
```

Build artifacts are stored in `dist/stripestore/`.

## Testing

```bash
# Run unit tests
npm test

# Run tests for specific files
ng test --include='**/stripe*'
```

## Configuration

### OCC Backend

Configure the backend URL in `src/app/spartacus/spartacus-configuration.module.ts`:

```typescript
provideConfig(<OccConfig>{
  backend: {
    occ: {
      baseUrl: 'https://localhost:9002',
    },
  },
})
```

### Stripe Public Key

Configure in `integrations-libs/stripe/root/config/stripe-payment-config.ts`:

```typescript
export const stripePaymentConfig: StripePaymentConfig = {
  publicKey: 'pk_test_your_public_key',
};
```

## Stripe Integration

The Stripe integration library (`integrations-libs/stripe/`) provides:

| Module | Description |
|--------|-------------|
| `core/services/stripe-payment.service.ts` | Stripe.js initialization, Payment Element handling |
| `core/services/stripe-payment-occ.service.ts` | OCC adapter for backend communication |
| `components/` | Payment form UI components |
| `root/` | Module configuration and providers |

### Checkout Flow

Custom checkout flow with Stripe:
1. `deliveryAddress` - Shipping address
2. `deliveryMode` - Shipping method
3. `stripePaymentReview` - Payment + Order review

## Useful Commands

| Command | Description |
|---------|-------------|
| `npm start` | Start dev server |
| `npm run build` | Build for production |
| `npm run build:ssr` | Build with SSR |
| `npm run serve:ssr:stripestore` | Serve SSR build |
| `npm test` | Run unit tests |
| `ng generate component <name>` | Generate component |

## Additional Resources

- [Spartacus Documentation](https://sap.github.io/spartacus-docs/)
- [Stripe.js Reference](https://stripe.com/docs/js)
- [Angular CLI](https://angular.dev/tools/cli)
