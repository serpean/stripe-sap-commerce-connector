/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

export interface StripeIntentResponse {
  intentId?: string;
  clientSecret?: string;
  status?: string;
}

export interface StripePaymentResult {
  intent?: StripeIntentResponse;
  error?: string;
}

// Module augmentation for Spartacus OccEndpoints
declare module '@spartacus/core' {
  interface OccEndpoints {
    stripeIntent?: string;
  }
}
