/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CheckoutConfig, CheckoutStepType } from '@spartacus/checkout/base/root';

export const defaultStripeCheckoutConfig: CheckoutConfig = {
  checkout: {
    flows: {
      STRIPE: {
        steps: [
          {
            id: 'deliveryAddress',
            name: 'checkoutProgress.deliveryAddress',
            routeName: 'checkoutDeliveryAddress',
            type: ['deliveryAddress' as CheckoutStepType],
          },
          {
            id: 'deliveryMode',
            name: 'checkoutProgress.deliveryMode',
            routeName: 'checkoutDeliveryMode',
            type: ['deliveryMode' as CheckoutStepType],
          },
          {
            id: 'stripePaymentReview',
            name: 'checkoutProgress.paymentDetails',
            routeName: 'checkoutPaymentDetails',
            type: [
              'paymentDetails' as CheckoutStepType,
              'reviewOrder' as CheckoutStepType,
            ],
          },
        ],
      },
    },
  },
};
