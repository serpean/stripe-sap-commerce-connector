/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OrderFacade } from '@spartacus/order/root';
import { OutletContextData } from '@spartacus/storefront';
import { of } from 'rxjs';
import { StripePaymentInfo } from '../model/index';
import { StripePaymentInstructionsComponent } from './stripe-payment-instructions.component';

@Pipe({ name: 'cxTranslate', standalone: false })
class StubTranslatePipe implements PipeTransform {
  transform(value: unknown): string {
    return String(value);
  }
}

@Pipe({ name: 'cxDate', standalone: false })
class StubDatePipe implements PipeTransform {
  transform(value: unknown): string {
    return 'DATE:' + String(value);
  }
}

describe('StripePaymentInstructionsComponent', () => {
  let fixture: ComponentFixture<StripePaymentInstructionsComponent>;

  function setup(
    info?: StripePaymentInfo,
    options: { outlet?: 'order' | 'component' | 'none' } = {}
  ): void {
    const outlet = options.outlet ?? 'order';
    const order = { stripePaymentInfo: info };
    const providers: unknown[] = [
      { provide: OrderFacade, useValue: { getOrderDetails: () => of(order) } },
    ];
    if (outlet === 'order') {
      providers.push({
        provide: OutletContextData,
        useValue: { context$: of({ item: order }) },
      });
    } else if (outlet === 'component') {
      // A CMS component outlet (the confirmation page) gives the component, not an order.
      providers.push({
        provide: OutletContextData,
        useValue: { context$: of({ component: { flexType: 'X' } }) },
      });
    }
    TestBed.configureTestingModule({
      declarations: [
        StripePaymentInstructionsComponent,
        StubTranslatePipe,
        StubDatePipe,
      ],
      schemas: [NO_ERRORS_SCHEMA],
      providers: providers as never[],
    });
    fixture = TestBed.createComponent(StripePaymentInstructionsComponent);
    fixture.detectChanges();
  }

  const pending: StripePaymentInfo = {
    paymentStatus: 'PENDING',
    instructionsUrl: 'https://hooks.stripe.com/voucher/abc',
    paymentReference: '12345 678 901 234',
    instructionsExpireAt: '2026-11-01T10:00:00Z',
    amountRemaining: '€12.34',
  };

  it('shows reference, amount, expiry and link when pending', () => {
    setup(pending);
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('12345 678 901 234');
    expect(text).toContain('€12.34');
    expect(text).toContain('DATE:2026-11-01T10:00:00Z');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a');
    expect(link.getAttribute('href')).toBe('https://hooks.stripe.com/voucher/abc');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener');
    expect(link.textContent).toContain('stripePayment.instructionsLink');
  });

  it('renders nothing when paid', () => {
    setup({ ...pending, paymentStatus: 'ACCEPTED' });
    expect(fixture.nativeElement.textContent.trim()).toBe('');
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
  });

  it('renders nothing without instructionsUrl', () => {
    setup({ ...pending, instructionsUrl: undefined });
    expect(fixture.nativeElement.textContent.trim()).toBe('');
  });

  it('renders nothing without payment info', () => {
    setup(undefined);
    expect(fixture.nativeElement.textContent.trim()).toBe('');
  });

  it('reads the placed order from the order facade on the confirmation page (CMS component outlet)', () => {
    setup(pending, { outlet: 'component' });
    expect(fixture.nativeElement.textContent).toContain('12345 678 901 234');
  });

  it('reads the placed order from the order facade without any outlet context', () => {
    setup(pending, { outlet: 'none' });
    expect(fixture.nativeElement.querySelector('a')).not.toBeNull();
  });
});
