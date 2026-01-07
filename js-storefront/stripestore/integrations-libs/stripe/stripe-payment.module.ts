/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NgModule } from '@angular/core';
import { StripePaymentRootModule } from 'sap-commerce-stripe-payment/root';
import { StripePaymentCoreModule } from 'sap-commerce-stripe-payment/core';
import { StripePaymentComponentsModule } from 'sap-commerce-stripe-payment/components';

@NgModule({
  imports: [
    StripePaymentRootModule,
    StripePaymentCoreModule,
    StripePaymentComponentsModule,
  ],
})
export class StripePaymentModule {}
