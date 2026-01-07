/*
 * SPDX-FileCopyrightText: 2025 SAP Spartacus team <spartacus-team@sap.com>
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import {
  Rule,
  SchematicContext,
  Tree,
  chain,
} from '@angular-devkit/schematics';
import { NodePackageInstallTask } from '@angular-devkit/schematics/tasks';
import { Schema as StripePaymentOptions } from './schema';

const STRIPE_FEATURE_MODULE = 'StripePaymentFeatureModule';
const STRIPE_JS_PACKAGE = '@stripe/stripe-js';
const STRIPE_JS_VERSION = '^2.2.0';

const FEATURE_MODULE_PATH = 'src/app/spartacus/features/stripe/stripe-feature.module.ts';
const SPARTACUS_FEATURES_MODULE_PATH = 'src/app/spartacus/spartacus-features.module.ts';

export function addStripePayment(options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    if (options.debug) {
      context.logger.info(`Starting Stripe Payment schematic...`);
      context.logger.info(`Options: ${JSON.stringify(options, null, 2)}`);
    }

    return chain([
      addPackageJsonDependencies(options),
      createStripeFeatureModule(options),
      addStripeFeatureModuleImport(options),
      installPackages(options),
      logCompletion(options),
    ])(tree, context);
  };
}

function addPackageJsonDependencies(options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    const packageJsonPath = '/package.json';
    const packageJsonBuffer = tree.read(packageJsonPath);

    if (!packageJsonBuffer) {
      context.logger.error('Could not find package.json');
      return tree;
    }

    const packageJson = JSON.parse(packageJsonBuffer.toString('utf-8'));

    packageJson.dependencies = packageJson.dependencies || {};

    // Add @stripe/stripe-js dependency
    if (!packageJson.dependencies[STRIPE_JS_PACKAGE]) {
      packageJson.dependencies[STRIPE_JS_PACKAGE] = STRIPE_JS_VERSION;
      if (options.debug) {
        context.logger.info(`Added ${STRIPE_JS_PACKAGE}@${STRIPE_JS_VERSION}`);
      }
    }

    tree.overwrite(packageJsonPath, JSON.stringify(packageJson, null, 2));

    return tree;
  };
}

function createStripeFeatureModule(options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    // Check if file already exists
    if (tree.exists(FEATURE_MODULE_PATH)) {
      context.logger.warn(`File ${FEATURE_MODULE_PATH} already exists. Skipping creation.`);
      return tree;
    }

    const publishableKey = options.publishableKey || 'YOUR_STRIPE_PUBLISHABLE_KEY';

    const featureModuleContent = generateFeatureModuleContent(publishableKey, options.lazy);

    // Create directory if it doesn't exist
    const dirPath = FEATURE_MODULE_PATH.substring(0, FEATURE_MODULE_PATH.lastIndexOf('/'));
    if (!tree.exists(dirPath)) {
      // Tree will create directories automatically when creating a file
    }

    tree.create(FEATURE_MODULE_PATH, featureModuleContent);
    context.logger.info(`Created ${FEATURE_MODULE_PATH}`);

    return tree;
  };
}

function generateFeatureModuleContent(publishableKey: string, lazy: boolean): string {
  if (lazy) {
    return `import { NgModule } from '@angular/core';
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
    }),
    provideConfig(<StripePaymentConfig>{
      stripePayment: {
        publishableKey: '${publishableKey}',
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
`;
  } else {
    return `import { NgModule } from '@angular/core';
import { I18nConfig, provideConfig } from '@spartacus/core';
import { StripePaymentModule } from 'sap-commerce-stripe-payment';
import { StripePaymentConfig } from 'sap-commerce-stripe-payment/root';
import {
  stripePaymentTranslationChunksConfig,
  stripePaymentTranslationsEn,
} from 'sap-commerce-stripe-payment/assets';

@NgModule({
  imports: [StripePaymentModule],
  providers: [
    provideConfig(<StripePaymentConfig>{
      stripePayment: {
        publishableKey: '${publishableKey}',
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
`;
  }
}

function addStripeFeatureModuleImport(_options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    const spartacusFeaturesPath = SPARTACUS_FEATURES_MODULE_PATH;

    if (!tree.exists(spartacusFeaturesPath)) {
      context.logger.warn(
        `Could not find ${spartacusFeaturesPath}. Please manually add StripePaymentFeatureModule to your app.`
      );
      return tree;
    }

    const content = tree.read(spartacusFeaturesPath)?.toString('utf-8');
    if (!content) {
      context.logger.error(`Could not read ${spartacusFeaturesPath}`);
      return tree;
    }

    // Check if already imported
    if (content.includes(STRIPE_FEATURE_MODULE)) {
      context.logger.info(`${STRIPE_FEATURE_MODULE} is already imported.`);
      return tree;
    }

    let updatedContent = content;

    // Add import statement at the top (after other imports)
    const importStatement = `import { ${STRIPE_FEATURE_MODULE} } from './features/stripe/stripe-feature.module';`;

    // Find the last import statement and add after it
    const importRegex = /^import .+ from .+;$/gm;
    let lastImportMatch: RegExpExecArray | null = null;
    let match: RegExpExecArray | null;

    while ((match = importRegex.exec(updatedContent)) !== null) {
      lastImportMatch = match;
    }

    if (lastImportMatch) {
      const insertPosition = lastImportMatch.index + lastImportMatch[0].length;
      updatedContent =
        updatedContent.slice(0, insertPosition) +
        '\n' +
        importStatement +
        updatedContent.slice(insertPosition);
    } else {
      // If no imports found, add at the beginning
      updatedContent = importStatement + '\n' + updatedContent;
    }

    // Add to NgModule imports array
    // Find the imports array and add StripePaymentFeatureModule
    const importsArrayRegex = /imports:\s*\[([^\]]*)\]/s;
    const importsMatch = importsArrayRegex.exec(updatedContent);

    if (importsMatch) {
      const existingImports = importsMatch[1];
      const lastImportInArray = existingImports.trim().replace(/,\s*$/, '');

      // Add StripePaymentFeatureModule at the end of imports
      const newImports = lastImportInArray
        ? `${lastImportInArray},\n    ${STRIPE_FEATURE_MODULE}`
        : STRIPE_FEATURE_MODULE;

      updatedContent = updatedContent.replace(
        importsArrayRegex,
        `imports: [\n    ${newImports}\n  ]`
      );
    } else {
      context.logger.warn(
        'Could not find imports array in spartacus-features.module.ts. Please add StripePaymentFeatureModule manually.'
      );
    }

    tree.overwrite(spartacusFeaturesPath, updatedContent);
    context.logger.info(`Updated ${spartacusFeaturesPath} with ${STRIPE_FEATURE_MODULE} import`);

    return tree;
  };
}

function installPackages(options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    context.addTask(new NodePackageInstallTask());
    if (options.debug) {
      context.logger.info('Scheduled npm install task');
    }
    return tree;
  };
}

function logCompletion(_options: StripePaymentOptions): Rule {
  return (tree: Tree, context: SchematicContext) => {
    context.logger.info('');
    context.logger.info('='.repeat(60));
    context.logger.info('Stripe Payment integration setup complete!');
    context.logger.info('='.repeat(60));
    context.logger.info('');
    context.logger.info('Files created/modified:');
    context.logger.info(`  - ${FEATURE_MODULE_PATH}`);
    context.logger.info(`  - ${SPARTACUS_FEATURES_MODULE_PATH}`);
    context.logger.info('');
    context.logger.info('Next steps:');
    context.logger.info('1. Update the publishable key in stripe-feature.module.ts');
    context.logger.info('2. Ensure OCC endpoints for stripe intent/confirm are configured');
    context.logger.info('');

    return tree;
  };
}
