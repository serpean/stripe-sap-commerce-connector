/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { CheckoutFlowOrchestratorService } from '@spartacus/checkout/base/components';
import {
  GlobalMessageService,
  GlobalMessageType,
  RoutingService,
  TranslationService,
} from '@spartacus/core';
import {
  Stripe,
  StripeElements,
  StripePaymentElement,
  StripeElementsOptions,
} from '@stripe/stripe-js';
import { Subscription, mergeMap, take } from 'rxjs';
import {
  StripePaymentService,
  getStripeErrorReason,
} from 'sap-commerce-stripe-payment/core';

@Component({
  selector: 'cx-stripe-payment-form',
  templateUrl: './stripe-payment-form.component.html',
  styleUrls: ['./stripe-payment-form.component.scss'],
  standalone: false,
})
export class StripePaymentFormComponent
  implements OnInit, AfterViewInit, OnDestroy
{
  @ViewChild('paymentElement') paymentElementRef?: ElementRef<HTMLDivElement>;

  isLoading = false;
  paymentPending = false;
  error?: string;

  protected stripe?: Stripe | null;
  protected elements?: StripeElements;
  protected paymentElement?: StripePaymentElement;
  protected clientSecret?: string;
  protected subscription = new Subscription();
  protected viewInitialized = false;
  useStripeFlow = false;
  protected stripeSetup = false;
  protected placingOrder = false;
  protected isReturningFromRedirect = false;
  protected activeCartFacade = inject(ActiveCartFacade);

  constructor(
    protected stripeService: StripePaymentService,
    protected translation: TranslationService,
    protected globalMessageService: GlobalMessageService,
    protected checkoutFlowOrchestrator: CheckoutFlowOrchestratorService,
    protected routingService: RoutingService,
    protected route: ActivatedRoute,
    protected router: Router,
    protected changeDetector: ChangeDetectorRef
  ) {
    this.subscription.add(
      this.checkoutFlowOrchestrator
        .getPaymentProvider()
        .subscribe((provider) => {
          const shouldUseStripe = provider?.toUpperCase() === 'STRIPE';
          this.useStripeFlow = shouldUseStripe;
          if (shouldUseStripe) {
            this.initializeStripeSupport();
            if (this.viewInitialized) {
              this.ensureIntent();
            }
          } else {
            this.resetStripeState();
          }
          this.changeDetector.markForCheck();
        })
    );
  }

  ngOnInit(): void {
    this.subscription.add(
      this.route.queryParams.pipe(take(1)).subscribe((params) => {
        if (params['payment_intent']) {
          this.isReturningFromRedirect = true;
          this.handleRedirectReturn();
        }
      })
    );
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    if (this.useStripeFlow && !this.isReturningFromRedirect) {
      this.ensureIntent();
    }
  }

  protected initializeElements(clientSecret: string): void {
    if (!this.stripe || !this.paymentElementRef) {
      return;
    }
    this.paymentElement?.destroy();
    const options: StripeElementsOptions = { clientSecret };
    this.elements = this.stripe.elements(options);
    this.paymentElement = this.elements.create('payment', {
      layout: 'tabs',
    });
    this.paymentElement.mount(this.paymentElementRef.nativeElement);
  }

  protected initializeStripeSupport(): void {
    if (this.stripeSetup) {
      return;
    }
    this.stripeSetup = true;
    this.subscription.add(
      this.stripeService.getStripe().subscribe({
        next: (stripe) => {
          this.stripe = stripe;
          if (!stripe) {
            this.error = 'stripePayment.missingPublishableKey';
          } else if (this.clientSecret) {
            this.initializeElements(this.clientSecret);
          }
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.isLoading = false;
          this.error = 'stripePayment.unableToInitialize';
          this.changeDetector.markForCheck();
        },
      })
    );
  }

  protected resetStripeState(): void {
    this.paymentElement?.destroy();
    this.paymentElement = undefined;
    this.elements = undefined;
    this.stripe = null;
    this.clientSecret = undefined;
    this.isLoading = false;
    this.error = undefined;
  }

  protected ensureIntent(): void {
    if (!this.useStripeFlow) {
      return;
    }
    this.subscription.add(
      this.stripeService.ensureIntent().subscribe({
        next: (intent) => {
          this.clientSecret = intent.clientSecret;
          if (this.clientSecret) {
            this.initializeElements(this.clientSecret);
          }
          this.changeDetector.markForCheck();
        },
        error: (err) => {
          this.isLoading = false;
          this.handleError(err, 'stripePayment.unableToInitialize', false);
        },
      })
    );
  }

  submit(): void {
    if (!this.useStripeFlow || this.isLoading || this.paymentPending) {
      return;
    }
    if (!this.stripe || !this.elements || !this.clientSecret) {
      this.error = 'stripePayment.unableToInitialize';
      return;
    }
    this.isLoading = true;
    this.error = undefined;
    const { stripe, elements, clientSecret } = this;
    this.subscription.add(
      this.activeCartFacade
        .getActive()
        .pipe(
          take(1),
          mergeMap((cart) =>
            this.stripeService.confirmPayment(
              stripe,
              elements,
              clientSecret,
              this.billingEmail(cart?.user?.uid)
            )
          )
        )
        .subscribe({
          next: (result) => {
            this.isLoading = false;
            if (result.newClientSecret) {
              this.clientSecret = result.newClientSecret;
              this.initializeElements(result.newClientSecret);
            }
            if (result.error) {
              this.error = result.error;
              this.changeDetector.markForCheck();
              return;
            }
            this.translation
              .translate('stripePayment.success')
              .pipe(take(1))
              .subscribe((message) =>
                this.globalMessageService.add(
                  message,
                  GlobalMessageType.MSG_TYPE_CONFIRMATION
                )
              );
            this.placeOrderAfterPayment();
          },
          error: (err) => {
            this.isLoading = false;
            this.handleError(err, 'stripePayment.genericError');
          },
        })
    );
  }

  /** The cart user id is the email; for a guest it is "<guid>|<email>". */
  protected billingEmail(uid?: string): string | undefined {
    if (!uid) {
      return undefined;
    }
    const separator = uid.indexOf('|');
    const email = separator >= 0 ? uid.substring(separator + 1) : uid;
    return email.includes('@') && email !== 'anonymous' ? email : undefined;
  }

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
    this.paymentElement?.destroy();
  }

  protected placeOrderAfterPayment(): void {
    if (this.placingOrder) {
      return;
    }
    this.placingOrder = true;
    this.isLoading = true;
    this.changeDetector.markForCheck();

    this.subscription.add(
      this.stripeService
        .placeOrder()
        .pipe(take(1))
        .subscribe({
          next: () => {
            this.useStripeFlow = false;
            this.resetStripeState();
            this.subscription.unsubscribe();
            this.isLoading = false;
            this.changeDetector.markForCheck();
            this.routingService.go({ cxRoute: 'orderConfirmation' });
          },
          error: (err) => {
            this.isLoading = false;
            this.placingOrder = false;
            this.recoverFromRedirectFailure(err);
            this.handleError(err, 'stripePayment.genericError');
          },
        })
    );
  }

  /** The server decides the outcome; the client status is not used. */
  protected handleRedirectReturn(): void {
    this.clearUrlParams();
    this.placeOrderAfterPayment();
  }

  /**
   * After a failed redirect return the Payment Element is not mounted yet.
   * Mount it again so the customer can retry.
   */
  protected recoverFromRedirectFailure(err: unknown): void {
    if (!this.isReturningFromRedirect) {
      return;
    }
    const reason = getStripeErrorReason(err);
    if (reason === 'STRIPE_PAYMENT_IN_PROGRESS' || reason === 'STRIPE_ALREADY_PLACED') {
      return;
    }
    this.isReturningFromRedirect = false;
    if (reason !== 'STRIPE_AMOUNT_MISMATCH') {
      this.ensureIntent();
    }
  }

  protected handleError(
    err: unknown,
    fallbackMessage: string,
    remountOnMismatch = true
  ): void {
    switch (getStripeErrorReason(err)) {
      case 'STRIPE_AMOUNT_MISMATCH':
        if (remountOnMismatch) {
          this.ensureIntent();
        }
        this.error = 'stripePayment.amountChanged';
        break;
      case 'STRIPE_PAYMENT_FAILED':
        this.error = 'stripePayment.paymentFailed';
        break;
      case 'STRIPE_PAYMENT_IN_PROGRESS':
        this.paymentPending = true;
        this.error = 'stripePayment.paymentPending';
        break;
      case 'STRIPE_ALREADY_PLACED':
        this.globalMessageService.add(
          { key: 'stripePayment.alreadyPlaced' },
          GlobalMessageType.MSG_TYPE_INFO
        );
        this.routingService.go({ cxRoute: 'orders' });
        break;
      case 'STRIPE_UNAVAILABLE':
        this.error = 'stripePayment.genericError';
        break;
      default:
        this.error = fallbackMessage;
    }
    this.changeDetector.markForCheck();
  }

  protected clearUrlParams(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });
  }
}
