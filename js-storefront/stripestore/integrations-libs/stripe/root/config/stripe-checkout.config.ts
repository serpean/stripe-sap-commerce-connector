/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  CheckoutConfig,
  CheckoutStep,
  CheckoutStepType,
} from '@spartacus/checkout/base/root';

/**
 * Three steps: the payment step also places the order, so there is no separate review step.
 */
const stripeCheckoutSteps: CheckoutStep[] = [
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
];

/**
 * Spartacus picks the flow by the base store's paymentProvider, case-sensitive. The connector uses 'Stripe'
 * everywhere (base store, payment transactions, command factory).
 */
export const defaultStripeCheckoutConfig: CheckoutConfig = {
  checkout: {
    flows: {
      Stripe: { steps: stripeCheckoutSteps },
    },
  },
};
