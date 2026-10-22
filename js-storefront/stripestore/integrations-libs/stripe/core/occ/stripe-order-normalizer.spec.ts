import { Order } from '@spartacus/order/root';
import { StripeOrderNormalizer } from './stripe-order-normalizer';

describe('StripeOrderNormalizer', () => {
  const normalizer = new StripeOrderNormalizer();

  it('normalizer maps card data to paymentInfo', () => {
    const source: Order = {
      code: '0001',
      stripePaymentInfo: {
        paymentMethodType: 'card',
        cardBrand: 'visa',
        cardLast4: '4242',
        cardExpMonth: 12,
        cardExpYear: 2030,
        paymentStatus: 'ACCEPTED',
      },
    };

    const result = normalizer.convert(source);

    expect(result.code).toBe('0001');
    expect(result.paymentInfo?.cardType?.name).toBe('visa');
    expect(result.paymentInfo?.cardNumber).toBe('************4242');
    expect(result.paymentInfo?.expiryMonth).toBe('12');
    expect(result.paymentInfo?.expiryYear).toBe('2030');
  });

  it('writes into the given target', () => {
    const target: Order = { code: '0002' };

    const result = normalizer.convert(
      { stripePaymentInfo: { cardBrand: 'mastercard', cardLast4: '4444' } },
      target
    );

    expect(result).toBe(target);
    expect(target.paymentInfo?.cardNumber).toBe('************4444');
  });

  it('leaves paymentInfo untouched when there is no card data', () => {
    const source: Order = {
      code: '0003',
      stripePaymentInfo: { paymentStatus: 'PENDING' },
    };

    const result = normalizer.convert(source);

    expect(result.paymentInfo).toBeUndefined();
  });

  it('keeps an order without Stripe data unchanged', () => {
    const result = normalizer.convert({ code: '0004' });

    expect(result).toEqual({ code: '0004' });
  });
});
