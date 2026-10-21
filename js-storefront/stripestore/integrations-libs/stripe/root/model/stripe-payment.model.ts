/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

export interface StripeIntentResponse {
  paymentIntentId?: string;
  clientSecret?: string;
  status?: string;
}

export interface StripePaymentResult {
  intent?: StripeIntentResponse;
  error?: string;
  /** The cart now has a new PaymentIntent: mount it and let the customer pay again. */
  newClientSecret?: string;
}

export interface StripeConfigResponse {
  publishableKey: string;
}

export type StripePaymentErrorReason =
  | 'STRIPE_AMOUNT_MISMATCH'
  | 'STRIPE_PAYMENT_FAILED'
  | 'STRIPE_ALREADY_PLACED'
  | 'STRIPE_UNAVAILABLE'
  | 'STRIPE_PAYMENT_IN_PROGRESS';

export type StripePaymentStatus = 'ACCEPTED' | 'PENDING' | 'FAILED';

export interface StripePaymentInfo {
  paymentMethodType?: string;
  cardBrand?: string;
  cardLast4?: string;
  cardExpMonth?: string;
  cardExpYear?: string;
  paymentStatus?: StripePaymentStatus;
}

// Module augmentation for Spartacus OccEndpoints
declare module '@spartacus/core' {
  interface OccEndpoints {
    stripeIntent?: string;
    stripeConfig?: string;
  }
}
