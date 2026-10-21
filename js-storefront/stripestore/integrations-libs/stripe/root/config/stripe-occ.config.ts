/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { OccConfig } from '@spartacus/core';
import '../model/stripe-payment.model';

export const defaultStripeOccConfig: OccConfig = {
  backend: {
    occ: {
      endpoints: {
        stripeIntent: 'users/${userId}/carts/${cartId}/payments/stripe/intent',
        stripeConfig: 'payments/stripe/config',
      },
    },
  },
};
