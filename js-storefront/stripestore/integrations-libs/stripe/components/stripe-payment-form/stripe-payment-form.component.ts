/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
} from '@angular/core';
import { FormBuilder, FormGroup } from '@angular/forms';
import { ActivatedRoute, Params, Router } from '@angular/router';
import {
  CheckoutFlowOrchestratorService,
  CheckoutStepService,
} from '@spartacus/checkout/base/components';
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
import { Subscription, take } from 'rxjs';
import { StripePaymentService } from 'sap-commerce-stripe-payment/core';

@Component({
  selector: 'cx-stripe-payment-form',
  templateUrl: './stripe-payment-form.component.html',
  styleUrls: ['./stripe-payment-form.component.scss'],
  standalone: false,
})
export class StripePaymentFormComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('paymentElement') paymentElementRef?: ElementRef<HTMLDivElement>;

  form: FormGroup;
  isLoading = false;
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

  constructor(
    protected fb: FormBuilder,
    protected stripeService: StripePaymentService,
    protected checkoutStepService: CheckoutStepService,
    protected translation: TranslationService,
    protected globalMessageService: GlobalMessageService,
    protected checkoutFlowOrchestrator: CheckoutFlowOrchestratorService,
    protected routingService: RoutingService,
    protected route: ActivatedRoute,
    protected router: Router
  ) {
    this.form = this.fb.group({
      savePaymentMethod: [false],
    });

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
        })
    );
  }

  ngOnInit(): void {
    this.subscription.add(
      this.route.queryParams.pipe(take(1)).subscribe((params) => {
        if (params['redirect_status']) {
          this.isReturningFromRedirect = true;
          this.handleRedirectReturn(params);
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
      this.stripeService.getStripe().subscribe((stripe) => {
        this.stripe = stripe;
        if (!stripe) {
          this.error = 'stripePayment.missingPublishableKey';
        } else if (this.clientSecret) {
          this.initializeElements(this.clientSecret);
        }
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
    this.error = undefined;
    this.subscription.add(
      this.stripeService.ensureIntent().subscribe({
        next: (intent) => {
          this.clientSecret = intent.clientSecret;
          if (this.clientSecret) {
            this.initializeElements(this.clientSecret);
          }
        },
        error: () => {
          this.error = 'stripePayment.unableToInitialize';
        },
      })
    );
  }

  submit(): void {
    if (!this.useStripeFlow) {
      return;
    }
    if (!this.stripe || !this.elements || !this.clientSecret) {
      this.error = 'stripePayment.unableToInitialize';
      return;
    }
    this.isLoading = true;
    this.error = undefined;
    const savePaymentMethod = this.form.get('savePaymentMethod')?.value ?? false;
    this.subscription.add(
      this.stripeService
        .confirmPayment(this.stripe, this.elements, savePaymentMethod)
        .subscribe({
          next: (result) => {
            this.isLoading = false;
            if (result.error) {
              this.error = result.error;
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
            this.error = err?.message ?? 'stripePayment.genericError';
          },
        })
    );
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
            this.routingService.go({ cxRoute: 'orderConfirmation' });
          },
          error: (err) => {
            this.isLoading = false;
            this.placingOrder = false;
            this.error = err?.message ?? 'stripePayment.genericError';
          },
        })
    );
  }

  protected handleRedirectReturn(params: Params): void {
    const redirectStatus = params['redirect_status'];
    const clientSecret = params['payment_intent_client_secret'];

    this.isLoading = true;
    this.clearUrlParams();

    if (redirectStatus === 'succeeded') {
      this.placeOrderAfterPayment();
    } else if (redirectStatus === 'failed') {
      this.isLoading = false;
      this.error = 'stripePayment.paymentFailed';
      this.isReturningFromRedirect = false;
    } else {
      // pending or other status - verify with Stripe
      this.verifyPaymentStatus(clientSecret);
    }
  }

  protected verifyPaymentStatus(clientSecret: string): void {
    if (!clientSecret) {
      this.isLoading = false;
      this.error = 'stripePayment.missingClientSecret';
      this.isReturningFromRedirect = false;
      return;
    }

    this.subscription.add(
      this.stripeService.verifyPaymentIntent(clientSecret).subscribe({
        next: (paymentIntent) => {
          if (
            paymentIntent?.status === 'succeeded' ||
            paymentIntent?.status === 'requires_capture'
          ) {
            this.placeOrderAfterPayment();
          } else if (
            paymentIntent?.status === 'requires_payment_method' ||
            paymentIntent?.status === 'canceled'
          ) {
            this.isLoading = false;
            this.error = 'stripePayment.paymentFailed';
            this.isReturningFromRedirect = false;
          } else {
            // Still processing or other status
            this.isLoading = false;
            this.error = 'stripePayment.paymentPending';
            this.isReturningFromRedirect = false;
          }
        },
        error: () => {
          this.isLoading = false;
          this.error = 'stripePayment.verificationFailed';
          this.isReturningFromRedirect = false;
        },
      })
    );
  }

  protected clearUrlParams(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });
  }
}
