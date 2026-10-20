/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NgModule } from '@angular/core';
import { HttpClientModule } from '@angular/common/http';
import { ORDER_NORMALIZER } from '@spartacus/order/root';
import { StripeOrderNormalizer } from './occ/stripe-order-normalizer';

@NgModule({
  imports: [HttpClientModule],
  providers: [
    {
      provide: ORDER_NORMALIZER,
      useExisting: StripeOrderNormalizer,
      multi: true,
    },
  ],
})
export class StripePaymentCoreModule {}
