import { NgModule } from '@angular/core';
import { CmsConfig, I18nConfig, provideConfig } from '@spartacus/core';
import {
  STRIPE_PAYMENT_FEATURE,
  StripePaymentConfig,
  StripePaymentRootModule,
} from 'sap-commerce-stripe-payment/root';
import {
  stripePaymentTranslationChunksConfig,
  stripePaymentTranslationsEn,
} from 'sap-commerce-stripe-payment/assets';

@NgModule({
  imports: [StripePaymentRootModule],
  providers: [
    provideConfig(<CmsConfig>{
      featureModules: {
        [STRIPE_PAYMENT_FEATURE]: {
          module: () =>
            import('sap-commerce-stripe-payment').then(
              (m) => m.StripePaymentModule
            ),
        },
      },
      cmsComponents: {
        CheckoutPaymentDetails: {
          component: () =>
            import('sap-commerce-stripe-payment/components').then(
              (m) => m.StripePaymentFormComponent
            ),
        },
      },
    }),
    provideConfig(<StripePaymentConfig>{
      stripePayment: {
        publishableKey: 'pk_test_51L2c2dBLWRvZiE6aSenfb7sPSzCuYNgoxruUOjQH7Lp2hibR98h7O0fIuALD6Edt2UjfSBVjSwjwbvDPKJger0dk00VhWAwwC3',
      },
    }),
    provideConfig(<I18nConfig>{
      i18n: {
        resources: { en: stripePaymentTranslationsEn },
        chunks: stripePaymentTranslationChunksConfig,
      },
    }),
  ],
})
export class StripePaymentFeatureModule {}
