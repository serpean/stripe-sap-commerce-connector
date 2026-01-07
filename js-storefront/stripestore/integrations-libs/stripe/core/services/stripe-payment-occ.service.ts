/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, combineLatest, switchMap } from 'rxjs';
import { OccEndpointsService, UserIdService } from '@spartacus/core';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { StripeIntentResponse } from 'sap-commerce-stripe-payment/root';

@Injectable({ providedIn: 'root' })
export class StripePaymentOccService {
  constructor(
    protected http: HttpClient,
    protected occEndpoints: OccEndpointsService,
    protected activeCart: ActiveCartFacade,
    protected userIdService: UserIdService
  ) {}

  ensureIntent(): Observable<StripeIntentResponse> {
    return this.withCartContext((userId, cartId) => {
      const url = this.occEndpoints.buildUrl('stripeIntent', {
        urlParams: { userId, cartId },
      });
      return this.http.post<StripeIntentResponse>(url, {});
    });
  }

  protected withCartContext<T>(
    projector: (userId: string, cartId: string) => Observable<T>
  ): Observable<T> {
    return combineLatest([
      this.userIdService.takeUserId(),
      this.activeCart.getActiveCartId(),
    ]).pipe(switchMap(([userId, cartId]) => projector(userId, cartId)));
  }
}
