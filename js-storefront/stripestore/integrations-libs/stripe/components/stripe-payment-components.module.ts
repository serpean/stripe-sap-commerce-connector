/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { I18nConfig, I18nModule, provideConfig } from '@spartacus/core';
import {
  CheckoutPaymentMethodModule,
  CheckoutProgressModule,
  CheckoutReviewSubmitModule,
} from '@spartacus/checkout/base/components';
import { StripePaymentFormComponent } from './stripe-payment-form/stripe-payment-form.component';
import {
  stripePaymentTranslationChunksConfig,
  stripePaymentTranslationsEn,
} from 'sap-commerce-stripe-payment/assets';

@NgModule({
  declarations: [StripePaymentFormComponent],
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
    CheckoutProgressModule,
    CheckoutPaymentMethodModule,
    CheckoutReviewSubmitModule,
    I18nModule,
  ],
  providers: [
    provideConfig(<I18nConfig>{
      i18n: {
        resources: { en: stripePaymentTranslationsEn },
        chunks: stripePaymentTranslationChunksConfig,
        fallbackLang: 'en',
      },
    }),
  ],
  exports: [StripePaymentFormComponent],
})
export class StripePaymentComponentsModule {}
