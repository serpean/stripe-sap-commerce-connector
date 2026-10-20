/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { HttpErrorResponse } from '@angular/common/http';
import { HttpErrorModel } from '@spartacus/core';
import { StripePaymentErrorReason } from 'sap-commerce-stripe-payment/root';

/**
 * Reads the Stripe error reason from an OCC ErrorList response, raw
 * (HttpErrorResponse) or normalized by Spartacus (HttpErrorModel.details, for
 * example on place order).
 * Returns undefined when the error is not a StripePaymentError.
 */
export function getStripeErrorReason(
  error: unknown
): StripePaymentErrorReason | undefined {
  const errors =
    (error as HttpErrorResponse | undefined)?.error?.errors ??
    (error as HttpErrorModel | undefined)?.details;
  const first = Array.isArray(errors) ? errors[0] : undefined;
  return first?.type === 'StripePaymentError' ? first.reason : undefined;
}
