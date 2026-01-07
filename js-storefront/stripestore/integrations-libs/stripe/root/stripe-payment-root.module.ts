/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NgModule } from '@angular/core';
import { provideDefaultConfig } from '@spartacus/core';
import { defaultStripePaymentConfig } from './config/default-stripe-payment.config';
import { defaultStripeCheckoutConfig } from './config/stripe-checkout.config';
import { defaultStripeOccConfig } from './config/stripe-occ.config';

@NgModule({
  providers: [
    provideDefaultConfig(defaultStripePaymentConfig),
    provideDefaultConfig(defaultStripeCheckoutConfig),
    provideDefaultConfig(defaultStripeOccConfig),
  ],
})
export class StripePaymentRootModule {}
