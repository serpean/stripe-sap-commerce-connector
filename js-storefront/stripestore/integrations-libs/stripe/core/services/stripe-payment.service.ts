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
   * Syncs the intent amount with the cart, then confirms the payment. With the
   * same client secret, the Payment Element fetches the new amount first. A new
   * client secret means that the cart has a new PaymentIntent: the old one is
   * not confirmed, and the result asks to mount the new one and pay again.
   */
  confirmPayment(
    stripe: Stripe,
    elements: StripeElements,
    currentClientSecret?: string,
    billingEmail?: string,
  ): Observable<StripePaymentResult> {
    return this.ensureIntent().pipe(
      mergeMap((intent): Observable<StripePaymentResult> => {
        if (intent.clientSecret && intent.clientSecret !== currentClientSecret) {
          return of<StripePaymentResult>({
            newClientSecret: intent.clientSecret,
            error: 'stripePayment.amountChanged',
          });
        }
        return from(elements.fetchUpdates()).pipe(
          mergeMap((update) =>
            update.error
              ? of<StripePaymentResult>({ error: 'stripePayment.genericError' })
              : this.confirm(stripe, elements, billingEmail),
          ),
        );
      }),
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
    billingEmail?: string,
  ): Observable<StripePaymentResult> {
    return from(
      stripe.confirmPayment({
        elements,
        confirmParams: {
          return_url: this.currentUrl(),
          // The Payment Element hides the email field: Multibanco needs it.
          ...(billingEmail
            ? { payment_method_data: { billing_details: { email: billingEmail } } }
            : {}),
        },
        redirect: 'if_required',
      }),
    ).pipe(
      map((result): StripePaymentResult => {
        if (result.error) {
          // Stripe shows its own message inside the Payment Element; the shop shows a translated one.
          return { error: 'stripePayment.paymentFailed' };
        }
        const paymentIntent = result.paymentIntent;
        if (!paymentIntent) {
          return { error: 'stripePayment.noPaymentIntent' };
        }
        const intent = this.mapPaymentIntent(paymentIntent);
        if (
          paymentIntent.status === 'requires_action' &&
          !this.isInstructionsAction(paymentIntent)
        ) {
          // The customer must still act (for example, authenticate): do not place the order.
          return {
            intent,
            placeOrder: false,
            error: 'stripePayment.genericError',
          };
        }
        return { intent, placeOrder: true };
      }),
    );
  }

  /** A voucher or bank transfer: the customer pays later, so the order is placed now. */
  protected isInstructionsAction(paymentIntent: PaymentIntent): boolean {
    const type = paymentIntent.next_action?.type;
    return (
      type === 'multibanco_display_details' ||
      type === 'display_bank_transfer_instructions'
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
