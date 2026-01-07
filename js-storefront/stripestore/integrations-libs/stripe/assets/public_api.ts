/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

export * from './translations/index';

import { stripePaymentTranslationsEn } from './translations/translations';

/**
 * @deprecated Please use **specific language** translations (suffixed with language code) instead,
 * like in the following example:
 *             ```diff
 *               i18n: {
 *             -   resources: stripePaymentTranslations
 *             +   resources: { en: stripePaymentTranslationsEn }
 *               }
 *             ```
 */
export const stripePaymentTranslations = {
  en: stripePaymentTranslationsEn,
};
