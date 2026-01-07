# Stripe Connector for SAP Commerce Cloud

Stripe provides payment infrastructure for the internet. This Connector for SAP Commerce Cloud enables a seamless integration with Stripe's Payment Intents API, supporting secure card payments with built-in 3D Secure (SCA) compliance.

## Release Compatibility

This release is compatible with:
- SAP Commerce Cloud 2211
- SAP Commerce REST API (OCC)
- Spartacus 2211.37
- Java 17
- Stripe Java SDK

## Features

- **Card Payments**: Accept credit and debit card payments
- **Payment Intents API**: Modern payment flow with built-in SCA/3DS support
- **Webhooks**: Process Stripe events for payment confirmations and updates
- **Headless Architecture**: OCC-first design for Spartacus storefronts

## Project Structure

```
core-customize/
├── hybris/custom/stripe/
│   ├── stripecore/              # Core services, Stripe API client
│   ├── stripefacades/           # Facade layer, DTOs
│   ├── stripeocc/               # OCC REST controllers
│   ├── stripefulfilmentprocess/ # Order process actions
│   └── stripestore/             # Store configuration
├── installer/recipes/
│   └── b2c_occ_stripe/          # Installer recipe
└── manifest.json                # CCv2 deployment manifest

js-storefront/stripestore/
├── src/app/                     # Angular application
└── integrations-libs/stripe/    # Stripe integration library
```

## Extensions

| Extension | Description |
|-----------|-------------|
| `stripecore` | Core services, Stripe API client, payment commands |
| `stripefacades` | Facade layer with DTOs and converters |
| `stripeocc` | OCC REST endpoints for payment operations |
| `stripefulfilmentprocess` | Order process actions for payment authorization and capture |
| `stripestore` | Store-specific configuration and sample data |

# Installation and Usage

## Prerequisites

- SAP Commerce Cloud 2211
- Java 17
- Node.js 18+ (for Spartacus)
- Stripe account with API keys

## Installation using recipes

The Connector ships with a gradle recipe for the SAP Commerce installer:

**Recipe**: `b2c_occ_stripe` - B2C with OCC and Stripe payment functionality

Recipes are located under the `installer` folder.

### Install Commands

```bash
# Navigate to installer directory
cd HYBRIS_HOME/installer

# Setup: Create solution from templates
./install.sh -r b2c_occ_stripe setup

# Initialize: Build and initialize the platform
./install.sh -r b2c_occ_stripe initialize

# Start: Launch commerce suite instance
./install.sh -r b2c_occ_stripe start
```

## Manual Installation

1. Copy the stripe extensions to `${HYBRIS_BIN_DIR}/custom/stripe`

2. Add the following to your `localextensions.xml`:

```xml
<!-- Stripe Payment -->
<extension name="stripecore"/>
<extension name="stripefacades"/>
<extension name="stripeocc"/>
<extension name="stripefulfilmentprocess"/>
<extension name="stripestore"/>
```

3. Run the build commands:

```bash
cd hybris/bin/platform
. ./setantenv.sh
ant clean all
```

4. Start the server and update the system:

```bash
./hybrisserver.sh
ant updatesystem
```

## Configuration

Add your Stripe API keys to `local.properties`:

```properties
stripe.api.secret.key=sk_test_your_secret_key
stripe.api.public.key=pk_test_your_public_key
stripe.webhook.secret=whsec_your_webhook_secret
```

## Installing on SAP Commerce Cloud

Follow the [SAP Commerce Cloud documentation](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD) to prepare your repository for deployment.

1. Include the Stripe extensions in the `core-customize` folder
2. Update your `manifest.json` to include the extensions
3. Add Stripe API keys as environment properties in SAP Commerce Cloud Portal

# Spartacus Frontend

The Connector supports Spartacus storefronts through OCC APIs. The frontend integration is located in `js-storefront/stripestore/integrations-libs/stripe/`.

## Frontend Installation

```bash
cd js-storefront/stripestore
npm install
npm start
```

The storefront will be available at `http://localhost:4200`.

# Support

For questions, issues, or feature requests, please open an issue on [GitHub Issues](../../issues).

# Contribution

## Our Pledge

In the interest of fostering an open and welcoming environment, we as contributors and maintainers pledge to making participation in our project and our community a harassment-free experience for everyone, regardless of age, body size, disability, ethnicity, sex characteristics, gender identity and expression, level of experience, education, socio-economic status, nationality, personal appearance, race, religion, or sexual identity and orientation.

## Our Standards

Examples of behavior that contributes to creating a positive environment include:

* Using welcoming and inclusive language
* Being respectful of differing viewpoints and experiences
* Gracefully accepting constructive criticism
* Focusing on what is best for the community
* Showing empathy towards other community members

Examples of unacceptable behavior by participants include:

* The use of sexualized language or imagery and unwelcome sexual attention or advances
* Trolling, insulting/derogatory comments, and personal or political attacks
* Public or private harassment
* Publishing others' private information, such as a physical or electronic address, without explicit permission
* Other conduct which could reasonably be considered inappropriate in a professional setting

## Our Responsibilities

Project maintainers are responsible for clarifying the standards of acceptable behavior and are expected to take appropriate and fair corrective action in response to any instances of unacceptable behavior.

Project maintainers have the right and responsibility to remove, edit, or reject comments, commits, code, wiki edits, issues, and other contributions that are not aligned to this Code of Conduct, or to ban temporarily or permanently any contributor for other behaviors that they deem inappropriate, threatening, offensive, or harmful.

## Scope

This Code of Conduct applies both within project spaces and in public spaces when an individual is representing the project or its community.

## Attribution

This Code of Conduct is adapted from the [Contributor Covenant](https://www.contributor-covenant.org), version 1.4.

# License

This repository is open source and available under the MIT license.

Copyright (c) 2025 Sergio Perez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
