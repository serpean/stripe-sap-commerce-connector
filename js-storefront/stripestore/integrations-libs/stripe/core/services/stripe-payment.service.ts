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
import {
  defer,
  from,
  map,
  mergeMap,
  Observable,
  of,
  shareReplay,
  tap,
} from 'rxjs';
import { StripePaymentOccService } from './stripe-payment-occ.service';
import {
  StripePaymentConfig,
  StripeIntentResponse,
  StripePaymentResult,
} from 'sap-commerce-stripe-payment/root';

@Injectable({ providedIn: 'root' })
export class StripePaymentService {
  private stripe$?: Observable<Stripe | null>;

  constructor(
    protected config: StripePaymentConfig,
    protected occService: StripePaymentOccService,
    protected orderFacade: OrderFacade,
    protected globalMessage: GlobalMessageService,
    protected windowRef: WindowRef,
  ) {}

  /**
   * Loads Stripe.js with the configured publishable key. When the
   * configuration has no key, the backend supplies it.
   */
  getStripe(): Observable<Stripe | null> {
    if (!this.stripe$) {
      this.stripe$ = defer(() => this.resolvePublishableKey()).pipe(
        mergeMap((key) => (key ? from(this.loadStripeJs(key)) : of(null))),
        shareReplay(1),
      );
    }
    return this.stripe$;
  }

  ensureIntent(): Observable<StripeIntentResponse> {
    return this.occService.ensureIntent().pipe(
      tap((response) => {
        if (!response.clientSecret) {
          this.globalMessage.add(
            { key: 'stripePayment.missingClientSecret' },
            GlobalMessageType.MSG_TYPE_ERROR,
          );
        }
      }),
    );
  }

  /**
   * Syncs the intent amount with the cart, refreshes the Payment Element when
   * the client secret changed, and confirms the payment.
   */
  confirmPayment(
    stripe: Stripe,
    elements: StripeElements,
    currentClientSecret?: string,
  ): Observable<StripePaymentResult> {
    return this.ensureIntent().pipe(
      mergeMap((intent): Observable<StripePaymentResult> => {
        const changed =
          !!intent.clientSecret && intent.clientSecret !== currentClientSecret;
        if (!changed) {
          return this.confirm(stripe, elements);
        }
        return from(elements.fetchUpdates()).pipe(
          mergeMap((update) =>
            update.error
              ? of<StripePaymentResult>({ error: 'stripePayment.genericError' })
              : this.confirm(stripe, elements)
          )
        );
      })
    );
  }

  placeOrder(): Observable<Order> {
    return this.orderFacade.placeOrder(true);
  }

  protected loadStripeJs(publishableKey: string): Promise<Stripe | null> {
    return loadStripe(publishableKey);
  }

  protected resolvePublishableKey(): Observable<string | undefined> {
    const configured = this.config?.stripePayment?.publishableKey;
    return configured
      ? of(configured)
      : this.occService.getConfig().pipe(map((c) => c?.publishableKey));
  }

  protected confirm(
    stripe: Stripe,
    elements: StripeElements,
  ): Observable<StripePaymentResult> {
    return from(
      stripe.confirmPayment({
        elements,
        confirmParams: { return_url: this.currentUrl() },
        redirect: 'if_required',
      }),
    ).pipe(
      map((result): StripePaymentResult => {
        if (result.error) {
          return { error: result.error.message };
        }
        const paymentIntent = result.paymentIntent;
        if (!paymentIntent) {
          return { error: 'stripePayment.noPaymentIntent' };
        }
        return { intent: this.mapPaymentIntent(paymentIntent) };
      }),
    );
  }

  protected currentUrl(): string {
    return (
      this.windowRef?.location?.href ??
      this.windowRef?.nativeWindow?.location?.href ??
      '/'
    );
  }

  protected mapPaymentIntent(
    paymentIntent: PaymentIntent,
  ): StripeIntentResponse {
    return {
      paymentIntentId: paymentIntent.id,
      clientSecret: paymentIntent.client_secret ?? undefined,
      status: paymentIntent.status,
    };
  }
}
