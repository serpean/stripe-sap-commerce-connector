/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { StripePaymentConfig } from './stripe-payment.config';

export const defaultStripePaymentConfig: StripePaymentConfig = {
  stripePayment: {
    publishableKey: '',
    appearance: {
      theme: 'stripe',
    },
  },
};
