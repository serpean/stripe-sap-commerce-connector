/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { StripePaymentInfo } from 'sap-commerce-stripe-payment/root';

declare module '@spartacus/order/root' {
  interface Order {
    stripePaymentInfo?: StripePaymentInfo;
  }
}

declare module '@spartacus/cart/base/root' {
  interface Cart {
    stripePaymentInfo?: StripePaymentInfo;
  }
}
