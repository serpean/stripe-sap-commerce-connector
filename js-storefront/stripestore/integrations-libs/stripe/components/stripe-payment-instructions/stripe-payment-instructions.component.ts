/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { ChangeDetectionStrategy, Component, Optional } from '@angular/core';
import { Order } from '@spartacus/order/root';
import { OutletContextData } from '@spartacus/storefront';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import { StripePaymentInfo } from 'sap-commerce-stripe-payment/root';

/**
 * Shows the voucher or bank transfer instructions of a pending Stripe
 * payment. Rendered after the order overview on the order confirmation and
 * order details pages.
 */
@Component({
  selector: 'cx-stripe-payment-instructions',
  templateUrl: './stripe-payment-instructions.component.html',
  styleUrls: ['./stripe-payment-instructions.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: false,
})
export class StripePaymentInstructionsComponent {
  info$: Observable<StripePaymentInfo | undefined>;

  constructor(@Optional() protected outletContext: OutletContextData<{ item: Order }>) {
    this.info$ = (this.outletContext?.context$ ?? of(undefined)).pipe(
      map((context) => {
        const info: StripePaymentInfo | undefined =
          context?.item?.stripePaymentInfo;
        return info?.paymentStatus === 'PENDING' && info.instructionsUrl
          ? info
          : undefined;
      })
    );
  }
}
