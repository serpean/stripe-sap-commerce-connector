import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { OccEndpointsService, UserIdService } from '@spartacus/core';
import { ActiveCartFacade } from '@spartacus/cart/base/root';
import { of } from 'rxjs';
import { StripePaymentOccService } from './stripe-payment-occ.service';

describe('StripePaymentOccService', () => {
  let service: StripePaymentOccService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        {
          provide: OccEndpointsService,
          useValue: {
            buildUrl: (endpoint: string, attrs?: any) =>
              endpoint === 'stripeConfig'
                ? '/occ/payments/stripe/config'
                : `/occ/users/${attrs.urlParams.userId}/carts/${attrs.urlParams.cartId}/payments/stripe/intent`,
          },
        },
        { provide: UserIdService, useValue: { takeUserId: () => of('current') } },
        {
          provide: ActiveCartFacade,
          useValue: { getActiveCartId: () => of('00001') },
        },
      ],
    });
    service = TestBed.inject(StripePaymentOccService);
    http = TestBed.inject(HttpTestingController);
  });

  it('posts to the intent endpoint of the active cart', () => {
    let response: any;
    service.ensureIntent().subscribe((r) => (response = r));

    const req = http.expectOne('/occ/users/current/carts/00001/payments/stripe/intent');
    expect(req.request.method).toBe('POST');
    req.flush({ paymentIntentId: 'pi_1', clientSecret: 'cs', status: 'requires_payment_method' });

    expect(response.paymentIntentId).toBe('pi_1');
  });

  it('reads the publishable key from the config endpoint', () => {
    let response: any;
    service.getConfig().subscribe((r) => (response = r));

    const req = http.expectOne('/occ/payments/stripe/config');
    expect(req.request.method).toBe('GET');
    req.flush({ publishableKey: 'pk_test_backend' });

    expect(response.publishableKey).toBe('pk_test_backend');
  });
});
