# Proyecto 1: Limpieza del conector Stripe para SAP Commerce

Fecha: 2026-10-18
Estado: borrador para aprobación

## 1. Contexto

Este repositorio contiene el conector de Stripe para SAP Commerce Cloud 2211 y un storefront Spartacus. Una revisión del código (octubre 2026) encontró:

- Mucho código que no es del conector: una copia de la tienda electronics (67 MB), una copia completa de la plantilla de fulfilment, el jar de Stripe en el repositorio y código de plantilla vacío.
- Dos pilas de pago a la vez: SAP OPF (Open Payment Framework) y la integración propia de Stripe. Ningún código Java usa OPF.
- Fallos funcionales (webhooks, devoluciones, nombres de propiedades). Estos fallos **no** son de este proyecto.

El propietario tiene cinco objetivos. Los hacemos como proyectos separados, en este orden:

1. **Limpieza** (este documento).
2. Conector estándar de SAP Commerce y pruebas e2e.
3. Máximo número de métodos de pago de Stripe.
4. Upgrades automáticos entre versiones (CI, Renovate, versiones en un sitio).
5. Variante para el storefront Accelerator (JSP).

Cada proyecto tiene su propio diseño. La limpieza va primero porque reduce el código que los demás proyectos tienen que leer y cambiar.

## 2. Qué es el conector

Decisión del propietario: **lo publicado es el conector**. Los clientes lo instalan en su propio proyecto. Todo lo demás es un **proyecto de referencia**: sirve para demos y para ejecutar pruebas, pero no se entrega.

| Parte | Rutas | Papel |
|---|---|---|
| Conector backend | `core-customize/hybris/custom/stripe/{stripecore,stripefacades,stripeocc,stripefulfilmentprocess}` | Se publica |
| Conector front | `js-storefront/stripestore/integrations-libs/stripe` | Se publica (librería npm) |
| Proyecto de referencia | `core-customize/manifest.json`, `core-customize/installer/recipes/b2c_occ_stripe`, `js-storefront/manifest.json`, la app `js-storefront/stripestore/src`, los datos de ejemplo | No se publica |

La estructura de carpetas `core-customize/` y `js-storefront/` se mantiene, porque CCv2 la necesita. La separación entre conector y referencia es lógica y queda en el README. No movemos carpetas (*decisión del diseñador*).

## 3. Objetivos

1. Eliminar todo lo que el conector no necesita.
2. Eliminar **todo** rastro de SAP OPF. Al terminar, `git grep -i opf` no devuelve resultados (excepto este documento y otros documentos de `docs/`).
3. Dejar el proyecto de referencia con lo mínimo para arrancar el conector con un BaseStore cuyo `paymentProvider` sea `Stripe`.
4. Documentar en el README qué es el conector y qué es el proyecto de referencia.

## 4. No objetivos

Estas cosas son de otros proyectos. No se hacen aquí aunque el código esté abierto:

- Corregir fallos funcionales: lógica invertida en `StripeWebhookService.resolveOrder`, nombres de propiedades (`stripe.secret.key` frente a `stripe.api.secret.key`), mock de devolución, idempotencia, estados asíncronos. → Proyecto 2.
- Cambiar el modelo de datos del pago: `StripePaymentInfo` como subtipo de `CreditCardPaymentInfo` y dejar de usar `SAPGenericPaymentInfo`. → Proyecto 2. Nota: `SAPGenericPaymentInfo` es un tipo del core de SAP (`basecommerce-items.xml:1110`), no de OPF, así que el criterio `git grep -i opf` no lo incluye.
- Reducir `stripefulfilmentprocess` a las acciones de Stripe. Hacerlo bien necesita el diseño de sobrescritura de beans del proyecto 2. → Proyecto 2.
- Llevar la autorización a `PaymentService`. → Proyecto 2.
- Subir de SAP Commerce 2211.37 a 2211.55, CI, Renovate. → Proyecto 4.
- Publicar la librería en npm y el `manifest.json` de CCv2 (que hoy referencia `yacceleratorstorefront` y un `localextensions.xml` que no existe). → Proyecto 4.
- Endpoint de backend para la clave pública de Stripe. → Proyecto 2.

## 5. Antiobjetivos

El proyecto falla, aunque compile, si:

- Elimina algo que el conector necesita en tiempo de ejecución.
- Deja una referencia a OPF en código, configuración, dependencias, estilos o datos.
- Mezcla correcciones funcionales del conector con la limpieza. Un revisor tiene que poder leer cada commit como "elimina", "mueve", "sustituye datos de ejemplo" o "configura el proyecto de referencia".

**No hay estado "antes" que funcione.** El propietario confirma que no existe ningún entorno donde el checkout funcione hoy. Con la receta actual, el sitio `stripe-spa` depende de `stripestore`, que está comentada. Por eso este proyecto también incluye conseguir que el sitio de referencia arranque y llegue al paso de pago **por primera vez**. Ese trabajo solo afecta al proyecto de referencia (datos, receta, app), nunca al código del conector.

## 6. Compatibilidad

Decisión del propietario: **se puede romper la compatibilidad** con la versión publicada todo lo que sea necesario. Los cambios que rompen algo se anotan en una sección "Cambios incompatibles" del README o de un `MIGRATION.md` (*el constructor elige*).

## 6.1 Historial de git

Decisión del propietario: los ficheros se eliminan con **commits nuevos**. No se reescribe el historial, para que se vea la evolución del proyecto. El repositorio sigue pesando lo mismo al clonarlo, y eso se acepta.

## 6.2 Dependencia externa

El trabajo de 7.2 (datos de ejemplo) necesita el paquete oficial `spartacussampledata` 2211.37 de SAP. El propietario lo aporta. Sin ese paquete se puede hacer todo lo demás, pero no las verificaciones 3, 5 y 6. La verificación 6 también necesita unas claves de Stripe en modo test, que aporta el propietario.

## 7. Inventario

### 7.1 Se elimina

**Extensiones y módulos completos**
- `core-customize/hybris/custom/stripe/stripestore/` (copia de electronics, 2.951 ficheros, 67 MB, ya comentada en la receta).

**OPF en el front**
- Dependencia `@spartacus/opf` en `js-storefront/stripestore/package.json` (y regenerar `package-lock.json`).
- `src/app/spartacus/features/opf/` y su import en `spartacus-features.module.ts`.
- `OpfOrderModule` en `src/app/spartacus/features/order/order-wrapper.module.ts`. Se usa en su lugar el módulo de pedido estándar de Spartacus (`@spartacus/order`).
- `src/styles/spartacus/opf.scss` y sus dos entradas en `angular.json`.
- La palabra clave `"opf"` en `integrations-libs/stripe/package.json`.
- La URL del tenant OPF (`opf-feature.module.ts:56`) desaparece con el módulo.

**OPF en el backend y los datos**
- `paymentProvider=OPF` y cualquier otra referencia OPF en impex.
- La tienda `stripeopf` y el content catalog `stripeopfContentCatalog` de `spartacussampledata`, con sus imágenes e impex.
- Las referencias a "stripeopf" en `SpaSampleDataImportService.java` y en `spartacussampledata/resources/installer/customconfig/custom.properties`, si existen.

**Ficheros que no hacen falta**
- `stripecore/lib/stripe-java-30.0.0.jar` y `stripecore/lib/.lastupdate`. El jar llega por Maven desde `external-dependencies.xml` (`usemaven=true`). Hay que comprobar que la build sigue resolviendo la dependencia.
- En `stripeocc`: `StripeoccController`, `StripepaymentoccTest`, los tipos y beans de ejemplo de la plantilla en `stripeocc-items.xml` y `stripeocc-beans.xml`, y el endpoint `stripeConfirm` con sus DTO si no tiene backend.
- Ficheros de IDE en el repositorio: `.classpath` y `.settings/`. Se añaden al `.gitignore`.
- Cualquier otro código de plantilla vacío que nada usa (*el constructor decide caso por caso, con una lista en el commit*).

### 7.2 Se mueve o se cambia en el proyecto de referencia

- **Datos de ejemplo:** decisión del propietario: se usa el paquete **oficial** `spartacussampledata` de SAP. El `spartacussampledata` del repositorio no es el de SAP: es una copia recortada que solo contiene la tienda `stripeopf` y no tiene datos de `electronics-spa`. Ese paquete tampoco está en el zip de SAP Commerce.
  - El propietario aporta el paquete oficial. Es una descarga de SAP y no está en el repositorio.
  - Versión: la que corresponde a la versión de Spartacus del `package.json`, hoy 2211.37 (*decisión del diseñador*).
  - El paquete oficial sustituye por completo a la copia del repositorio, sin cambios. Así, en el futuro se puede actualizar sustituyéndolo otra vez.
  - El sitio de referencia es `electronics-spa`.
  - Una impex mínima pone `paymentProvider=Stripe` en el BaseStore `electronics` (*decisión del diseñador*). Esa impex vive en el proyecto de referencia, no en el conector, porque el conector no debe cambiar las tiendas del cliente. El constructor elige dónde va: una extensión pequeña de referencia (por ejemplo `stripesampledata`) o el sistema de importación de la receta.
- **Receta `b2c_occ_stripe`:** quitar `stripestore` (ya está comentada) y comprobar que la lista de extensiones arranca sin OPF.
- **Clave pública en Angular:** `stripe-feature.module.ts:36` tiene una clave `pk_test` escrita en el código. Pasa a ser un valor de configuración de la app de referencia, con un marcador en lugar de la clave real (*decisión del diseñador*). El endpoint de backend que entrega la clave es del proyecto 2.

### 7.3 Se queda

- `stripecore`, `stripefacades`, `stripeocc`, `stripefulfilmentprocess` y la librería `integrations-libs/stripe`, sin los ficheros de 7.1.
- Los populators de `sapGenericPaymentInfo` (se sustituyen en el proyecto 2).
- El código muerto *dentro* de clases que el proyecto 2 va a reescribir (`StripePaymentProvider`, `StripeClient`, `DefaultStripePaymentIntegrationService`, `StripeWebhookService`). Eliminarlo ahora solo produce cambios que el proyecto 2 deshace.

## 8. Cómo se verifica

1. `git grep -i opf -- ':!docs'` no devuelve resultados.
2. El backend compila: `ant clean all` con las extensiones de la receta, en una plataforma SAP Commerce. Plataforma propuesta: la 2211.55 del zip `CXCOMCL221100U_55-70007431.ZIP` (está en la raíz del repositorio y en `.gitignore`), descomprimida **fuera** del repositorio (*decisión del diseñador*). Si la build en 2211.55 falla por motivos que no son de la limpieza, se compila en la 2211.37 de `/Volumes/e/y/stripeopf/hybris` y se anota el fallo para el proyecto 4.
3. `ant initialize` (o la receta) termina sin errores de impex.
4. El front compila: `npm ci`, `ng build sap-commerce-stripe-payment` (la librería) y `ng build` (la app).
5. Comprobación manual: el storefront de referencia (`electronics-spa`) carga, permite añadir un producto al carrito y llega hasta el paso de pago del checkout, donde se ve el formulario de Stripe. No hace falta completar un pago: el flujo de pago no funciona por fallos del proyecto 2.
6. **Servidor arrancado y e2e de un checkout** (petición del propietario):
   - Se arranca el servidor (`hybrisserver.sh`) con la receta y el storefront de referencia (`npm start` o SSR) contra él.
   - Una prueba e2e automática con **Playwright** (*decisión del diseñador*: maneja bien los iframes de Stripe y es la base de la batería e2e del proyecto 2) hace un checkout completo en `electronics-spa` como usuario registrado. Pasos: login, añadir un producto, dirección de entrega, modo de envío, pago con la tarjeta de prueba `4242 4242 4242 4242` (sin 3DS), realizar pedido. Al final se ve la página de confirmación con el número de pedido.
   - Las claves de Stripe en modo test (`sk_test`, `pk_test`) llegan por variables de entorno o por un `local.properties` que no está en git. Nunca se guardan en el repositorio. El propietario las aporta.
   - La prueba vive en el proyecto de referencia (por ejemplo `js-storefront/stripestore/e2e/`), con un comando para ejecutarla, y queda documentada en el README.
   - El objetivo es que la prueba pase. Si falla por un fallo del **código del conector** (por ejemplo el nombre de propiedad `stripe.secret.key` o el estado del pago), no se corrige aquí. El fallo se anota con la evidencia (paso, error, log) para el proyecto 2, y la prueba queda marcada como fallo conocido. Los fallos del proyecto de referencia (datos, receta, configuración) sí se corrigen aquí. Por ejemplo, la configuración de referencia puede usar el nombre de propiedad que el código lee hoy.
7. Las pruebas unitarias que compilaban antes siguen compilando. Las que ya fallaban o no compilaban (`StripeClientTest`, `StripePaymentControllerTest`) se dejan como están y se anotan para el proyecto 2.

## 9. Lo que queda al constructor

- El orden y el tamaño de los commits. Regla: cada commit tiene un solo tipo de cambio (ver antiobjetivos) y compila.
- Qué código de plantilla vacío se elimina (7.1, último punto), con una justificación breve por fichero.
- Dónde va la impex de referencia (7.2).
- README frente a `MIGRATION.md` para los cambios incompatibles.
- Cómo montar la plataforma de prueba fuera del repositorio.

## 10. Datos de SAP Commerce 2211.55 útiles para proyectos siguientes

Del análisis de los fuentes de SAP (zip 2211.55, octubre 2026):

- `yacceleratorstorefront`, `addonsupport` y `acceleratorstorefrontcommons` tienen `deprecated=true`.
- El checkout Accelerator usa `acceleratorCheckoutFacade` (`AbstractCheckoutController.java:45`), no `checkoutFacade`. OCC usa `checkoutFacade`.
- El punto de extensión estándar para la autorización es el alias `commercePaymentAuthorizationStrategy`.
- `order-process.xml` ya tiene el nodo de espera `waitForReviewDecision` con el evento `${process.order.code}_ReviewDecision`, útil para pagos asíncronos.
- `CheckAuthorizeOrderPaymentAction` solo acepta AUTHORIZATION con estado ACCEPTED.
- La acción de devolución de `yacceleratorordermanagement` llama a `paymentService.refundFollowOn`.
