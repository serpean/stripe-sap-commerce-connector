import { HttpErrorResponse } from '@angular/common/http';
import { HttpErrorModel, normalizeHttpError } from '@spartacus/core';
import { getStripeErrorReason } from './stripe-payment-error';

describe('getStripeErrorReason', () => {
  const stripeErrors = {
    errors: [{ type: 'StripePaymentError', reason: 'STRIPE_PAYMENT_FAILED' }],
  };

  it('reads the reason of a raw OCC error response', () => {
    const error = new HttpErrorResponse({ status: 400, error: stripeErrors });

    expect(getStripeErrorReason(error)).toBe('STRIPE_PAYMENT_FAILED');
  });

  it('reads the reason of an error that Spartacus normalized (place order)', () => {
    const error = normalizeHttpError(
      new HttpErrorResponse({ status: 400, error: stripeErrors }),
      { error: () => {} } as never
    );

    expect(error instanceof HttpErrorModel).toBeTrue();
    expect(getStripeErrorReason(error)).toBe('STRIPE_PAYMENT_FAILED');
  });

  it('gives undefined for another error type', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { errors: [{ type: 'PaymentAuthorizationError' }] },
    });

    expect(getStripeErrorReason(error)).toBeUndefined();
    expect(getStripeErrorReason(undefined)).toBeUndefined();
  });
});
