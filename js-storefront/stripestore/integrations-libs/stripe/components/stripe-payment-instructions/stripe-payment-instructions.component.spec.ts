/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { NO_ERRORS_SCHEMA, Pipe, PipeTransform } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OutletContextData } from '@spartacus/storefront';
import { of } from 'rxjs';
import { StripePaymentInfo } from 'sap-commerce-stripe-payment/root';
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

  function setup(info?: StripePaymentInfo): void {
    TestBed.configureTestingModule({
      declarations: [
        StripePaymentInstructionsComponent,
        StubTranslatePipe,
        StubDatePipe,
      ],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        {
          provide: OutletContextData,
          useValue: { context$: of({ item: { stripePaymentInfo: info } }) },
        },
      ],
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
});
