# Limpieza del conector Stripe: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** eliminar del repositorio todo lo que no es del conector y todo rastro de OPF, y dejar un proyecto de referencia que arranca y hace un checkout e2e.

**Arquitectura:** el conector son las extensiones `stripecore`, `stripefacades`, `stripeocc` y `stripefulfilmentprocess`, más la librería Angular `sap-commerce-stripe-payment`. El resto (receta, datos de ejemplo, app Spartacus, manifests, prueba e2e) es el proyecto de referencia. Las carpetas no se mueven. La plataforma de prueba vive fuera del repositorio y enlaza las carpetas del repositorio con symlinks.

**Tecnologías:** SAP Commerce 2211.55 (zip `CXCOMCL221100U_55-70007431.ZIP`), Java 21 (SapMachine), Spartacus 2211.37, Angular 19, Node 20, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-18-limpieza-conector-design.md`

## Restricciones globales

- Rama: `chore/limpieza-conector`. No se reescribe el historial de git. Los ficheros se eliminan con commits nuevos.
- Cada commit tiene **un solo tipo** de cambio: "elimina", "mueve", "sustituye datos de ejemplo" o "configura el proyecto de referencia". Cada commit compila.
- No se corrige ningún fallo del código del conector (lista en la sección 4 de la spec). Un fallo del conector que bloquea una verificación se anota en `docs/superpowers/notes/2026-10-19-limpieza-fallos-conocidos.md` con paso, error y log.
- No se cambian: `StripePaymentProvider`, `StripeClient`, `DefaultStripePaymentIntegrationService`, `StripeWebhookService`, los populators de `sapGenericPaymentInfo` y las clases de `stripefulfilmentprocess`.
- Ninguna clave de Stripe (`sk_test_…`, `pk_test_…`, `whsec_…`) entra en git. Las claves llegan por las variables de entorno `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` y `STRIPE_WEBHOOK_SECRET`.
- Criterio final: `git grep -i opf -- ':!docs'` no devuelve nada.
- Plataforma de prueba: `/Volumes/e/y/stripe-ref-platform` (fuera del repositorio). Nada de esa carpeta entra en git.

## Puntos de atención en la revisión

1. **El jar de Stripe ya no está en `lib/`:** la build tiene que descargar `com.stripe:stripe-java:30.0.0` por Maven. Si la descarga falla, la build falla. La tarea 2 lo comprueba con `ant clean all`.
2. **Arrancar el front sin `STRIPE_PUBLISHABLE_KEY`:** la app tiene que compilar y arrancar con un valor de marcador, sin romper. La tarea 4 lo comprueba.
3. **Orden de los datos de ejemplo:** la impex que pone `paymentProvider=Stripe` se tiene que importar **después** de los datos de `electronics`. Si no, la sobrescriben. La tarea 7 lo comprueba con una consulta FlexibleSearch después de `initialize`.
4. **Usuario de la prueba e2e ya existe:** si la prueba se ejecuta dos veces, el registro del usuario no puede fallar. La tarea 9 usa un email único en cada ejecución.
5. **Una referencia a OPF en ficheros que git no sigue** (por ejemplo `package-lock.json` sin regenerar): la tarea 3 regenera el lock y comprueba con `git grep` y con `grep` en `node_modules/.package-lock.json`.

---

### Task 1: Plataforma de prueba y estado inicial

Prepara la plataforma fuera del repositorio y guarda el resultado de la build **antes** de cambiar nada. Sin commit en el repositorio, excepto la nota.

**Ficheros:**
- Crear: `docs/superpowers/notes/2026-10-19-limpieza-fallos-conocidos.md`

- [ ] **Paso 1:** descomprimir el zip en `/Volumes/e/y/stripe-ref-platform`.
  Ejecutar: `unzip -q /Volumes/e/y/stripe-sap-commerce-connector/CXCOMCL221100U_55-70007431.ZIP -d /Volumes/e/y/stripe-ref-platform`
  Esperado: existe `/Volumes/e/y/stripe-ref-platform/hybris/bin/platform/build.number` con `version=2211.55`.
- [ ] **Paso 2:** enlazar las carpetas del repositorio.
  - `ln -s /Volumes/e/y/stripe-sap-commerce-connector/core-customize/hybris/custom /Volumes/e/y/stripe-ref-platform/hybris/bin/custom`
  - `ln -s /Volumes/e/y/stripe-sap-commerce-connector/core-customize/installer/recipes/b2c_occ_stripe /Volumes/e/y/stripe-ref-platform/installer/recipes/b2c_occ_stripe`
- [ ] **Paso 3:** compilar con la receta y Java 21.
  Ejecutar: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && cd /Volumes/e/y/stripe-ref-platform/installer && ./install.sh -r b2c_occ_stripe -A local_property:initialpassword.admin=nimda`
  Esperado: termina, o falla con un error que se puede anotar.
- [ ] **Paso 4:** escribir la nota con el resultado: versión de Java, comando, éxito o error con las últimas 30 líneas del log. Encabezado de la nota: `# Fallos conocidos encontrados durante la limpieza`. Si la build en 2211.55 falla por motivos que no son de la limpieza, la nota lo dice y las tareas siguientes usan la plataforma 2211.37 de `/Volumes/e/y/stripeopf/hybris` con los mismos symlinks.
  Caso conocido: `ant all` también compila `testsrc`, y `StripePaymentControllerTest.java:31` llama a `createOrUpdateIntent()`, un método que no existe. Si la build falla solo por eso, la tarea 2 elimina ese test (es un "elimina"; el proyecto 2 escribe los tests nuevos) y la nota lo registra.
- [ ] **Paso 5: commit**
  ```bash
  git add docs/superpowers/notes/2026-10-19-limpieza-fallos-conocidos.md
  git commit -m "docs: record baseline build before cleanup"
  ```

### Task 2: Eliminar `stripestore`, el jar de Stripe y los ficheros de IDE

**Ficheros:**
- Eliminar: `core-customize/hybris/custom/stripe/stripestore/` (entera)
- Eliminar: `core-customize/hybris/custom/stripe/stripecore/lib/stripe-java-30.0.0.jar`, `core-customize/hybris/custom/stripe/stripecore/lib/.lastupdate`
- Eliminar, solo si la tarea 1 lo confirma: `core-customize/hybris/custom/stripe/stripeocc/testsrc/com/stripe/controllers/StripePaymentControllerTest.java`
- Eliminar: todos los `.classpath` y `.settings/` que sigue git (15 ficheros; listarlos con `git ls-files | grep -E '/\.classpath$|/\.settings/'`)
- Modificar: `.gitignore` (añadir `.classpath`, `.settings/`, `.project`, y `core-customize/hybris/custom/stripe/stripecore/lib/` porque Maven descarga ahí)
- Modificar: `core-customize/installer/recipes/b2c_occ_stripe/build.gradle:211-214` (quitar las líneas comentadas de `stripestore` y su TODO)

- [ ] **Paso 1:** `git rm -r` de las rutas de arriba.
- [ ] **Paso 2:** editar `.gitignore` y la receta.
- [ ] **Paso 3:** comprobar que nada referencia `stripestore` como extensión.
  Ejecutar: `git grep -n "stripestore" -- core-customize`
  Esperado: sin resultados.
- [ ] **Paso 4:** compilar.
  Ejecutar: `cd /Volumes/e/y/stripe-ref-platform/hybris/bin/platform && . ./setantenv.sh && ant clean all`
  Esperado: `BUILD SUCCESSFUL`, y existe `core-customize/hybris/custom/stripe/stripecore/lib/stripe-java-30.0.0.jar` descargado por Maven (no seguido por git: `git status --short` no lo muestra).
- [ ] **Paso 5: commit** — `git commit -m "chore: remove stripestore copy, committed Stripe jar and IDE files"`

### Task 3: Eliminar OPF del front y el endpoint `stripeConfirm` sin backend

**Ficheros:**
- Modificar: `js-storefront/stripestore/package.json` (quitar `@spartacus/opf`) y regenerar `js-storefront/stripestore/package-lock.json`
- Eliminar: `js-storefront/stripestore/src/app/spartacus/features/opf/opf-feature.module.ts`
- Modificar: `js-storefront/stripestore/src/app/spartacus/spartacus-features.module.ts` (quitar import y uso de `OpfFeatureModule`)
- Modificar: `js-storefront/stripestore/src/app/spartacus/features/order/order-wrapper.module.ts` (quitar `OpfOrderModule`; queda solo `OrderModule`)
- Eliminar: `js-storefront/stripestore/src/styles/spartacus/opf.scss`
- Modificar: `js-storefront/stripestore/angular.json:82` y `:173` (quitar `src/styles/spartacus/opf.scss`)
- Modificar: `js-storefront/stripestore/integrations-libs/stripe/package.json` (quitar la palabra clave `"opf"`)
- Modificar: `js-storefront/stripestore/integrations-libs/stripe/root/config/stripe-occ.config.ts:15`, `js-storefront/stripestore/integrations-libs/stripe/root/model/stripe-payment.model.ts:22`, `js-storefront/stripestore/src/app/spartacus/spartacus-configuration.module.ts:20` (quitar `stripeConfirm`)
- Eliminar: `core-customize/hybris/custom/stripe/stripeocc/src/com/stripe/dto/StripeConfirmRequest.java`, `core-customize/hybris/custom/stripe/stripeocc/src/com/stripe/controllers/StripeoccController.java`, `core-customize/hybris/custom/stripe/stripeocc/testsrc/com/stripe/controllers/StripepaymentoccTest.java`

Nota: `stripeocc-items.xml` y `stripeocc-beans.xml` se quedan. Solo tienen ejemplos comentados y la estructura de la extensión los espera.

- [ ] **Paso 1:** hacer los cambios de arriba.
- [ ] **Paso 2:** regenerar dependencias.
  Ejecutar: `cd js-storefront/stripestore && rm -rf node_modules && npm install && npm ci`
  Esperado: sin errores; `grep -c '@spartacus/opf' package-lock.json` devuelve `0`.
- [ ] **Paso 3:** compilar la librería y la app.
  Ejecutar: `npx ng build sap-commerce-stripe-payment && npx ng build`
  Esperado: las dos terminan sin errores.
- [ ] **Paso 4:** comprobar el backend con `ant clean all` en la plataforma. Esperado: `BUILD SUCCESSFUL`.
- [ ] **Paso 5:** `git grep -in "opf" -- js-storefront core-customize/hybris/custom/stripe` → sin resultados.
- [ ] **Paso 6: commit** — `git commit -m "chore: remove SAP OPF from storefront and unused stripeConfirm endpoint"`

### Task 4: Clave pública de Stripe fuera del código

La clave `pk_test_…` de `stripe-feature.module.ts:36` sale del código. La app de referencia la lee de un fichero generado desde `STRIPE_PUBLISHABLE_KEY`.

**Ficheros:**
- Crear: `js-storefront/stripestore/scripts/write-stripe-config.mjs`
- Crear (generado, en `.gitignore`): `js-storefront/stripestore/src/app/spartacus/features/stripe/stripe-keys.generated.ts`
- Modificar: `js-storefront/stripestore/src/app/spartacus/features/stripe/stripe-feature.module.ts` (importar `STRIPE_PUBLISHABLE_KEY` desde `./stripe-keys.generated`)
- Modificar: `js-storefront/stripestore/package.json` (scripts `prestart`, `prebuild` y `pretest` que ejecutan `node scripts/write-stripe-config.mjs`)
- Modificar: `.gitignore` (añadir `js-storefront/stripestore/src/app/spartacus/features/stripe/stripe-keys.generated.ts`)

**Interfaces:**
- Produce: `export const STRIPE_PUBLISHABLE_KEY: string` en `stripe-keys.generated.ts`. Valor: `process.env.STRIPE_PUBLISHABLE_KEY` o, si no existe, `'pk_test_REPLACE_ME'`. El script muestra un aviso en la consola cuando usa el marcador.

- [ ] **Paso 1:** escribir el script y cambiar el módulo.
- [ ] **Paso 2:** sin variable: `unset STRIPE_PUBLISHABLE_KEY; npm run build` → compila y muestra el aviso.
- [ ] **Paso 3:** con variable: `STRIPE_PUBLISHABLE_KEY=pk_test_dummy npm run build && grep -c pk_test_dummy src/app/spartacus/features/stripe/stripe-keys.generated.ts` → `1`.
- [ ] **Paso 4:** `git grep -n "pk_test_51" ` → sin resultados.
- [ ] **Paso 5: commit** — `git commit -m "chore: read Stripe publishable key from environment in reference app"`

### Task 5: Datos de ejemplo oficiales de Spartacus

**Bloqueada** hasta que el propietario aporte el paquete oficial `spartacussampledata` 2211.37 de SAP. Si no está, pasar a la tarea 6 y volver después.

**Ficheros:**
- Sustituir: `core-customize/hybris/custom/spartacus/spartacussampledata/` por el contenido del paquete oficial, sin cambios.

- [ ] **Paso 1:** `git rm -r core-customize/hybris/custom/spartacus/spartacussampledata` y copiar el paquete oficial en la misma ruta. Quitar del paquete solo los ficheros que ignora `.gitignore` (`.classpath`, `.settings/`).
- [ ] **Paso 2:** `git grep -in "opf\|stripe" -- core-customize/hybris/custom/spartacus` → sin resultados.
- [ ] **Paso 3:** comprobar que la receta tiene las extensiones que pide el paquete en su `extensioninfo.xml` (por ejemplo `addonsupport`). Si falta alguna, añadirla en la tarea 7, no aquí.
- [ ] **Paso 4:** `ant clean all` → `BUILD SUCCESSFUL`.
- [ ] **Paso 5: commit** — `git commit -m "chore: replace custom spartacussampledata with official SAP package 2211.37"`

### Task 6: Comprobación de OPF

- [ ] **Paso 1:** `git grep -il opf -- ':!docs'` → sin resultados. Si queda algo, eliminarlo en esta tarea con un commit "elimina".
- [ ] **Paso 2:** `grep -rli opf js-storefront/stripestore/node_modules/.package-lock.json` → sin resultados.

### Task 7: Configuración del proyecto de referencia

**Ficheros:**
- Crear: extensión `core-customize/hybris/custom/reference/stripesampledata/` con `extensioninfo.xml` (`requires-extension` `electronicsstore`, `spartacussampledata`, `stripecore`), `project.properties`, `resources/stripesampledata-spring.xml`, `src/com/stripe/sampledata/setup/StripeSampleDataSystemSetup.java` y `resources/stripesampledata/import/sampledata/stores/electronics/store.impex`
- Modificar: `core-customize/installer/recipes/b2c_occ_stripe/build.gradle` (propiedades y extensiones)

**Interfaces:**
- `StripeSampleDataSystemSetup` usa `@SystemSetup(extension = "stripesampledata")` con un método `@SystemSetup(type = Type.PROJECT, process = Process.ALL)` que importa `store.impex`.
- `store.impex` pone `paymentProvider=Stripe` en el `BaseStore` con `uid=electronics`.

- [ ] **Paso 1:** en la receta, sustituir las propiedades `stripe.api.secret.key` y `stripe.api.public.key` por los nombres que el código lee hoy: `stripe.secret.key`, `stripe.publishable.key` y `stripe.webhook.secret`. Los valores vienen de `System.getenv('STRIPE_SECRET_KEY') ?: ''`, `System.getenv('STRIPE_PUBLISHABLE_KEY') ?: ''` y `System.getenv('STRIPE_WEBHOOK_SECRET') ?: ''`. No se toca `DefaultStripeConfigurationService` (spec, sección 7.2).
- [ ] **Paso 2:** añadir a la receta `extName 'stripesampledata'` y las extensiones que faltan según la tarea 5, paso 3.
- [ ] **Paso 3:** crear la extensión `stripesampledata`.
- [ ] **Paso 4:** inicializar.
  Ejecutar: `cd /Volumes/e/y/stripe-ref-platform/installer && STRIPE_SECRET_KEY=… STRIPE_PUBLISHABLE_KEY=… ./install.sh -r b2c_occ_stripe initialize`
  Esperado: termina sin errores de impex.
- [ ] **Paso 5:** comprobar el proveedor en la HAC (FlexibleSearch): `SELECT {paymentProvider} FROM {BaseStore} WHERE {uid}='electronics'` → `Stripe`.
- [ ] **Paso 6: commit** — `git commit -m "chore: configure reference project with Stripe on electronics store"`

### Task 8: Arrancar el servidor y el storefront

- [ ] **Paso 1:** arrancar el servidor: `cd /Volumes/e/y/stripe-ref-platform/installer && ./install.sh -r b2c_occ_stripe start`. Esperado: `https://localhost:9002/occ/v2/electronics-spa/basestores/electronics` responde 200.
- [ ] **Paso 2:** arrancar el storefront: `cd js-storefront/stripestore && STRIPE_PUBLISHABLE_KEY=… npm start`. Esperado: `http://localhost:4200/electronics-spa/en/USD` carga la home.
- [ ] **Paso 3:** prueba manual: añadir un producto al carrito, llegar al paso de pago y ver el formulario de Stripe.
- [ ] **Paso 4:** si algo falla en datos, receta o configuración de referencia, corregirlo con un commit "configura el proyecto de referencia". Si falla el código del conector, anotarlo en la nota de fallos conocidos y seguir.

### Task 9: Prueba e2e de checkout con Playwright

**Ficheros:**
- Modificar: `js-storefront/stripestore/package.json` (devDependency `@playwright/test`; script `"e2e": "playwright test"`)
- Crear: `js-storefront/stripestore/playwright.config.ts` (`baseURL` desde `E2E_BASE_URL`, por defecto `http://localhost:4200`; `ignoreHTTPSErrors: true`; un solo navegador, Chromium)
- Crear: `js-storefront/stripestore/e2e/support/occ.ts`
- Crear: `js-storefront/stripestore/e2e/checkout-card.spec.ts`
- Modificar: `.gitignore` (añadir `js-storefront/stripestore/test-results/` y `js-storefront/stripestore/playwright-report/`)

**Interfaces:**
- `e2e/support/occ.ts` exporta `registerCustomer(): Promise<{ email: string; password: string }>`. Pide un token `client_credentials` con el cliente OAuth `mobile_android` / `secret` en `https://localhost:9002/authorizationserver/oauth/token` y hace `POST /occ/v2/electronics-spa/users`. El email es único en cada ejecución: `e2e+<timestamp>@example.com`. La URL de OCC viene de `E2E_OCC_URL`, por defecto `https://localhost:9002`.

- [ ] **Paso 1:** escribir la prueba `checkout con tarjeta sin 3DS termina en confirmación de pedido`:
  1. `registerCustomer()`, después login por la interfaz.
  2. Ir a un producto de `electronics-spa` con stock y añadirlo al carrito.
  3. Checkout: dirección nueva, primer modo de envío.
  4. En el iframe del Payment Element de Stripe: tarjeta `4242 4242 4242 4242`, caducidad `12/34`, CVC `123`. El código postal, si lo pide, es `10115`.
  5. Pulsar el botón de pago o de realizar pedido.
  6. Comprobar: la URL contiene `order-confirmation` y se ve un número de pedido no vacío.
- [ ] **Paso 2:** instalar el navegador: `npx playwright install chromium`.
- [ ] **Paso 3:** con el servidor y el storefront arrancados (tarea 8), ejecutar `npm run e2e`.
  Esperado: PASS.
- [ ] **Paso 4:** si falla por el código del conector: anotar paso, error y log en la nota de fallos conocidos. Marcar la prueba con `test.fail()` y una anotación `{ type: 'known-issue', description: '<resumen> — ver docs/superpowers/notes/2026-10-19-limpieza-fallos-conocidos.md' }`, de forma que `npm run e2e` pase mientras el fallo exista. Si falla por la referencia, corregirlo como en la tarea 8, paso 4.
- [ ] **Paso 5:** ejecutar `npm run e2e` dos veces seguidas. Esperado: el mismo resultado las dos veces.
- [ ] **Paso 6: commit** — `git commit -m "test: add Playwright e2e checkout test to reference project"`

### Task 10: README y cambios incompatibles

**Ficheros:**
- Modificar: `README.md`
- Crear: `docs/migration/cleanup.md` (dentro de `docs/` para poder nombrar los paquetes eliminados sin romper el criterio de `git grep`; el README enlaza a este fichero)

- [ ] **Paso 1:** README, sección "Project Structure": dos tablas, "Connector (published)" y "Reference project (not published)", con las rutas de la sección 2 de la spec. Quitar `stripestore` de la tabla "Extensions".
- [ ] **Paso 2:** README, sección "Configuration": los nombres de propiedades que lee el código (`stripe.secret.key`, `stripe.publishable.key`, `stripe.webhook.secret`, `stripe.capture.mode`, `stripe.statement.descriptor`, `stripe.descriptor.suffix`, `stripe.capture.manual.methods`) y las variables de entorno de la referencia.
- [ ] **Paso 3:** README, sección nueva "Reference project": cómo montar la plataforma con symlinks (tarea 1), arrancar (tarea 8) y ejecutar la prueba e2e (tarea 9).
- [ ] **Paso 4:** `docs/migration/cleanup.md`, título "Breaking changes in the cleanup": se elimina `stripestore`; se elimina la dependencia `@spartacus/opf` y `OpfOrderModule`; se elimina el endpoint `stripeConfirm` del front; la clave pública ya no está en el código; la configuración de referencia usa `stripe.secret.key` y `stripe.publishable.key`.
- [ ] **Paso 5:** comprobar `git grep -i opf -- ':!docs'` → sin resultados.
- [ ] **Paso 6: commit** — `git commit -m "docs: document connector vs reference project and breaking changes"`
