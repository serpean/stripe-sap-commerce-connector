/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Order, OrderFacade } from '@spartacus/order/root';
import { OutletContextData } from '@spartacus/storefront';
import { Observable, of } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { StripePaymentInfo } from '../model/index';

/**
 * Shows the voucher or bank transfer instructions of a pending Stripe
 * payment. It is rendered after the order overview of the order details page
 * (the outlet gives the order) and after the thank-you message of the order
 * confirmation page (no order in the outlet: the placed order is read from
 * the order facade). The root module provides both outlets, so they exist
 * before the lazy payment feature loads.
 */
@Component({
  selector: 'cx-stripe-payment-instructions',
  templateUrl: './stripe-payment-instructions.component.html',
  styleUrls: ['./stripe-payment-instructions.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: false,
})
export class StripePaymentInstructionsComponent {
  protected outletContext = inject<OutletContextData<{ item?: Order }>>(
    OutletContextData,
    { optional: true }
  );
  protected orderFacade = inject(OrderFacade, { optional: true });

  info$: Observable<StripePaymentInfo | undefined> = this.order$().pipe(
    map((order) => {
      const info = (order as { stripePaymentInfo?: StripePaymentInfo } | undefined)
        ?.stripePaymentInfo;
      return info?.paymentStatus === 'PENDING' && info.instructionsUrl
        ? info
        : undefined;
    })
  );

  protected order$(): Observable<Order | undefined> {
    const context$: Observable<{ item?: Order } | undefined> =
      this.outletContext?.context$ ?? of(undefined);
    return context$.pipe(
      switchMap((context): Observable<Order | undefined> =>
        context?.item
          ? of(context.item)
          : this.orderFacade?.getOrderDetails() ?? of(undefined)
      )
    );
  }
}
