import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  NO_ERRORS_SCHEMA,
  Pipe,
  PipeTransform,
} from '@angular/core';
import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { CheckoutFlowOrchestratorService } from '@spartacus/checkout/base/components';
import {
  GlobalMessageService,
  GlobalMessageType,
  RoutingService,
  TranslationService,
} from '@spartacus/core';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { Stripe } from '@stripe/stripe-js';
import { Order } from '@spartacus/order/root';
import { Observable, Subject, mergeMap, of, throwError, timer } from 'rxjs';
import { StripePaymentService } from 'sap-commerce-stripe-payment/core';
import { StripePaymentFormComponent } from './stripe-payment-form.component';

@Pipe({ name: 'cxTranslate', standalone: false })
class StubTranslatePipe implements PipeTransform {
  transform(value: unknown): string {
    return String(value);
  }
}

function failLater(error: unknown): Observable<never> {
  return timer(0).pipe(mergeMap(() => throwError(() => error)));
}

function succeedLater<T>(value: T): Observable<T> {
  return timer(0).pipe(mergeMap(() => of(value)));
}

function stripeError(reason: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status: 400,
    error: { errors: [{ type: 'StripePaymentError', reason }] },
  });
}

describe('StripePaymentFormComponent', () => {
  let fixture: ComponentFixture<StripePaymentFormComponent>;
  let component: StripePaymentFormComponent;
  let stripeService: jasmine.SpyObj<StripePaymentService>;
  let routingService: jasmine.SpyObj<RoutingService>;
  let globalMessage: jasmine.SpyObj<GlobalMessageService>;
  let paymentElement: { mount: jasmine.Spy; destroy: jasmine.Spy };
  let fakeStripe: Stripe;

  function setup(
    queryParams: Record<string, string> = {},
    placeOrderResult: Observable<unknown> = of({}),
    stripe$: Observable<Stripe | null> | undefined = undefined,
    onPush = false,
    cartUid = 'ana@example.com'
  ): void {
    paymentElement = {
      mount: jasmine.createSpy('mount'),
      destroy: jasmine.createSpy('destroy'),
    };
    fakeStripe = {
      elements: () => ({ create: () => paymentElement }),
    } as unknown as Stripe;
    stripeService = jasmine.createSpyObj('StripePaymentService', [
      'getStripe',
      'ensureIntent',
      'confirmPayment',
      'placeOrder',
    ]);
    stripeService.getStripe.and.returnValue(stripe$ ?? of(fakeStripe));
    stripeService.ensureIntent.and.returnValue(
      of({ paymentIntentId: 'pi_1', clientSecret: 'cs_1' })
    );
    stripeService.placeOrder.and.returnValue(placeOrderResult as Observable<Order>);
    routingService = jasmine.createSpyObj('RoutingService', ['go']);
    globalMessage = jasmine.createSpyObj('GlobalMessageService', ['add']);

    TestBed.configureTestingModule({
      declarations: [StripePaymentFormComponent, StubTranslatePipe],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: StripePaymentService, useValue: stripeService },
        { provide: TranslationService, useValue: { translate: () => of('ok') } },
        { provide: RoutingService, useValue: routingService },
        { provide: GlobalMessageService, useValue: globalMessage },
        {
          provide: CheckoutFlowOrchestratorService,
          useValue: { getPaymentProvider: () => of('STRIPE') },
        },
        {
          provide: ActiveCartFacade,
          useValue: { getActive: () => of({ user: { uid: cartUid } }) },
        },
        { provide: ActivatedRoute, useValue: { queryParams: of(queryParams) } },
        { provide: Router, useValue: { navigate: jasmine.createSpy('navigate') } },
      ],
    });
    if (onPush) {
      TestBed.overrideComponent(StripePaymentFormComponent, {
        set: { changeDetection: ChangeDetectionStrategy.OnPush },
      });
    }
    fixture = TestBed.createComponent(StripePaymentFormComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  describe('after mounting', () => {
    beforeEach(() => setup());

    it('prepares the intent and mounts the Payment Element', () => {
      expect(stripeService.ensureIntent).toHaveBeenCalledTimes(1);
      expect(paymentElement.mount).toHaveBeenCalledTimes(1);
    });

    it('places the order after a successful confirmation', () => {
      stripeService.confirmPayment.and.returnValue(
        of({ intent: { paymentIntentId: 'pi_1', status: 'succeeded' } })
      );

      component.submit();

      expect(stripeService.confirmPayment).toHaveBeenCalled();
      expect(stripeService.placeOrder).toHaveBeenCalledTimes(1);
      expect(routingService.go).toHaveBeenCalledWith({
        cxRoute: 'orderConfirmation',
      });
    });

    it('sends the cart email as billing email', () => {
      stripeService.confirmPayment.and.returnValue(of({ placeOrder: true, intent: {} }));

      component.submit();

      expect(stripeService.confirmPayment.calls.mostRecent().args[3]).toBe(
        'ana@example.com'
      );
    });

    it('does not place the order when the service says the customer must still act', () => {
      stripeService.confirmPayment.and.returnValue(
        of({ placeOrder: false, error: 'stripePayment.genericError', intent: {} })
      );

      component.submit();

      expect(stripeService.placeOrder).not.toHaveBeenCalled();
      expect(component.error).toBe('stripePayment.genericError');
    });

    it('mounts the new PaymentIntent and lets the customer pay again when the client secret changed', () => {
      stripeService.confirmPayment.and.returnValue(
        of({ newClientSecret: 'cs_2', error: 'stripePayment.amountChanged' })
      );

      component.submit();

      expect(component.error).toBe('stripePayment.amountChanged');
      expect(paymentElement.mount).toHaveBeenCalledTimes(2);
      expect(stripeService.placeOrder).not.toHaveBeenCalled();
      expect(component.isLoading).toBeFalse();

      stripeService.confirmPayment.and.returnValue(
        of({ intent: { paymentIntentId: 'pi_2', status: 'succeeded' } })
      );
      component.submit();

      expect(stripeService.confirmPayment.calls.mostRecent().args[2]).toBe('cs_2');
      expect(stripeService.placeOrder).toHaveBeenCalledTimes(1);
    });

    it('maps STRIPE_AMOUNT_MISMATCH to amountChanged message', () => {
      stripeService.confirmPayment.and.returnValue(
        throwError(() => stripeError('STRIPE_AMOUNT_MISMATCH'))
      );

      component.submit();

      expect(component.error).toBe('stripePayment.amountChanged');
      expect(stripeService.placeOrder).not.toHaveBeenCalled();
      expect(stripeService.ensureIntent).toHaveBeenCalledTimes(2);
      expect(paymentElement.mount).toHaveBeenCalledTimes(2);
    });

    it('maps STRIPE_PAYMENT_FAILED from place order to paymentFailed', () => {
      stripeService.confirmPayment.and.returnValue(of({ intent: {} }));
      stripeService.placeOrder.and.returnValue(
        throwError(() => stripeError('STRIPE_PAYMENT_FAILED'))
      );

      component.submit();

      expect(component.error).toBe('stripePayment.paymentFailed');
    });

    it('shows paymentPending for STRIPE_PAYMENT_IN_PROGRESS and does not confirm again', () => {
      stripeService.confirmPayment.and.returnValue(of({ intent: {} }));
      stripeService.placeOrder.and.returnValue(
        throwError(() => stripeError('STRIPE_PAYMENT_IN_PROGRESS'))
      );

      component.submit();

      expect(component.error).toBe('stripePayment.paymentPending');
      expect(stripeService.confirmPayment).toHaveBeenCalledTimes(1);
    });

    it('maps STRIPE_UNAVAILABLE to genericError', () => {
      stripeService.confirmPayment.and.returnValue(
        throwError(() => stripeError('STRIPE_UNAVAILABLE'))
      );

      component.submit();

      expect(component.error).toBe('stripePayment.genericError');
    });

    it('goes to the order history when STRIPE_ALREADY_PLACED', () => {
      stripeService.confirmPayment.and.returnValue(of({ intent: {} }));
      stripeService.placeOrder.and.returnValue(
        throwError(() => stripeError('STRIPE_ALREADY_PLACED'))
      );

      component.submit();

      expect(routingService.go).toHaveBeenCalledWith({ cxRoute: 'orders' });
      expect(globalMessage.add).toHaveBeenCalledWith(
        { key: 'stripePayment.alreadyPlaced' },
        GlobalMessageType.MSG_TYPE_INFO
      );
    });

    it('shows the Stripe error message when confirmation fails', () => {
      stripeService.confirmPayment.and.returnValue(
        of({ error: 'Card declined' })
      );

      component.submit();

      expect(component.error).toBe('Card declined');
      expect(stripeService.placeOrder).not.toHaveBeenCalled();
    });
  });

  describe('guest checkout', () => {
    it('sends the guest email without the guid prefix', () => {
      setup({}, of({}), undefined, false, '8f2a-11ee|guest@example.com');
      stripeService.confirmPayment.and.returnValue(of({ intent: {} }));

      component.submit();

      expect(stripeService.confirmPayment.calls.mostRecent().args[3]).toBe(
        'guest@example.com'
      );
    });
  });

  describe('failures and re-entry', () => {
    it('shows unableToInitialize when the Stripe config request fails', fakeAsync(() => {
      setup(
        {},
        of({}),
        failLater(new HttpErrorResponse({ status: 500 }))
      );
      tick();

      expect(component.error).toBe('stripePayment.unableToInitialize');
      expect(component.isLoading).toBeFalse();
    }));

    it('ignores a second submit while the first one is running', () => {
      setup();
      stripeService.confirmPayment.and.returnValue(new Subject<any>());

      component.submit();
      component.submit();

      expect(stripeService.confirmPayment).toHaveBeenCalledTimes(1);
    });

    it('blocks new confirmations while the payment is pending', () => {
      setup();
      stripeService.confirmPayment.and.returnValue(of({ intent: {} }));
      stripeService.placeOrder.and.returnValue(
        throwError(() => stripeError('STRIPE_PAYMENT_IN_PROGRESS'))
      );

      component.submit();
      component.submit();

      expect(stripeService.confirmPayment).toHaveBeenCalledTimes(1);
      expect(component.paymentPending).toBeTrue();
    });
  });

  describe('inside an OnPush slot', () => {
    function errorText(): string | undefined {
      return fixture.nativeElement
        .querySelector('.error')
        ?.textContent.trim();
    }

    function button(): HTMLButtonElement {
      return fixture.nativeElement.querySelector('button[type="submit"]');
    }

    it('shows the error and enables the button after a declined card', fakeAsync(() => {
      setup({}, of({}), undefined, true);
      stripeService.confirmPayment.and.returnValue(
        succeedLater({ error: 'Card declined' })
      );

      button().click();
      fixture.detectChanges();
      expect(button().disabled).toBeTrue();
      tick();
      fixture.detectChanges();

      expect(errorText()).toBe('Card declined');
      expect(button().disabled).toBeFalse();
      expect(button().textContent).toContain('stripePayment.payNow');
    }));

    it('shows the server decision after a failed redirect return', fakeAsync(() => {
      setup(
        { payment_intent: 'pi_1', redirect_status: 'failed' },
        failLater(stripeError('STRIPE_PAYMENT_FAILED')),
        undefined,
        true
      );
      tick();
      fixture.detectChanges();

      expect(errorText()).toBe('stripePayment.paymentFailed');
      expect(button().disabled).toBeFalse();
    }));

    it('shows the pending state when the payment is in progress', fakeAsync(() => {
      setup({}, failLater(stripeError('STRIPE_PAYMENT_IN_PROGRESS')), undefined, true);
      stripeService.confirmPayment.and.returnValue(succeedLater({ intent: {} }));

      button().click();
      fixture.detectChanges();
      tick();
      fixture.detectChanges();

      expect(errorText()).toBe('stripePayment.paymentPending');
      expect(button().disabled).toBeTrue();
    }));

    it('shows unableToInitialize when the Stripe config request fails', fakeAsync(() => {
      setup(
        {},
        of({}),
        failLater(new HttpErrorResponse({ status: 500 })),
        true
      );
      tick();
      fixture.detectChanges();

      expect(errorText()).toBe('stripePayment.unableToInitialize');
    }));
  });

  describe('after a redirect return', () => {
    it('places order after redirect return without reading client status', () => {
      setup({
        payment_intent: 'pi_1',
        payment_intent_client_secret: 'cs_1',
        redirect_status: 'failed',
      });

      expect(stripeService.placeOrder).toHaveBeenCalledTimes(1);
      expect(routingService.go).toHaveBeenCalledWith({
        cxRoute: 'orderConfirmation',
      });
      expect(stripeService.ensureIntent).not.toHaveBeenCalled();
    });

    it('shows the server decision when the payment failed', fakeAsync(() => {
      setup(
        { payment_intent: 'pi_1', redirect_status: 'failed' },
        failLater(stripeError('STRIPE_PAYMENT_FAILED'))
      );
      tick();

      expect(component.error).toBe('stripePayment.paymentFailed');
      expect(routingService.go).not.toHaveBeenCalled();
    }));

    ['STRIPE_PAYMENT_FAILED', 'STRIPE_AMOUNT_MISMATCH', 'STRIPE_UNAVAILABLE'].forEach(
      (reason) =>
        it(`mounts the Payment Element again after redirect return fails with ${reason}`, fakeAsync(() => {
          setup(
            { payment_intent: 'pi_1', redirect_status: 'succeeded' },
            failLater(stripeError(reason))
          );
          expect(paymentElement.mount).not.toHaveBeenCalled();
          tick();

          expect(stripeService.ensureIntent).toHaveBeenCalledTimes(1);
          expect(paymentElement.mount).toHaveBeenCalledTimes(1);
          expect(component.error).toBeDefined();
        }))
    );
  });
});
