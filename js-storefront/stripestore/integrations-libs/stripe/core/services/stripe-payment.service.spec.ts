import { TestBed } from '@angular/core/testing';
import { GlobalMessageService, WindowRef } from '@spartacus/core';
import { OrderFacade } from '@spartacus/order/root';
import { Stripe, StripeElements } from '@stripe/stripe-js';
import { of } from 'rxjs';
import { StripePaymentConfig } from 'sap-commerce-stripe-payment/root';
import { StripePaymentOccService } from './stripe-payment-occ.service';
import { StripePaymentService } from './stripe-payment.service';

describe('StripePaymentService', () => {
  let occService: jasmine.SpyObj<StripePaymentOccService>;
  let config: StripePaymentConfig;

  function createService(): StripePaymentService {
    TestBed.configureTestingModule({
      providers: [
        StripePaymentService,
        { provide: StripePaymentConfig, useValue: config },
        { provide: StripePaymentOccService, useValue: occService },
        { provide: OrderFacade, useValue: { placeOrder: () => of({}) } },
        { provide: GlobalMessageService, useValue: { add: () => {} } },
        {
          provide: WindowRef,
          useValue: { location: { href: 'https://shop/checkout' } },
        },
      ],
    });
    return TestBed.inject(StripePaymentService);
  }

  beforeEach(() => {
    occService = jasmine.createSpyObj('StripePaymentOccService', [
      'ensureIntent',
      'getConfig',
    ]);
    config = { stripePayment: { publishableKey: '' } };
  });

  describe('getStripe', () => {
    it('uses publishable key from backend when not configured', (done) => {
      occService.getConfig.and.returnValue(of({ publishableKey: 'pk_backend' }));
      const service = createService();
      const stripe = {} as Stripe;
      const load = spyOn<any>(service, 'loadStripeJs').and.resolveTo(stripe);

      service.getStripe().subscribe((result) => {
        expect(load).toHaveBeenCalledWith('pk_backend');
        expect(result).toBe(stripe);
        done();
      });
    });

    it('uses the configured key without calling the backend', (done) => {
      config.stripePayment = { publishableKey: 'pk_config' };
      const service = createService();
      const load = spyOn<any>(service, 'loadStripeJs').and.resolveTo({} as Stripe);

      service.getStripe().subscribe(() => {
        expect(load).toHaveBeenCalledWith('pk_config');
        expect(occService.getConfig).not.toHaveBeenCalled();
        done();
      });
    });

    it('returns null when the backend sends no key', (done) => {
      occService.getConfig.and.returnValue(of({ publishableKey: '' }));
      const service = createService();
      const load = spyOn<any>(service, 'loadStripeJs');

      service.getStripe().subscribe((result) => {
        expect(result).toBeNull();
        expect(load).not.toHaveBeenCalled();
        done();
      });
    });
  });

  describe('confirmPayment', () => {
    let calls: string[];
    let stripe: jasmine.SpyObj<Stripe>;
    let elements: jasmine.SpyObj<StripeElements>;

    beforeEach(() => {
      calls = [];
      stripe = jasmine.createSpyObj('Stripe', ['confirmPayment']);
      stripe.confirmPayment.and.callFake((() => {
        calls.push('confirm');
        return Promise.resolve({
          paymentIntent: { id: 'pi_1', status: 'succeeded', client_secret: 'cs_1' },
        });
      }) as any);
      elements = jasmine.createSpyObj('StripeElements', ['fetchUpdates']);
      elements.fetchUpdates.and.callFake((() => {
        calls.push('fetchUpdates');
        return Promise.resolve({});
      }) as any);
    });

    it('syncs intent before confirming', (done) => {
      occService.ensureIntent.and.callFake(() => {
        calls.push('ensureIntent');
        return of({ paymentIntentId: 'pi_1', clientSecret: 'cs_1' });
      });
      const service = createService();

      service.confirmPayment(stripe, elements, 'cs_1').subscribe((result) => {
        expect(calls).toEqual(['ensureIntent', 'confirm']);
        expect(result.intent?.paymentIntentId).toBe('pi_1');
        const args = stripe.confirmPayment.calls.mostRecent().args[0] as any;
        expect(args.redirect).toBe('if_required');
        expect(args.confirmParams.return_url).toBe('https://shop/checkout');
        done();
      });
    });

    it('fetches element updates when the client secret changed', (done) => {
      occService.ensureIntent.and.callFake(() => {
        calls.push('ensureIntent');
        return of({ paymentIntentId: 'pi_2', clientSecret: 'cs_2' });
      });
      const service = createService();

      service.confirmPayment(stripe, elements, 'cs_1').subscribe(() => {
        expect(calls).toEqual(['ensureIntent', 'fetchUpdates', 'confirm']);
        done();
      });
    });

    it('maps a Stripe card error to the translated paymentFailed key', (done) => {
      occService.ensureIntent.and.returnValue(
        of({ paymentIntentId: 'pi_1', clientSecret: 'cs_1' })
      );
      stripe.confirmPayment.and.resolveTo({
        error: { type: 'card_error', code: 'card_declined', message: 'Your card has been declined.' },
      } as any);
      const service = createService();

      service.confirmPayment(stripe, elements, 'cs_1').subscribe((result) => {
        expect(result.error).toBe('stripePayment.paymentFailed');
        done();
      });
    });

    it('does not confirm and reports genericError when fetchUpdates fails', (done) => {
      occService.ensureIntent.and.returnValue(
        of({ paymentIntentId: 'pi_2', clientSecret: 'cs_2' })
      );
      elements.fetchUpdates.and.resolveTo({ error: { message: 'boom' } } as any);
      const service = createService();

      service.confirmPayment(stripe, elements, 'cs_1').subscribe((result) => {
        expect(stripe.confirmPayment).not.toHaveBeenCalled();
        expect(result.error).toBe('stripePayment.genericError');
        done();
      });
    });
  });
});
