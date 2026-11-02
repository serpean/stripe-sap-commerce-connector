# Verificación rápida: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que verificar una tarea tarde minutos y no pare el servidor.

**Architecture:** las pruebas unitarias corren con Gradle fuera de la plataforma. La plataforma usa PostgreSQL en Docker, con un tenant `junit` propio. Playwright se divide en los proyectos `parallel`, `serial` y `slow`, con etiquetas por área y una comprobación previa del entorno.

**Tech Stack:** SAP Commerce 2211.55, Java 17 (SapMachine), Gradle (wrapper), JUnit 4 + Mockito, PostgreSQL 16, Playwright, Node 22, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-28-verificacion-rapida-design.md`

## Global Constraints

- **Commits:** `git -c user.name=serpean -c user.email=sergi9p@gmail.com commit`, sin metadatos de Claude.
- **Secretos:** nunca se imprimen, nunca se copian y nunca entran en git. Esto incluye `.env`, `js-storefront/stripestore/.npmrc` y todos los tokens. En los workflows, todo secreto va por `env:` y nunca dentro de `run:` con `${{ }}`.
- **Binarios de SAP** (ZIP de la plataforma y jars): nunca en git.
- **Plataforma de referencia:** está fuera del repo, en `/Volumes/e/y/stripe-ref-platform` (`hybris/bin/custom` es un enlace a este repo). Los cambios en ella se hacen con scripts del repo y se documentan en el README.
- **Procesos:** no matar ni reiniciar procesos que la tarea no haya arrancado (comprobar antes con `ps -p <pid> -o command=`). `stripe listen` y `ng serve --port 4300` son del propietario.
- **E2E en local:** `E2E_BASE_URL=http://localhost:4300`. El puerto `:4200` es de otra aplicación.
- **Cobertura:** ningún caso e2e de hoy desaparece. Cada uno sigue en `parallel`, `serial` o `slow`.
- **README:** en inglés sencillo, con frases cortas.
- **Fuera del alcance:** CI con e2e o Java en GitHub, y el entorno de demo (repo `serpean/stripe-sap-commerce-demo`).

## Review Focus

1. **Un spec en paralelo que cambia la configuración global del servidor** (modo de captura, plazos) rompe a los demás workers. Esperado: solo el proyecto `serial` puede llamar a `setConfig`. Test en la tarea 4.
2. **Un reintento (`retries: 1`) de un spec que ya creó un pedido.** Esperado: el reintento usa un carrito y un cliente nuevos, y las comprobaciones buscan por su propio carrito. Test en la tarea 4.
3. **Un fichero cambiado que el selector de áreas no conoce.** Esperado: se ejecuta toda la batería, nunca nada. Test en la tarea 5.
4. **Volver de PostgreSQL a HSQLDB** cuando Docker no está disponible. Esperado: el script lo revierte y los datos de HSQLDB siguen intactos. Test en la tarea 2.

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
- [ ] **Step 3 (Review Focus 4):** ejecuta `refdb.sh use-hsqldb`, comprueba que `local.properties` queda igual que antes del paso 1 (con `diff` contra una copia guardada en el scratchpad) y vuelve a `use-postgres`.
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
- [ ] **Step 7:** README: proyectos, scripts, `retries` y vídeo. Corrige también la lista de valores de `TEST_TIMINGS`, que hoy está incompleta en el README. CHANGELOG: entrada en "Unreleased".
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
