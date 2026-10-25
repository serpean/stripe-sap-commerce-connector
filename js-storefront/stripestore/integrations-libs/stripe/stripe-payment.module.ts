/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NgModule } from '@angular/core';
import { StripePaymentCoreModule } from 'sap-commerce-stripe-payment/core';
import { StripePaymentComponentsModule } from 'sap-commerce-stripe-payment/components';

@NgModule({
  imports: [
    StripePaymentCoreModule,
    StripePaymentComponentsModule,
  ],
})
export class StripePaymentModule {}
