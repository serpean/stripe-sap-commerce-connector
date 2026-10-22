/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { I18nConfig, I18nModule, provideConfig } from '@spartacus/core';
import { CartOutlets } from '@spartacus/cart/base/root';
import { OutletPosition, provideOutlet } from '@spartacus/storefront';
import {
  CheckoutPaymentMethodModule,
  CheckoutProgressModule,
  CheckoutReviewSubmitModule,
} from '@spartacus/checkout/base/components';
import { StripePaymentFormComponent } from './stripe-payment-form/stripe-payment-form.component';
import { StripePaymentInstructionsComponent } from './stripe-payment-instructions/stripe-payment-instructions.component';
import {
  stripePaymentTranslationChunksConfig,
  stripePaymentTranslationsEn,
} from 'sap-commerce-stripe-payment/assets';

@NgModule({
  declarations: [StripePaymentFormComponent, StripePaymentInstructionsComponent],
  imports: [
    CommonModule,
    RouterModule,
    CheckoutProgressModule,
    CheckoutPaymentMethodModule,
    CheckoutReviewSubmitModule,
    I18nModule,
  ],
  providers: [
    // The order overview is shared by the confirmation and the order details pages.
    provideOutlet({
      id: CartOutlets.ORDER_OVERVIEW,
      position: OutletPosition.AFTER,
      component: StripePaymentInstructionsComponent,
    }),
    provideConfig(<I18nConfig>{
      i18n: {
        resources: { en: stripePaymentTranslationsEn },
        chunks: stripePaymentTranslationChunksConfig,
        fallbackLang: 'en',
      },
    }),
  ],
  exports: [StripePaymentFormComponent, StripePaymentInstructionsComponent],
})
export class StripePaymentComponentsModule {}
