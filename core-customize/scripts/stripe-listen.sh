#!/usr/bin/env bash
# Forward Stripe webhook events to the local reference platform.
# Prints the whsec_ signing secret first: export it as STRIPE_WEBHOOK_SECRET
# before "install.sh ... setup" (or set stripe.webhook.secret in local.properties).
# Needs STRIPE_SECRET_KEY in the environment (or in ../.env). The key is never printed.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -z "${STRIPE_SECRET_KEY:-}" ] && [ -f "$root/.env" ]; then
  set -a
  . "$root/.env"
  set +a
fi
: "${STRIPE_SECRET_KEY:?Set STRIPE_SECRET_KEY (or add it to .env)}"

forward_to="${STRIPE_FORWARD_TO:-https://localhost:9002/occ/v2/electronics-spa/payments/stripe/webhook}"

secret="$(stripe listen --api-key "$STRIPE_SECRET_KEY" --print-secret)"
echo "STRIPE_WEBHOOK_SECRET=$secret"

exec stripe listen --api-key "$STRIPE_SECRET_KEY" --forward-to "$forward_to" --skip-verify
