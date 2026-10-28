# Verificación rápida y demo bajo demanda: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que verificar una tarea tarde minutos y no pare el servidor, y que el propietario pueda levantar una demo de la última versión publicada cuando la necesite.

**Architecture:**
- **Parte A (tareas 1-5):** cambia cómo se prueba en local:
  - pruebas unitarias con Gradle fuera de la plataforma;
  - PostgreSQL en Docker con un tenant `junit` propio;
  - Playwright dividido en proyectos `parallel`, `serial` y `slow`, con etiquetas por área y una comprobación previa del entorno.
- **Parte B (tareas 6-9):**
  - el servidor de la demo construye y guarda la imagen de cada versión;
  - Coolify la despliega con docker compose;
  - Cloudflare Tunnel y Access publican y protegen `demo.serpean.dev`;
  - dos workflows de GitHub crean y borran el servidor de Hetzner.

**Tech Stack:** SAP Commerce 2211.55, Java 17 (SapMachine), Gradle (wrapper), JUnit 4 + Mockito, PostgreSQL 16, Playwright, Node 22, Docker Compose, Coolify (API v1), Hetzner Cloud API (`hcloud`), Cloudflare (Tunnel, Access, R2), GHCR privado, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-28-verificacion-rapida-y-demo-design.md`

## Global Constraints

- **Commits:** `git -c user.name=serpean -c user.email=sergi9p@gmail.com commit`, sin metadatos de Claude.
- **Secretos:** nunca se imprimen, nunca se copian y nunca entran en git. Esto incluye `.env`, `js-storefront/stripestore/.npmrc` y todos los tokens. En los workflows, todo secreto va por `env:` y nunca dentro de `run:` con `${{ }}`.
- **Binarios de SAP** (ZIP de la plataforma, jars, imagen de Commerce y paquetes `@spartacus`): nunca en git ni en un registro o bucket público. Imágenes en GHCR privado (`ghcr.io/serpean/...`, visibilidad private).
- **Plataforma de referencia:** está fuera del repo, en `/Volumes/e/y/stripe-ref-platform` (`hybris/bin/custom` es un enlace a este repo). Los cambios en ella se hacen con scripts del repo y se documentan en el README.
- **Procesos:** no matar ni reiniciar procesos que la tarea no haya arrancado (comprobar antes con `ps -p <pid> -o command=`). `stripe listen` y `ng serve --port 4300` son del propietario.
- **E2E en local:** `E2E_BASE_URL=http://localhost:4300`. El puerto `:4200` es de otra aplicación.
- **Cobertura:** ningún caso e2e de hoy desaparece. Cada uno sigue en `parallel`, `serial` o `slow`.
- **README:** en inglés sencillo, con frases cortas.
- **Fuera del alcance:** CI con e2e o Java en GitHub, demo pública, demo siempre encendida, enseñar ramas o `main` en la demo, CCv2.

## Review Focus

1. **Un spec en paralelo que cambia la configuración global del servidor** (modo de captura, plazos) rompe a los demás workers. Esperado: solo el proyecto `serial` puede llamar a `setConfig`. Test en la tarea 4.
2. **Un reintento (`retries: 1`) de un spec que ya creó un pedido.** Esperado: el reintento usa un carrito y un cliente nuevos, y las comprobaciones buscan por su propio carrito. Test en la tarea 4.
3. **Un fichero cambiado que el selector de áreas no conoce.** Esperado: se ejecuta toda la batería, nunca nada. Test en la tarea 5.
4. **"Demo up" falla a mitad.** Esperado: el servidor de Hetzner se borra de todas formas, así que no queda nada facturando, y una imagen incompleta nunca se publica con su etiqueta final. Tests en las tareas 7 y 9.
5. **Volver de PostgreSQL a HSQLDB** cuando Docker no está disponible. Esperado: el script lo revierte y los datos de HSQLDB siguen intactos. Test en la tarea 2.

---

### Task 1: Pruebas unitarias sin plataforma (Gradle)

**Files:**
- Create: `tools/fast-unit-tests/settings.gradle.kts`, `tools/fast-unit-tests/build.gradle.kts`, wrapper de Gradle (`gradlew`, `gradle/wrapper/*`)
- Create: `scripts/dev/unit-tests.sh`
- Modify: `README.md`, sección "Unit and integration tests"

**Interfaces:**
- Produces: `scripts/dev/unit-tests.sh [--platform <dir>]`. Por defecto usa `/Volumes/e/y/stripe-ref-platform` o la variable `HYBRIS_PLATFORM`. Ejecuta todas las clases `@UnitTest` de `stripecore`, `stripefacades` y `stripeocc`, y termina con código distinto de 0 si alguna falla.

Datos: hay 43 clases `@UnitTest` (34 en stripecore, 6 en stripefacades y 3 en stripeocc), que suman 486 pruebas. Ninguna hereda de clases base de la plataforma: usan Mockito y los modelos de SAP solo como jars.

- [ ] **Step 1:** el build compila `src`, `web/src` (stripeocc) y `testsrc` de las tres extensiones. El classpath sale de la plataforma local, y nada se copia al repo:
  - `hybris/bin/**/bin/*.jar` y `hybris/bin/**/lib/*.jar`;
  - `platform/bootstrap/bin/*.jar` (`models.jar` y otros);
  - las carpetas `classes` de las extensiones de SAP que haga falta;
  - `stripecore/lib` (stripe-java y gson).

  Las clases de integración (`@IntegrationTest`) se excluyen por anotación (JUnit 4 categories o un filtro por anotación). Si falta `models.jar`, falla con el mensaje "run ant all once in the platform".
- [ ] **Step 2:** ejecuta `scripts/dev/unit-tests.sh` con el servidor SAP arrancado. Esperado: 486 pruebas y 0 fallos, el servidor sigue arrancado, y una segunda ejecución sin cambios tarda menos de 60 s (anota el tiempo real en el informe).
- [ ] **Step 3:** rompe a propósito una aserción en una prueba de `stripefacades`, ejecuta el script y comprueba que falla y la nombra. Después deshaz el cambio.
- [ ] **Step 4:** README: el comando nuevo va primero y `ant unittests` queda como alternativa. `.gitignore`: `tools/fast-unit-tests/build/` y `.gradle/`.
- [ ] **Step 5:** commit `test: run the connector unit tests with Gradle, without starting the platform`.

### Task 2: PostgreSQL en Docker para la plataforma de referencia

**Files:**
- Create: `tools/refdb/docker-compose.yml`, `tools/refdb/init/01-databases.sql`, `scripts/dev/refdb.sh`
- Modify: `README.md` ("Run the reference project", pasos 1 y 3, y "Unit and integration tests")

**Interfaces:**
- Produces: `scripts/dev/refdb.sh up|down|use-postgres|use-hsqldb|status`.
  - `up`: arranca `postgres:16` con el nombre de contenedor `stripe-refdb`, el puerto `5433` del host y el volumen `stripe-refdb-data`. Crea las bases `hybris` y `hybris_junit`, con el usuario `hybris`.
  - `use-postgres`: escribe en `$HYBRIS_PLATFORM/hybris/config/local.properties`, entre las marcas `# >>> refdb` y `# <<< refdb`, las claves `db.url`, `db.driver`, `db.username`, `db.password` y `db.tableprefix`. Además crea `local_tenant_junit.properties` con las mismas claves hacia `hybris_junit`.
  - `use-hsqldb`: quita solo esas marcas y ese fichero.
  - La contraseña de la base es local y fija (`hybris`). No es un secreto de producción, pero no se imprime.

- [ ] **Step 1:** ejecuta `refdb.sh up`, `refdb.sh use-postgres` y `ant initialize` (las instrucciones de la tarea lo autorizan: la base HSQLDB no se toca, solo se deja de usar). Después ejecuta `ant yunitinit` y arranca el servidor. Esperado: la HAC responde y el storefront de `:4300` permite un pedido con tarjeta.
- [ ] **Step 2:** con el servidor arrancado, ejecuta `ant integrationtests -Dtestclasses.extensions=stripecore,stripefacades,stripeocc`. Esperado: 38 pruebas, 0 fallos, y el servidor sigue respondiendo.
- [ ] **Step 3 (Review Focus 5):** ejecuta `refdb.sh use-hsqldb`, comprueba que `local.properties` queda igual que antes del paso 1 (con `diff` contra una copia guardada en el scratchpad) y vuelve a `use-postgres`.
- [ ] **Step 4:** README: pasos del proyecto de referencia con PostgreSQL. HSQLDB queda como alternativa sin Docker.
- [ ] **Step 5:** commit `chore: PostgreSQL in Docker for the reference platform, with its own junit tenant`.

### Task 3: Comprobación previa del entorno e2e

**Files:**
- Create: `js-storefront/stripestore/e2e/support/preflight.ts`
- Modify: `js-storefront/stripestore/e2e/global-setup.ts`, `README.md` (sección e2e)

**Interfaces:**
- Produces: `preflight(): Promise<void>`. Hace tres comprobaciones, cada una con 10 s de límite:
  1. la URL base responde con el `<title>` de `js-storefront/stripestore/src/index.html`;
  2. `E2E_OCC_URL` + `/hac/` responde con 200 o 302;
  3. `stripe listen ... --forward-to` está en marcha (la comprobación que ya existe, movida aquí).

  Si una falla, lanza un error que nombra lo que falla y el valor usado, por ejemplo: `E2E_BASE_URL http://localhost:4200 does not serve the stripestore app (title "MutuaPay local")`.

- [ ] **Step 1:** `E2E_BASE_URL=http://localhost:4200 npx playwright test e2e/webhooks/invalid-signature.spec.ts`. Esperado: falla en menos de 30 s con el mensaje de la URL base.
- [ ] **Step 2:** `E2E_BASE_URL=http://localhost:4399` (sin servidor). Esperado: falla en menos de 30 s ("does not answer").
- [ ] **Step 3:** con `E2E_BASE_URL=http://localhost:4300`, el mismo spec pasa.
- [ ] **Step 4:** commit `test(e2e): fail fast when the storefront, the HAC or stripe listen is not ready`.

### Task 4: Proyectos `parallel`, `serial` y `slow`, etiquetas y reintento

**Files:**
- Modify: `js-storefront/stripestore/playwright.config.ts`, los specs de `e2e/**`, `e2e/support/backend.ts` (`setConfig`), `js-storefront/stripestore/package.json`, `README.md`

**Interfaces:**
- Consumes: `preflight()` (tarea 3).
- Produces:
  - **Etiquetas por área**, en el detalle de cada test (`{ tag: [...] }`): `@card`, `@redirect`, `@sepa`, `@multibanco`, `@bank-transfer`, `@after-purchase`, `@webhooks`, `@line-items`.
  - **Proyectos de Playwright:**
    - `parallel`: todo lo que no está en los otros dos;
    - `serial`: `after-purchase/capture-automatic`, `checkout/webhook-amount-mismatch`, `checkout/race`, y cualquier spec que llame a `setConfig`;
    - `slow`: los specs que esperan de verdad a Stripe sin una entrada de prueba con resultado inmediato.
  - **Scripts npm:**
    - `e2e`: ejecuta `parallel` con `--workers=4`, después `serial` con `--workers=1` y después `slow` con `--workers=2`. Si una fase falla, sigue con las demás y el código de salida final es el peor;
    - `e2e:area`: `--grep` con las etiquetas que recibe, en los tres proyectos y con los mismos workers;
    - `e2e:fast`: `parallel` y `serial` (sin `slow`).
  - **Configuración:** `retries: 1`. Sin `E2E_VIDEO=on`: `trace: 'on-first-retry'`, `video: 'on-first-retry'`. Con `E2E_VIDEO=on`: todo `'on'`, como hoy.

- [ ] **Step 1 (Review Focus 1):** `setConfig` lanza un error si `test.info().project.name !== 'serial'`. Ejecuta un spec de `parallel` que llame a `setConfig` (temporal, en el scratchpad o en una rama de prueba). Esperado: el error dice "setConfig is only allowed in the serial project".
- [ ] **Step 2:** sustituye las esperas reales por entradas de prueba de Stripe con resultado inmediato, consultando la documentación de Stripe:
  - Multibanco pagado: email con `succeed_immediately`. `multibanco-paid` deja de subir el plazo a 900 s y sale de `serial`.
  - SEPA: el IBAN de prueba que se resuelve antes, si existe. Si no, los specs de SEPA van a `slow`.

  Anota en el informe qué spec acaba en cada proyecto y por qué.
- [ ] **Step 3 (Review Focus 2):** cada test crea su propio cliente y su propio carrito dentro del test, no a nivel de fichero, así que un reintento empieza de cero. Revisa los specs que guardan estado en `beforeAll` o en variables de módulo y corrígelos. Comprueba con `--retries=1 --repeat-each=2` sobre `card-no-3ds` y `bank-transfer`: todo pasa y cada repetición tiene su propio pedido.
- [ ] **Step 4:** el stock: `refillTestStock` sube a 500 el stock de los productos candidatos en el global-setup. Comprueba que 4 workers no lo agotan (como mucho unos 60 pedidos). Si hace falta, sube `MIN_TEST_STOCK`.
- [ ] **Step 5:** ejecuta `npm run e2e` dos veces con `E2E_BASE_URL=http://localhost:4300`. Esperado: todo verde, o como mucho un test que pasa en el reintento (dilo en el informe). Anota la duración de cada fase. Objetivo: `parallel` más `serial` en unos 15 minutos.
- [ ] **Step 6:** ejecuta `E2E_VIDEO=on npm run e2e:fast`. Esperado: hay vídeos para todos los tests.
- [ ] **Step 7:** README: proyectos, scripts, `retries` y vídeo. Corrige también la lista de valores de `TEST_TIMINGS`, que hoy está incompleta en el README.
- [ ] **Step 8:** commit `test(e2e): parallel, serial and slow projects, area tags and one retry`.

### Task 5: Elegir las e2e según los ficheros cambiados

**Files:**
- Create: `js-storefront/stripestore/e2e/select/areas.json`, `js-storefront/stripestore/e2e/select/select-areas.mjs`, `js-storefront/stripestore/e2e/select/select-areas.test.mjs`
- Modify: `js-storefront/stripestore/package.json` (`e2e:changed`), `README.md`

**Interfaces:**
- Consumes: las etiquetas y `e2e:area` (tarea 4).
- Produces:
  - `select-areas.mjs [--base <ref>]`: el valor por defecto es `origin/main`. Lee `git diff --name-only <base>...HEAD` y los ficheros sin commit, e imprime las etiquetas, una por línea, o `ALL`.
  - `areas.json`: una lista ordenada de `{ "pattern": "<glob>", "tags": ["@sepa"] }`.
  - `npm run e2e:changed`: ejecuta `e2e:area` con esas etiquetas, o `e2e` si sale `ALL`.

- [ ] **Step 1: tests del selector** (con `node --test`):
  - `README.md` solo → ninguna etiqueta, y `e2e:changed` dice "no e2e needed" y termina en 0;
  - `e2e/support/checkout.ts` → `ALL`;
  - `e2e/global-setup.ts` → `ALL`;
  - un fichero de `stripecore/src/com/stripe/core/customer/` → `@bank-transfer`;
  - un fichero de `integrations-libs/stripe/root/stripe-payment-instructions/` → `@multibanco` y `@bank-transfer`;
  - **(Review Focus 3)** un fichero Java que no está en `areas.json` → `ALL`.
- [ ] **Step 2:** ejecuta los tests y comprueba que fallan. Después escribe el selector y el mapa: cada paquete Java del conector y cada carpeta de la librería tiene al menos una regla. Ejecuta los tests: pasan.
- [ ] **Step 3:** prueba real: cambia un comentario en un fichero de SEPA y ejecuta `npm run e2e:changed`. Esperado: solo corren los tests `@sepa`. Mide el ciclo completo (`unit-tests.sh`, `ant integrationtests` y `e2e:changed`) con el servidor arrancado. Objetivo del spec: menos de 10 minutos, sin parar el servidor. Anota el tiempo real. Deshaz el cambio.
- [ ] **Step 4:** README: el ciclo de una tarea es `scripts/dev/unit-tests.sh`, después `ant integrationtests` y después `npm run e2e:changed`. Antes de publicar, `npm run e2e`.
- [ ] **Step 5:** commit `test(e2e): run only the areas that the branch changes`.

### Task 6: Preparación de la demo por el propietario (guía)

Este paso lo hace el propietario a mano. El agente escribe la guía y la comprueba, pero no crea cuentas ni tokens.

**Files:**
- Create: `docs/demo/setup.md`

**Interfaces:**
- Produces: los nombres de los secretos de GitHub Actions que usan las tareas 7-9:
  - `HCLOUD_TOKEN`;
  - `COOLIFY_URL` (`https://coolify.serpean.dev`) y `COOLIFY_TOKEN`;
  - `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` y `R2_BUCKET`;
  - `CF_API_TOKEN`, `CF_ACCOUNT_ID`, `CF_ZONE_ID` y `CF_TUNNEL_TOKEN`;
  - `CF_ACCESS_CLIENT_ID` y `CF_ACCESS_CLIENT_SECRET` (service token, para que el workflow compruebe la demo detrás de Access);
  - `STRIPE_DEMO_SECRET_KEY`, `STRIPE_DEMO_PUBLISHABLE_KEY` y `STRIPE_DEMO_WEBHOOK_SECRET`;
  - `STRIPE_DEMO_WEBHOOK_ENDPOINT_ID` y `STRIPE_DEMO_WEBHOOK_ADMIN_KEY` (clave restringida que solo puede escribir webhook endpoints);
  - `GHCR_PULL_TOKEN`;
  - `SAP_NPM_AUTH` (ya existe);
  - `DEMO_SSH_PRIVATE_KEY`, que es la clave que Coolify y el workflow usan para entrar en el servidor.

- [ ] **Step 1:** la guía, paso a paso:
  1. crear el bucket R2 `stripe-demo` (privado) y su token, solo para ese bucket;
  2. subir el ZIP de SAP a `sap/CXCOMCL221100U_55.ZIP`;
  3. crear el token de Hetzner y el de la API de Coolify;
  4. crear el túnel `stripe-demo` y el registro `demo.serpean.dev`;
  5. crear la aplicación de Access para `demo.serpean.dev`, con el email del propietario, y una política *bypass* para la ruta del webhook (`/occ/v2/*/payment/stripe/webhook`, con la ruta exacta comprobada en el código del conector);
  6. crear el service token de Access;
  7. crear el webhook de Stripe hacia `https://demo.serpean.dev/<ruta>` con los eventos de la lista del README, desactivado, y su clave restringida;
  8. dar de alta la clave SSH en Coolify;
  9. guardar cada secreto en GitHub con `gh secret set <NAME>`, que lee el valor de stdin.
- [ ] **Step 2:** comprueba cada ruta y cada nombre de evento contra el código y el README. Usa la documentación oficial de Cloudflare, Coolify y Hetzner para los nombres de las pantallas y de la API.
- [ ] **Step 3:** commit `docs: owner setup for the on-demand demo`. Después, **para y pide al propietario** que siga la guía. Las tareas 7-9 necesitan esos secretos para sus pruebas reales.

### Task 7: Imagen y volcado de una versión, construidos en el servidor de la demo

**Files:**
- Create: `demo/image/Dockerfile`, `demo/image/build-version.sh`, `demo/image/init-data.sh`, `demo/image/config/` (propiedades de la demo, sin secretos)

**Interfaces:**
- Produces:
  - `build-version.sh <version>`: se ejecuta en el servidor de la demo como root, y lee los secretos de su entorno. Hace esto:
    1. descarga de R2 el ZIP de SAP y de GitHub el asset `stripe-sap-commerce-connector-<version>.zip` del release `v<version>`;
    2. monta la plataforma con la receta `b2c_occ_stripe` y ejecuta `ant production`;
    3. construye `ghcr.io/serpean/stripe-demo-commerce:<version>`;
    4. con `init-data.sh`, inicializa una base PostgreSQL temporal con los datos de ejemplo y sube `dumps/<version>.sql.gz` a R2;
    5. solo al final, si todo fue bien, sube la imagen a GHCR con su etiqueta. Antes de ese momento solo existe como `:<version>-building`, que nunca se sube.
  - Las propiedades de la demo (`demo/image/config/demo.properties`) leen de variables de entorno: claves de Stripe, base de datos, y `website.*`/CORS para `https://demo.serpean.dev`.

- [ ] **Step 1 (Review Focus 4):** si falla cualquier paso, el script termina con un código distinto de 0 y no sube nada con la etiqueta final. Pruébalo en local con Docker, con una URL de R2 falsa: falla en la descarga y en GHCR no hay ninguna etiqueta nueva.
- [ ] **Step 2:** prueba en local con Docker (en este Mac, con el ZIP local en lugar de R2, mediante `SAP_ZIP_PATH`): construye la versión `4.0.0`, arranca la imagen con un PostgreSQL de compose cargado con el volcado y comprueba que `/occ/v2/electronics-spa/products/search` responde. **Nada se sube a GHCR ni a R2 en esta prueba** (`DEMO_DRY_RUN=1`).
- [ ] **Step 3:** comprueba con `docker history` y `unzip -l` que la imagen no tiene `.env`, `.npmrc` ni claves.
- [ ] **Step 4:** commit `feat(demo): build the Commerce image and the data dump of a released version`.

### Task 8: Despliegue de la demo (compose para Coolify)

**Files:**
- Create: `demo/compose/docker-compose.yml`, `demo/compose/Caddyfile`, `demo/storefront/Dockerfile`, `.github/workflows/demo-storefront-image.yml`

**Interfaces:**
- Consumes: la imagen `stripe-demo-commerce:<version>` y el volcado (tarea 7).
- Produces:
  - **Servicios del compose:**
    - `postgres`: carga `dumps/<version>.sql.gz` al crear la base;
    - `solr`;
    - `commerce`;
    - `storefront`: Spartacus SSR, imagen `ghcr.io/serpean/stripe-demo-storefront:<version>`;
    - `caddy`: rutas `/occ/*`, `/authorizationserver/*`, `/medias/*`, `/backoffice*` y `/hac*` a `commerce`, y el resto a `storefront`;
    - `cloudflared`: con `CF_TUNNEL_TOKEN`, hacia `caddy:80`.
  - **Variables:** `DEMO_VERSION` y los secretos de la tarea 6.
  - **`demo-storefront-image.yml`:** en cada tag `v*`, construye la imagen SSR en un runner de GitHub, con `SAP_NPM_AUTH` como en `release.yml`, y la sube a GHCR privado. La URL del backend es la del mismo origen (`https://demo.serpean.dev`).

- [ ] **Step 1:** prueba en local: `docker compose` con `cloudflared` desactivado y Caddy en `localhost:8088`. Esperado: la página de inicio carga, se puede iniciar sesión y un pedido con la tarjeta de prueba `4242…` llega a la confirmación.
- [ ] **Step 2:** comprueba que la HAC y la Backoffice responden detrás de Caddy, por las rutas de arriba.
- [ ] **Step 3:** ejecuta `actionlint` si está instalado. Si no, revisa el YAML a mano. Comprueba que no hay ningún `${{ secrets.* }}` dentro de `run:`.
- [ ] **Step 4:** commit `feat(demo): compose for Coolify with Caddy, Cloudflare Tunnel and the SSR storefront`.

### Task 9: Workflows "Demo up" y "Demo down"

**Files:**
- Create: `.github/workflows/demo-up.yml`, `.github/workflows/demo-down.yml`, `demo/scripts/{hetzner,coolify,stripe-webhook,wait-healthy}.sh`, `demo/scripts/base-snapshot.sh`

**Interfaces:**
- Consumes: la tarea 6 (secretos), la tarea 7 (`build-version.sh`) y la tarea 8 (compose).
- Produces:
  - **`demo-up.yml`** (`workflow_dispatch`, entrada opcional `version`; por defecto, el último release):
    1. crea el servidor `stripe-demo` (x86, 32 GB; por defecto `ccx33` en `fsn1`) desde el snapshot con la etiqueta `stripe-demo-base`;
    2. si la imagen de esa versión no existe en GHCR, ejecuta `build-version.sh` por SSH;
    3. registra el servidor en Coolify y despliega el compose;
    4. espera a que `https://demo.serpean.dev` responda, con el service token de Access, como mucho 20 minutos;
    5. activa el webhook de Stripe.
  - **`demo-down.yml`:** desactiva el webhook, quita el recurso y el servidor de Coolify, y borra el servidor de Hetzner. Es idempotente: no falla si algo ya no existe.
  - **`base-snapshot.sh`:** se ejecuta una vez a mano y crea el snapshot base, con Docker, la clave SSH pública de Coolify y la etiqueta `stripe-demo-base`.

- [ ] **Step 1 (Review Focus 4):** `demo-up.yml` tiene un paso final con `if: failure() || cancelled()` que ejecuta la misma limpieza que `demo-down`. Pruébalo con una versión que no existe (`version: 0.0.0`): el workflow falla y, al terminar, `hcloud server list` no muestra `stripe-demo`.
- [ ] **Step 2:** con el permiso del propietario, pedido en el momento, ejecuta "Demo up" con la versión `4.0.0`. Esperado:
  - la primera vez, construye la imagen y el volcado;
  - la demo responde detrás de Access;
  - un pedido con tarjeta de prueba llega a la confirmación;
  - el webhook de Stripe entrega `payment_intent.succeeded` con 200.

  Anota la duración.
- [ ] **Step 3:** ejecuta "Demo down". Esperado: el servidor ya no existe y el webhook está desactivado. Después ejecuta "Demo up" otra vez: esta vez no compila, y anota la duración (objetivo, 10-15 minutos). Termina con "Demo down".
- [ ] **Step 4:** revisa los logs de las ejecuciones: no hay ningún secreto sin enmascarar.
- [ ] **Step 5:** README: una sección "On-demand demo" con lo que hace, el coste aproximado, el enlace a `docs/demo/setup.md` y la nota de la licencia de SAP (uso personal, acceso restringido). CHANGELOG: entradas en "Unreleased" para las partes A y B.
- [ ] **Step 6:** commit `feat(demo): Demo up and Demo down workflows`.
