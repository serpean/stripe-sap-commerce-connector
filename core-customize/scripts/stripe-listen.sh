#!/usr/bin/env bash
# Forward Stripe webhook events to the local reference platform.
# Prints the whsec_ signing secret first: export it as STRIPE_WEBHOOK_SECRET
# before "install.sh ... setup" (or set stripe.webhook.secret in local.properties).
# Needs STRIPE_SECRET_KEY in the environment (or in ../.env). The key is never printed and never put on the
# command line: the Stripe CLI reads it from STRIPE_API_KEY, so other local users cannot see it with "ps".
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
key="${STRIPE_SECRET_KEY:-}"
if [ -z "$key" ] && [ -f "$root/.env" ]; then
  # Read only this variable from .env; the other entries stay out of the environment.
  key="$(sed -n -E 's/^[[:space:]]*(export[[:space:]]+)?STRIPE_SECRET_KEY=//p' "$root/.env" | tail -n 1)"
  key="${key%\"}"; key="${key#\"}"; key="${key%\'}"; key="${key#\'}"
fi
: "${key:?Set STRIPE_SECRET_KEY (or add it to .env)}"
export STRIPE_API_KEY="$key"
unset key

forward_to="${STRIPE_FORWARD_TO:-https://localhost:9002/occ/v2/electronics-spa/payments/stripe/webhook}"

secret="$(stripe listen --print-secret)"
echo "STRIPE_WEBHOOK_SECRET=$secret"

exec stripe listen --forward-to "$forward_to" --skip-verify
