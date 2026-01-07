/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { Injectable } from '@angular/core';
import {
  GlobalMessageService,
  GlobalMessageType,
  WindowRef,
} from '@spartacus/core';
import { Order, OrderFacade } from '@spartacus/order/root';
import {
  Stripe,
  StripeElements,
  loadStripe,
  PaymentIntent,
} from '@stripe/stripe-js';
import { from, mergeMap, Observable, of, shareReplay, tap } from 'rxjs';
import { StripePaymentOccService } from './stripe-payment-occ.service';
import {
  StripePaymentConfig,
  StripeIntentResponse,
  StripePaymentResult,
} from 'sap-commerce-stripe-payment/root';

@Injectable({ providedIn: 'root' })
export class StripePaymentService {
  private stripe$: Observable<Stripe | null>;

  constructor(
    protected config: StripePaymentConfig,
    protected occService: StripePaymentOccService,
    protected orderFacade: OrderFacade,
    protected globalMessage: GlobalMessageService,
    protected windowRef: WindowRef
  ) {
    const publishableKey = this.config?.stripePayment?.publishableKey;
    this.stripe$ = publishableKey
      ? from(loadStripe(publishableKey)).pipe(shareReplay(1))
      : of(null);
  }

  getStripe(): Observable<Stripe | null> {
    return this.stripe$;
  }

  ensureIntent(): Observable<StripeIntentResponse> {
    return this.occService.ensureIntent().pipe(
      tap((response) => {
        if (!response.clientSecret) {
          this.globalMessage.add(
            { key: 'stripe.missingClientSecret' },
            GlobalMessageType.MSG_TYPE_ERROR
          );
        }
      })
    );
  }

  confirmPayment(
    stripe: Stripe,
    elements: StripeElements,
    savePaymentMethod = false
  ): Observable<StripePaymentResult> {
    const confirmParams: Record<string, unknown> = {};
    const resolvedLocation = this.windowRef?.location;
    let returnUrl = '/';
    if (resolvedLocation?.href) {
      returnUrl = resolvedLocation.href;
    } else if (this.windowRef?.nativeWindow?.location?.href) {
      returnUrl = this.windowRef.nativeWindow.location.href;
    }
    confirmParams['return_url'] = returnUrl;
    if (savePaymentMethod) {
      confirmParams['payment_method_options'] = {
        card: { setup_future_usage: 'off_session' },
      };
    }
    return from(
      stripe.confirmPayment({
        elements,
        confirmParams,
        redirect: 'if_required',
      })
    ).pipe(
      mergeMap((result) => {
        if (result.error) {
          return of({ error: result.error.message });
        }
        const paymentIntent = result.paymentIntent;
        if (!paymentIntent) {
          return of({ error: 'stripe.noPaymentIntent' });
        }
        return of({
          intent: this.mapPaymentIntent(paymentIntent),
        });
      })
    );
  }

  protected mapPaymentIntent(
    paymentIntent: PaymentIntent
  ): StripeIntentResponse {
    return {
      intentId: paymentIntent.id,
      clientSecret: paymentIntent.client_secret ?? undefined,
      status: paymentIntent.status,
    };
  }

  placeOrder(): Observable<Order> {
    return this.orderFacade.placeOrder(true);
  }

  verifyPaymentIntent(clientSecret: string): Observable<PaymentIntent | null> {
    return this.stripe$.pipe(
      mergeMap((stripe) => {
        if (!stripe) {
          return of(null);
        }
        return from(stripe.retrievePaymentIntent(clientSecret)).pipe(
          mergeMap((result) => {
            if (result.error) {
              return of(null);
            }
            return of(result.paymentIntent ?? null);
          })
        );
      })
    );
  }
}
