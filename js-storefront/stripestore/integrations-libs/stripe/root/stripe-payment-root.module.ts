/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { I18nModule, provideDefaultConfig } from '@spartacus/core';
import { CartOutlets } from '@spartacus/cart/base/root';
import { OutletPosition, provideOutlet } from '@spartacus/storefront';
import { StripePaymentInstructionsComponent } from './stripe-payment-instructions/stripe-payment-instructions.component';
import { defaultStripePaymentConfig } from './config/default-stripe-payment.config';
import { defaultStripeCheckoutConfig } from './config/stripe-checkout.config';
import { defaultStripeOccConfig } from './config/stripe-occ.config';

/**
 * Eager module. It also provides the outlets of the payment instructions: the
 * lazy payment module loads only on the payment step, so a customer who comes
 * back to the order later would never see the instructions.
 */
@NgModule({
  declarations: [StripePaymentInstructionsComponent],
  imports: [CommonModule, I18nModule],
  exports: [StripePaymentInstructionsComponent],
  providers: [
    // Order details page.
    provideOutlet({
      id: CartOutlets.ORDER_OVERVIEW,
      position: OutletPosition.AFTER,
      component: StripePaymentInstructionsComponent,
    }),
    // Order confirmation page: it has no order overview. Its CMS components are wrapped in outlets by type code.
    provideOutlet({
      id: 'OrderConfirmationThankMessageComponent',
      position: OutletPosition.AFTER,
      component: StripePaymentInstructionsComponent,
    }),
    provideDefaultConfig(defaultStripePaymentConfig),
    provideDefaultConfig(defaultStripeCheckoutConfig),
    provideDefaultConfig(defaultStripeOccConfig),
  ],
})
export class StripePaymentRootModule {}
