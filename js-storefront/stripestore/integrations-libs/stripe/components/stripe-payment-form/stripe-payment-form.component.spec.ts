import { HttpErrorResponse } from '@angular/common/http';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { CheckoutFlowOrchestratorService } from '@spartacus/checkout/base/components';
import {
  GlobalMessageService,
  GlobalMessageType,
  I18nTestingModule,
  RoutingService,
} from '@spartacus/core';
import { Stripe } from '@stripe/stripe-js';
import { Order } from '@spartacus/order/root';
import { Observable, of, throwError } from 'rxjs';
import { StripePaymentService } from 'sap-commerce-stripe-payment/core';
import { StripePaymentFormComponent } from './stripe-payment-form.component';

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
    placeOrderResult: Observable<unknown> = of({})
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
    stripeService.getStripe.and.returnValue(of(fakeStripe));
    stripeService.ensureIntent.and.returnValue(
      of({ paymentIntentId: 'pi_1', clientSecret: 'cs_1' })
    );
    stripeService.placeOrder.and.returnValue(placeOrderResult as Observable<Order>);
    routingService = jasmine.createSpyObj('RoutingService', ['go']);
    globalMessage = jasmine.createSpyObj('GlobalMessageService', ['add']);

    TestBed.configureTestingModule({
      imports: [I18nTestingModule],
      declarations: [StripePaymentFormComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: StripePaymentService, useValue: stripeService },
        { provide: RoutingService, useValue: routingService },
        { provide: GlobalMessageService, useValue: globalMessage },
        {
          provide: CheckoutFlowOrchestratorService,
          useValue: { getPaymentProvider: () => of('STRIPE') },
        },
        { provide: ActivatedRoute, useValue: { queryParams: of(queryParams) } },
        { provide: Router, useValue: { navigate: jasmine.createSpy('navigate') } },
      ],
    });
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

    it('shows the server decision when the payment failed', () => {
      setup(
        { payment_intent: 'pi_1', redirect_status: 'failed' },
        throwError(() => stripeError('STRIPE_PAYMENT_FAILED'))
      );

      expect(component.error).toBe('stripePayment.paymentFailed');
      expect(routingService.go).not.toHaveBeenCalled();
    });
  });
});
