/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { Injectable } from '@angular/core';
import { Converter } from '@spartacus/core';
import { Order } from '@spartacus/order/root';

const MASK = '************';

@Injectable({ providedIn: 'root' })
export class StripeOrderNormalizer implements Converter<Order, Order> {
  convert(source: Order, target?: Order): Order {
    const result: Order = target ?? { ...source };
    const info = source.stripePaymentInfo;
    if (info?.cardLast4) {
      result.paymentInfo = {
        ...result.paymentInfo,
        cardType: { name: info.cardBrand },
        cardNumber: `${MASK}${info.cardLast4}`,
        expiryMonth: info.cardExpMonth,
        expiryYear: info.cardExpYear,
      };
    }
    return result;
  }
}
