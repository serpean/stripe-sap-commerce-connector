import { defaultStripeCheckoutConfig } from './stripe-checkout.config';

describe('defaultStripeCheckoutConfig', () => {
  const flows = defaultStripeCheckoutConfig.checkout?.flows ?? {};

  it('has a three-step flow for paymentProvider=Stripe, without a separate review step', () => {
    const steps = flows['Stripe']?.steps ?? [];

    expect(steps.map((step) => step.id)).toEqual([
      'deliveryAddress',
      'deliveryMode',
      'stripePaymentReview',
    ]);
    // The payment step also covers the review: the Stripe form places the order.
    expect(steps[2].type as string[]).toEqual(['paymentDetails', 'reviewOrder']);
  });

  it('keeps STRIPE as an alias with the same steps', () => {
    expect(flows['STRIPE']?.steps).toBe(flows['Stripe']?.steps);
  });
});
