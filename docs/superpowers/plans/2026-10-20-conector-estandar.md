# Conector estándar de SAP Commerce y batería e2e: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** el conector usa solo puntos de extensión estándar de SAP Commerce 2211, cumple la regla "nunca un cobro sin pedido ni un pedido con otro importe", y una batería de 17 pruebas e2e más tests JUnit lo demuestra.

**Arquitectura:** `stripecore` contiene todo el dominio: un gateway sobre el SDK de Stripe, el registro propio del PaymentIntent, los comandos del framework de pagos, la estrategia de autorización, el procesamiento de webhooks con `TaskService`, la creación del pedido desde el servidor y los envoltorios (`BeanPostProcessor`) de las acciones de SAP. `stripefacades` contiene el autorizador del checkout y los facades hijos. `stripeocc` contiene los endpoints y el validador de OCC. `stripefulfilmentprocess` desaparece. La librería de Spartacus pide la clave al backend, sincroniza el importe antes de confirmar y muestra los estados.

**Tecnologías:** SAP Commerce 2211.55, Java 17 (SapMachine 17.0.12), stripe-java 30.0.0 (API `2025-09-30.clover`), JUnit 4 + Mockito (los de la plataforma), Spartacus 2211.37, Angular 19, Playwright 1.49.1, Stripe CLI 1.34.0.

**Spec:** `docs/superpowers/specs/2026-10-19-conector-estandar-design.md`

## Restricciones globales

- Rama: `feat/conector-estandar`. Autor de los commits: `git -c user.name=serpean -c user.email=sergi9p@gmail.com commit ...`.
- **Regla número uno:** nunca un cobro en Stripe sin pedido en SAP y sin devolución; nunca un pedido con un importe distinto al cobrado.
- Nunca se copia código ni procesos de SAP. Solo alias, beans hijos, `BeanPostProcessor`, comandos del framework de pagos, populators y eventos.
- Nunca se usa `TransactionStatus.REVIEW` para un pago pendiente. Un pago pendiente no tiene entrada AUTHORIZATION.
- Proveedor de pago: `"Stripe"` (`StripeCoreConstants.STRIPE_PROVIDER`).
- Versión de la API de Stripe: `com.stripe.Stripe.API_VERSION` (`2025-09-30.clover`). No se usa el estático `Stripe.apiKey`.
- Toda llamada a Stripe que crea, cambia o mueve dinero lleva una clave de idempotencia **estable** (derivada de códigos de SAP o ids de Stripe, nunca aleatoria).
- Nunca se escriben en el log claves, `client_secret` ni datos de tarjeta. El `client_secret` no se guarda en la base de datos.
- Ningún mensaje de error de Stripe llega tal cual al cliente por OCC. Se devuelve un código estable.
- Propiedades (todas con override por sitio `stripe.<x>.<baseSiteUid>`): `stripe.secret.key`, `stripe.publishable.key`, `stripe.webhook.secret`, `stripe.capture.mode` (`manual`|`automatic`, defecto `manual`), `stripe.capture.manual.methods` (defecto `card,paypal,klarna`), `stripe.statement.descriptor`, `stripe.descriptor.suffix`, `stripe.webhook.graceperiod.seconds` (defecto `300`), `stripe.payment.pending.timeout.seconds` (defecto `86400`), `stripe.payment.pending.retry.seconds` (defecto `300`).
- Plataforma de prueba: `/Volumes/e/y/stripe-ref-platform` (2211.55, `hybris/bin/custom` enlazado al repo). Antes de cada `install.sh`: `rsync -a --delete core-customize/installer/recipes/b2c_occ_stripe/ /Volumes/e/y/stripe-ref-platform/installer/recipes/b2c_occ_stripe/`. Claves en `.env` (fuera de git).
- Comandos de test de backend (desde `/Volumes/e/y/stripe-ref-platform/hybris/bin/platform`, con `export JAVA_HOME=/Users/serpean/.sdkman/candidates/java/17.0.12-sapmchn; . ./setantenv.sh`):
  - Unit: `ant unittests -Dtestclasses.extensions=stripecore,stripefacades,stripeocc -Dtestclasses.annotations=unittests`
  - Integración: `ant yunitinit` (una vez) y `ant integrationtests -Dtestclasses.extensions=stripecore,stripefacades,stripeocc`
  - Un solo test: añadir `-Dtestclasses.packages=com.stripe.core.<paquete>.*`
- Todo test Java nuevo lleva `@UnitTest` o `@IntegrationTest` y se registra solo por convención (`testsrc/`).
- Versión del conector y de la librería: **3.0.0**.
- **Borrado de clases antiguas:** la lista "Delete" de cada tarea es la meta. Cada commit deja `ant all` en verde: una clase antigua se borra cuando su sustituto ya existe y no le quedan usuarios; si un cambio de la tarea rompe una clase antigua que aún tiene usuarios, se adapta lo mínimo para compilar y se borra en la tarea que sustituye a sus usuarios. Nunca se deja un `TODO` ni código comentado.

## Puntos de atención en la revisión

1. **Webhook antes de que exista el registro del PaymentIntent** (por ejemplo, otro sistema usa la misma cuenta de Stripe): el evento se marca IGNORED y responde 200; nunca lanza ni reintenta para siempre. → test `StripeWebhookEventTaskRunnerTest.ignoresEventForUnknownPaymentIntent` (Task 10).
2. **Moneda sin decimales (JPY)**: 1000 JPY son `1000` unidades mínimas, no `100000`. → test `StripeAmountConverterTest.jpyHasNoMinorUnits` (Task 2).
3. **El cliente pulsa "Pagar" dos veces o recarga la página de vuelta**: un solo pedido, y la segunda llamada devuelve el pedido existente o un error estable, nunca una excepción 500. → test `StripeCheckoutAuthorizerTest.secondPlacementForSamePaymentIntentIsRejectedByLock` (Task 7) y prueba e2e 9.
4. **Cancelación de un pedido sin `PaymentTransaction`** (pedido antiguo o de otro proveedor dentro de la misma tienda): la estrategia no hace nada con Stripe y delega en SAP. → test `StripeOrderCancelPaymentServiceStrategyTest.nonStripeOrderIsDelegated` (Task 13).
5. **Webhook con firma válida pero de una versión de API distinta** (la Stripe CLI usa otra versión): el objeto se deserializa igualmente (`deserializeUnsafe`) y el evento se procesa; si no se puede, se marca FAILED con log, sin 500. → test `StripeWebhookFacadeTest.acceptsEventWithDifferentApiVersion` (Task 9).

---

### Task 1: Gateway de Stripe y configuración por sitio

Sustituye `StripeClient`, `DefaultStripePaymentIntegrationService`, `StripeRequestFactory`, `DefaultStripeRequestFactory` y `DefaultStripeConfigurationService` por dos piezas con interfaz.

**Files:**
- Create: `core-customize/hybris/custom/stripe/stripecore/src/com/stripe/core/config/StripeConfigurationService.java`, `.../config/impl/DefaultStripeConfigurationService.java` (reescrito)
- Create: `.../stripecore/src/com/stripe/core/gateway/StripeGateway.java`, `.../gateway/impl/DefaultStripeGateway.java`, `.../gateway/StripeGatewayException.java`
- Delete: `.../stripe/StripeClient.java`, `.../payment/services/StripePaymentIntegrationService.java`, `.../payment/services/impl/DefaultStripePaymentIntegrationService.java`, `.../payment/request/**`, `.../payment/exception/StripePaymentIntegrationException.java`, `.../config/StripeConfigurationProperties.java`, `stripecore/testsrc/com/stripe/stripe/StripeClientTest.java`
- Modify: `stripecore/resources/stripecore-spring.xml`, `stripecore/project.properties`
- Test: `stripecore/testsrc/com/stripe/core/config/impl/DefaultStripeConfigurationServiceTest.java`, `stripecore/testsrc/com/stripe/core/gateway/impl/DefaultStripeGatewayTest.java`

**Interfaces:**
- Produces:
  - `StripeConfigurationService`: `String getSecretKey()`, `String getPublishableKey()`, `String getWebhookSecret()`, `StripeCaptureMode getCaptureMode()`, `Set<String> getManualCaptureMethods()`, `Duration getWebhookGracePeriod()`, `Duration getPendingPaymentTimeout()`, `Duration getPendingPaymentRetryDelay()`, `String getStatementDescriptorSuffix()`. Todas leen `ConfigurationService` en cada llamada, primero `stripe.<x>.<currentBaseSiteUid>`, después `stripe.<x>`. Bean `stripeConfigurationService` (alias → `defaultStripeConfigurationService`).
  - `StripeGateway` (bean `stripeGateway` → `defaultStripeGateway`). Todos lanzan `StripeGatewayException` (runtime, con `getStripeCode()` y `isRetryable()`):
    - `PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey)`
    - `PaymentIntent updatePaymentIntent(String id, PaymentIntentUpdateParams params, String idempotencyKey)`
    - `PaymentIntent retrievePaymentIntent(String id)` (expande `payment_method` y `latest_charge`)
    - `PaymentIntent cancelPaymentIntent(String id, String idempotencyKey)`
    - `PaymentIntent capturePaymentIntent(String id, long amountToCapture, String idempotencyKey)`
    - `Refund createRefund(String paymentIntentId, long amount, String idempotencyKey)`
    - `Event constructEvent(String payload, String signatureHeader)` (lanza `StripeGatewayException` con `isSignatureInvalid()==true` si la firma falla)
  - `DefaultStripeGateway` usa `com.stripe.StripeClient` construido por llamada con `getSecretKey()` y `setMaxNetworkRetries(2)`, y `RequestOptions.builder().setIdempotencyKey(...)`.

- [ ] **Step 1: tests que fallan**
  - `DefaultStripeConfigurationServiceTest.siteOverrideWins`: con `stripe.capture.mode=manual` y `stripe.capture.mode.electronics-spa=automatic` y sitio actual `electronics-spa` → `getCaptureMode()==AUTOMATIC`.
  - `.fallsBackToGlobal`: sin override → valor global.
  - `.defaults`: sin propiedades → `MANUAL`, `{card,paypal,klarna}`, `Duration.ofSeconds(300)`, `ofSeconds(86400)`, `ofSeconds(300)`.
  - `.readsOnEveryCall`: cambiar la propiedad entre dos llamadas cambia el resultado.
  - `DefaultStripeGatewayTest.apiVersionIsPinned`: `assertEquals("2025-09-30.clover", DefaultStripeGateway.API_VERSION)` y `DefaultStripeGateway.API_VERSION == com.stripe.Stripe.API_VERSION`.
  - `.invalidSignatureIsReported`: `constructEvent("{}", "t=1,v1=bad")` con un secreto → `StripeGatewayException` con `isSignatureInvalid()`.
  - `.missingSecretKeyFailsFast`: sin `stripe.secret.key` → `createPaymentIntent` lanza `StripeGatewayException` antes de llamar a la red.
- [ ] **Step 2:** ejecutar `ant unittests -Dtestclasses.extensions=stripecore -Dtestclasses.annotations=unittests`. Esperado: FAIL por clases inexistentes.
- [ ] **Step 3:** implementar las interfaces de arriba. Añadir a `project.properties` los defaults de las propiedades de las restricciones globales. Borrar las clases sustituidas y sus beans.
- [ ] **Step 4:** ejecutar los tests. Esperado: PASS, y `ant all` en verde (ver la regla de borrado en las restricciones globales).
- [ ] **Step 5: commit** `feat(core): Stripe gateway with pinned API version and per-site configuration`

### Task 2: Conversión de importes y mapeo de estados únicos

**Files:**
- Create: `stripecore/src/com/stripe/core/amount/StripeAmountConverter.java` (+ `impl/DefaultStripeAmountConverter.java`), `stripecore/src/com/stripe/core/status/StripePaymentIntentStatus.java`
- Delete: `stripecore/src/com/stripe/core/stripe/CurrencyUtils.java`
- Test: `stripecore/testsrc/com/stripe/core/amount/impl/DefaultStripeAmountConverterTest.java`, `stripecore/testsrc/com/stripe/core/status/StripePaymentIntentStatusTest.java`

**Interfaces:**
- Produces:
  - `StripeAmountConverter` (bean `stripeAmountConverter`): `long toMinorUnits(BigDecimal amount, String currencyIso)`; `BigDecimal fromMinorUnits(long amount, String currencyIso)`. Decimales por `CurrencyModel.digits` de SAP; si falta, `java.util.Currency`. Redondeo `HALF_UP`.
  - `enum StripePaymentIntentStatus { AUTHORIZED, CAPTURED, PROCESSING, REQUIRES_ACTION, FAILED, CANCELED; static StripePaymentIntentStatus of(String stripeStatus) }`: `requires_capture`→AUTHORIZED, `succeeded`→CAPTURED, `processing`→PROCESSING, `requires_action`/`requires_confirmation`→REQUIRES_ACTION, `requires_payment_method`→FAILED, `canceled`→CANCELED, desconocido → `IllegalArgumentException`.

- [ ] **Step 1: tests que fallan**
  - `jpyHasNoMinorUnits`: `toMinorUnits(new BigDecimal("1000"), "JPY") == 1000`.
  - `eurHasTwoDecimals`: `toMinorUnits(new BigDecimal("339.54"), "EUR") == 33954`; `fromMinorUnits(33954,"EUR")` igual a `339.54` con `compareTo`.
  - `roundsHalfUp`: `toMinorUnits(new BigDecimal("10.005"),"USD") == 1001`.
  - `StripePaymentIntentStatusTest.mapsEveryKnownStatus` (los 7) y `.unknownStatusFails`.
- [ ] **Step 2:** ejecutar; esperado FAIL.
- [ ] **Step 3:** implementar. Sustituir todos los usos de las conversiones antiguas.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: commit** `feat(core): single amount converter and payment intent status mapping`

### Task 3: Modelo de datos

**Files:**
- Modify: `stripecore/resources/stripecore-items.xml`
- Create: `stripecore/src/com/stripe/core/dao/StripePaymentIntentRecordDao.java` (+ `impl/Default...`), `stripecore/src/com/stripe/core/dao/StripeWebhookEventDao.java` (+ `impl/Default...`)
- Test: `stripecore/testsrc/com/stripe/core/dao/impl/StripeRecordsIntegrationTest.java` (`@IntegrationTest`, `ServicelayerTransactionalTest`)

**Interfaces:**
- Produces (items.xml):
  - `StripePaymentInfo extends PaymentInfo` (ya existe; `stripePaymentIntentId` se mantiene) + `paymentMethodType` (String), `paymentMethodId` (String), `cardBrand` (String), `cardLast4` (String), `cardExpMonth` (Integer), `cardExpYear` (Integer).
  - Enum `StripePaymentIntentState { CREATED, PROCESSING, AUTHORIZED, CAPTURED, FAILED, CANCELED, REFUNDED }` (dynamic).
  - `StripePaymentIntentRecord extends GenericItem`: `paymentIntentId` (String, **mandatory, unique index** `piIdx`), `cartCode` (String), `baseSiteUid` (String), `userUid` (String), `currencyIso` (String), `amount` (Long, unidades mínimas), `state` (StripePaymentIntentState), `order` (Order, opcional), `createdTime` heredado.
  - `StripeOrderPlacementLock extends GenericItem`: `paymentIntentId` (String, mandatory, **unique index** `lockIdx`), `ownerNode` (String). Sirve solo para la exclusión mutua de la creación del pedido (spec 6.4).
  - Enum `StripeWebhookEventState { RECEIVED, PROCESSED, IGNORED, FAILED }`.
  - `StripeWebhookEvent extends GenericItem`: `eventId` (String, mandatory, **unique index** `eventIdx`), `type` (String), `payload` (`HYBRIS.LONG_STRING`), `state`, `attempts` (Integer, defecto 0), `lastError` (String).
  - Se elimina el atributo `PaymentTransaction.stripeCaptureMode` y el enum `StripeCaptureMode` se mueve a Java (`com.stripe.core.config.StripeCaptureMode { MANUAL, AUTOMATIC }`).
  - DAOs: `Optional<StripePaymentIntentRecordModel> findByPaymentIntentId(String)`; `Optional<StripePaymentIntentRecordModel> findByOrder(OrderModel)`; `Optional<StripeWebhookEventModel> findByEventId(String)`.

- [ ] **Step 1: test que falla** `StripeRecordsIntegrationTest`: `duplicatePaymentIntentIdIsRejected` (guardar dos records con el mismo id → `ModelSavingException`); `duplicateLockIsRejected`; `duplicateEventIdIsRejected`; `findsRecordByPaymentIntentId`; `orderCloneDoesNotTouchRecords` (clonar un pedido con `modelService.clone` y guardarlo no lanza).
- [ ] **Step 2:** `ant all` y el test de integración. Esperado: FAIL (tipos inexistentes).
- [ ] **Step 3:** items.xml, DAOs (FlexibleSearch por el atributo), beans.
- [ ] **Step 4:** `ant all`, `ant yunitinit`, test de integración PASS.
- [ ] **Step 5: commit** `feat(core): Stripe payment intent record, placement lock and webhook event types`

### Task 4: Servicio de PaymentIntent del carrito (un solo PI por carrito, sincronización de importe)

**Files:**
- Create: `stripecore/src/com/stripe/core/payment/StripePaymentIntentService.java` (+ `impl/DefaultStripePaymentIntentService.java`), `stripecore/src/com/stripe/core/payment/StripeIntentData.java` (POJO: `paymentIntentId`, `clientSecret`, `status`)
- Delete: `stripefacades/.../StripePaymentFacade.java`, `.../impl/DefaultStripePaymentFacade.java` (se sustituyen en Task 7)
- Test: `stripecore/testsrc/com/stripe/core/payment/impl/DefaultStripePaymentIntentServiceTest.java`

**Interfaces:**
- Consumes: `StripeGateway`, `StripeConfigurationService`, `StripeAmountConverter`, `StripePaymentIntentRecordDao` (Tasks 1-3).
- Produces (bean `stripePaymentIntentService`):
  - `StripeIntentData prepareForCart(CartModel cart)`: si el carrito no tiene `StripePaymentInfo` con PI, crea uno (`automatic_payment_methods.enabled=true`, `capture_method` = `MANUAL` si `getCaptureMode()==MANUAL`, si no `AUTOMATIC`; `payment_method_options.<m>.capture_method=manual` solo para los métodos de `getManualCaptureMethods()` cuando el modo es manual; metadatos `cartCode`, `baseSiteUid`, `userUid`, `currencyIso`; `statement_descriptor_suffix`), crea el `StripePaymentInfo` (código = id del PI) y el `StripePaymentIntentRecord` (`CREATED`). Si ya existe y el PI está en `requires_payment_method`/`requires_confirmation`/`requires_action`, **actualiza** importe y moneda si difieren. Si el PI ya no admite cambios (`processing`, `requires_capture`, `succeeded`, `canceled`), libera el dinero si lo hay (`releaseFunds`), crea uno nuevo y sustituye `StripePaymentInfo` y record. Clave de idempotencia de creación: `cart-<cart.code>-<cart.guid>-pi-<n>` (n = número de PIs creados para el carrito).
  - `void releaseFunds(String paymentIntentId, String reason)`: `requires_capture` → cancel; `succeeded` → refund del importe recibido; otros estados → cancel si es posible; registra WARN con el id y el motivo; record → `CANCELED`/`REFUNDED`.

- [ ] **Step 1: tests que fallan** (gateway mockeado):
  - `createsOnePaymentIntentPerCart`: dos llamadas seguidas con el mismo carrito → `createPaymentIntent` una vez.
  - `updatesAmountWhenCartTotalChanges`: PI en `requires_payment_method` con 1000 y carrito 1200 → `updatePaymentIntent` con 1200.
  - `replacesConfirmedIntentWhenCartChanges`: PI `requires_capture` con 1000 y carrito 1200 → cancel del PI viejo, creación de uno nuevo, el carrito apunta al nuevo.
  - `manualModeSetsManualCaptureOnlyForAllowedMethods`: los params de creación tienen `payment_method_options.card.capture_method=manual` y no tienen entrada para `bancontact`.
  - `automaticModeUsesAutomaticCapture`.
  - `metadataContainsCartSiteUserCurrency`.
  - `releaseFundsRefundsSucceededIntent` y `releaseFundsCancelsAuthorizedIntent`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: commit** `feat(core): one payment intent per cart with amount sync and fund release`

### Task 5: Comandos del framework de pagos

**Files:**
- Rewrite: `stripecore/src/com/stripe/core/commands/impl/{AbstractStripeCommand,StripeCaptureCommand,StripeVoidCommand,StripeRefundCommand}.java`
- Create: `.../commands/impl/StripeAuthorizationCommand.java` (`SubscriptionAuthorizationCommand`), `.../commands/impl/StripePartialCaptureCommand.java`
- Modify: `stripecore/resources/stripecore-commands-spring.xml` (añadir `SubscriptionAuthorizationCommand` y `PartialCaptureCommand` al mapa del `stripeCommandFactory`)
- Delete: `stripecore/src/com/stripe/core/payment/services/StripePaymentTransactionService.java` y su impl, `stripecore/src/com/stripe/core/stripe/StripePaymentProvider.java`, `stripecore/testsrc/com/stripe/provider/StripePaymentProviderTest.java`
- Test: `stripecore/testsrc/com/stripe/core/commands/impl/*Test.java` (uno por comando)

**Interfaces:**
- Consumes: `StripeGateway`, `StripeAmountConverter`, `StripePaymentIntentStatus`.
- Produces: comandos registrados para el proveedor `Stripe`. Todos devuelven `requestId` = id del PI (o del refund en REFUND) y nunca `requestToken` con secretos.
  - Autorización (`SubscriptionAuthorizationRequest.subscriptionID` = id del PI): recupera el PI; ACCEPTED solo si estado AUTHORIZED o CAPTURED **y** `amount`/`currency` del PI igualan `totalAmount`/`currency` de la petición; si no, REJECTED con `TransactionStatusDetails.INVALID_REQUEST` (importe) o `GENERAL_SYSTEM_ERROR`.
  - Captura (`CaptureRequest`): si el PI está CAPTURED → ACCEPTED sin llamar a Stripe (modo automático, spec 7.4). Si AUTHORIZED → `capturePaymentIntent(id, total, "capture-"+merchantTransactionCode)`.
  - Captura parcial (`PartialCaptureRequest`): igual con el importe de la petición.
  - Void (`VoidRequest`): AUTHORIZED → cancel; CAPTURED → **refund completo** del importe recibido (spec 7.5, decisión del propietario) y resultado ACCEPTED; idempotencia `"void-"+merchantTransactionCode`.
  - Devolución (`FollowOnRefundRequest`): `createRefund(piId, amount, "refund-"+merchantTransactionCode)`; estado del refund `succeeded`→ACCEPTED, `pending`→ACCEPTED con `TransactionStatusDetails.SUCCESFULL` y se confirma por webhook, `failed`/`canceled`→REJECTED.
  - Error de Stripe → `TransactionStatus.ERROR` con detalle `COMMUNICATION_PROBLEM` (reintetable) o `GENERAL_SYSTEM_ERROR`.

- [ ] **Step 1: tests que fallan:** `authorizationAcceptsAuthorizedIntentWithMatchingAmount`, `authorizationRejectsAmountMismatch`, `authorizationRejectsProcessingIntent` (un pago pendiente nunca produce AUTHORIZATION), `captureOfCapturedIntentDoesNotCallStripe`, `captureUsesStableIdempotencyKey`, `partialCaptureCapturesRequestedAmount`, `voidCancelsAuthorizedIntent`, `voidRefundsCapturedIntent`, `refundMapsPendingToAccepted`, `stripeErrorMapsToErrorStatus`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implementar y registrar.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: commit** `feat(core): Stripe commands for authorization, capture, partial capture, void and refund`

### Task 6: Estrategia de autorización

**Files:**
- Create: `stripecore/src/com/stripe/core/order/StripeCommercePaymentAuthorizationStrategy.java`
- Modify: `stripecore/resources/stripecore-spring.xml` (alias `commercePaymentAuthorizationStrategy` → `stripeCommercePaymentAuthorizationStrategy`, parent `defaultPaymentAuthorizationStrategy`); `stripecore/extensioninfo.xml` (añadir `commerceservices`, quitar `assistedserviceservices`)
- Test: `stripecore/testsrc/com/stripe/core/order/StripeCommercePaymentAuthorizationStrategyTest.java`

**Interfaces:**
- Produces: `public PaymentTransactionEntryModel authorizePaymentAmount(CommerceCheckoutParameter parameter)`: si `cart.paymentInfo` es `StripePaymentInfoModel` → `paymentService.authorize(merchantCode, amount, currency, deliveryAddress, stripePaymentInfo.getStripePaymentIntentId(), null, "Stripe")`; si ACCEPTED, enlaza `transaction.order=cart` y `transaction.info=paymentInfo` y guarda (como SAP); si no, borra transacción y entrada (como SAP). Ejecuta los `authorizePaymentHooks` igual que el padre. Si no es Stripe → `super`.

- [ ] **Step 1:** tests `stripeCartUsesPaymentIntentIdAsSubscriptionId`, `acceptedEntryIsLinkedToCart`, `rejectedEntryIsRemoved`, `nonStripeCartDelegatesToDefault`. FAIL.
- [ ] **Step 2-4:** implementar; PASS.
- [ ] **Step 5: commit** `feat(core): payment authorization strategy for Stripe payment infos`

### Task 7: Autorizador del checkout, bloqueo y facades hijos

**Files:**
- Create: `stripefacades/src/com/stripe/facades/checkout/StripeCheckoutAuthorizer.java` (+ `impl/DefaultStripeCheckoutAuthorizer.java`), `stripefacades/src/com/stripe/facades/checkout/StripeCheckoutException.java` (con `enum Reason { AMOUNT_MISMATCH, PAYMENT_FAILED, PAYMENT_NOT_FOUND, ALREADY_PLACED }`)
- Create: `stripecore/src/com/stripe/core/order/StripeOrderPlacementLockService.java` (+ impl)
- Rewrite: `stripefacades/src/com/stripe/facades/order/impl/StripeCheckoutFacade.java` (hijo de `defaultCheckoutFacade`); Create: `stripefacades/src/com/stripe/facades/order/impl/StripeAcceleratorCheckoutFacade.java` (hijo de `defaultAcceleratorCheckoutFacade`)
- Modify: `stripefacades/resources/stripefacades-spring.xml` (alias `checkoutFacade` y `acceleratorCheckoutFacade`); `stripefacades/extensioninfo.xml` (añadir `acceleratorfacades`)
- Test: `stripefacades/testsrc/com/stripe/facades/checkout/impl/DefaultStripeCheckoutAuthorizerTest.java`, `stripecore/testsrc/com/stripe/core/order/impl/DefaultStripeOrderPlacementLockServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `StripePaymentIntentService`, `StripeGateway`, `StripeAmountConverter`, `CommerceCheckoutService`, DAOs.
- Produces:
  - `StripeOrderPlacementLockService`: `boolean tryAcquire(String paymentIntentId)` (inserta `StripeOrderPlacementLock`; `false` si la inserción viola el índice único); `void release(String paymentIntentId)` (solo si la creación del pedido falla).
  - `StripeCheckoutAuthorizer.authorize(CartModel cart) -> boolean` (bean `stripeCheckoutAuthorizer`):
    1. Recupera el PI del `StripePaymentInfo` del carrito.
    2. Compara `amount`/`currency` del PI con `toMinorUnits(cart.totalPrice, cart.currency)`. Si no coinciden y el PI ya tiene dinero (AUTHORIZED/CAPTURED/PROCESSING): `releaseFunds`, quita el PI del carrito y lanza `StripeCheckoutException(AMOUNT_MISMATCH)`.
    3. `tryAcquire(piId)`; si `false` → `StripeCheckoutException(ALREADY_PLACED)`.
    4. AUTHORIZED/CAPTURED → `commerceCheckoutService.authorizePayment(parameter)` (estrategia de Task 6); rellena `cardBrand`, `cardLast4`, `cardExpMonth`, `cardExpYear`, `paymentMethodType`, `paymentMethodId` desde `payment_method` del PI; record → AUTHORIZED/CAPTURED. Devuelve `true` si la entrada es ACCEPTED.
    5. PROCESSING → record PROCESSING, rellena `paymentMethodType`, devuelve `true` sin entrada AUTHORIZATION.
    6. FAILED/CANCELED/REQUIRES_ACTION → `release(piId)` y `false`.
  - `StripeCheckoutFacade.authorizePayment(String securityCode)` y `StripeAcceleratorCheckoutFacade.authorizePayment(String)`: si `cart.paymentInfo instanceof StripePaymentInfoModel` → `stripeCheckoutAuthorizer.authorize(cart)`; si no → `super`.

- [ ] **Step 1: tests que fallan:** `acceptedIntentCreatesAcceptedAuthorization`, `processingIntentReturnsTrueWithoutAuthorizationEntry`, `failedIntentReturnsFalseAndReleasesLock`, `amountMismatchReleasesFundsAndThrows`, `secondPlacementForSamePaymentIntentIsRejectedByLock`, `cardDisplayDataIsCopied`; integración `lockCanBeAcquiredOnlyOnce`, `lockCanBeReacquiredAfterRelease`.
- [ ] **Step 2-4:** implementar; PASS.
- [ ] **Step 5: commit** `feat(facades): checkout authorizer with amount check and placement lock`

### Task 8: OCC — endpoints, validador, errores y datos del pago en el pedido

**Files:**
- Rewrite: `stripeocc/src/com/stripe/controllers/StripePaymentController.java`
- Create: `stripeocc/src/com/stripe/validators/StripePlaceOrderCartValidator.java`; `stripeocc/src/com/stripe/errors/StripeErrorCode.java`
- Modify: `stripeocc/resources/occ/v2/stripeocc/web/spring/stripeocc-web-spring.xml` (alias `placeOrderCartValidator` → `stripePlaceOrderCartValidator`, parent `defaultPlaceOrderCartValidator`; resolución de `StripeCheckoutException` a 400 con `StripeErrorCode`); `stripeocc/project.properties`
- Create: `stripefacades/src/com/stripe/facades/order/converters/populators/StripeOrderPaymentPopulator.java`; Modify: `stripefacades/resources/stripefacades-beans.xml` (`StripePaymentInfoData`: `paymentMethodType`, `cardBrand`, `cardLast4`, `cardExpMonth`, `cardExpYear`, `paymentStatus` (`ACCEPTED`|`PENDING`|`FAILED`); propiedad `stripePaymentInfo` en `de.hybris.platform.commercefacades.order.data.AbstractOrderData`); `stripeocc/resources/stripeocc-beans.xml` (`StripePaymentInfoWsDTO` mismo contenido y propiedad `stripePaymentInfo` en `de.hybris.platform.commercewebservicescommons.dto.order.AbstractOrderWsDTO`); `stripefacades/resources/stripefacades-spring.xml` (`modifyPopulatorList` en `orderConverter` y `extendedCartConverter`)
- Delete: `StripePaymentInfoPopulator.java`, `StripeOrderPaymentInfoPopulator.java`, el bean `StripeConfirmData`, `dto/StripeIntentWsDTO.java` y `dto/StripeWebhookResponseWsDTO.java` (pasan a beans.xml), `controllers/BaseController.java` (usar el de `commercewebservices` vía herencia o `@Resource dataMapper`)
- Test: `stripeocc/testsrc/com/stripe/validators/StripePlaceOrderCartValidatorTest.java`, `stripefacades/testsrc/com/stripe/facades/order/converters/populators/StripeOrderPaymentPopulatorTest.java`, `stripeocc/testsrc/com/stripe/controllers/StripePaymentControllerTest.java`

**Interfaces:**
- Consumes: `StripePaymentIntentService.prepareForCart`, `StripeConfigurationService.getPublishableKey`, `StripeWebhookFacade` (Task 9).
- Produces (OCC):
  - `POST /{baseSiteId}/users/{userId}/carts/{cartId}/payments/stripe/intent` → `StripeIntentWsDTO {paymentIntentId, clientSecret, status}`. Prepara **y sincroniza** el importe. La librería lo llama al montar el formulario y otra vez justo antes de confirmar (spec 6.3).
  - `GET /{baseSiteId}/payments/stripe/config` → `StripeConfigWsDTO {publishableKey}`. Sin `@Secured` (es pública por naturaleza).
  - `POST /{baseSiteId}/payments/stripe/webhook` → Task 9.
  - Errores: `StripeErrorCode` = `STRIPE_AMOUNT_MISMATCH`, `STRIPE_PAYMENT_FAILED`, `STRIPE_ALREADY_PLACED`, `STRIPE_UNAVAILABLE`. El cuerpo `ErrorWsDTO` lleva `reason` = el código y `message` genérico. El detalle de Stripe va al log.
  - `StripePlaceOrderCartValidator.validate(...)`: si el carrito de sesión tiene `StripePaymentInfoModel` con PI, no rechaza por `cart.paymentInfoNotSet`; en todo lo demás delega en el padre.
  - `StripeOrderPaymentPopulator`: rellena `AbstractOrderData.stripePaymentInfo`; `paymentStatus` = `ACCEPTED` si hay AUTHORIZATION aceptada, `PENDING` si el record está en PROCESSING, `FAILED` si está FAILED/CANCELED.

- [ ] **Step 1: tests que fallan:** `validatorAcceptsStripeCartWithoutCardData`, `validatorDelegatesForOtherCarts`, `populatorMarksPendingPayment`, `populatorCopiesCardDisplayData`, `intentEndpointReturnsClientSecretWithoutStoringIt`, `configEndpointReturnsPublishableKeyForSite`, `checkoutExceptionMapsToStableErrorCode`.
- [ ] **Step 2-4:** implementar; PASS. Comprobar en la plataforma que el alias del validador gana (`ant all`, arrancar, `POST .../orders` con un carrito Stripe no da `cart.paymentInfoNotSet`).
- [ ] **Step 5: commit** `feat(occ): intent sync, config endpoint, place-order validator and payment info on orders`

### Task 9: Recepción de webhooks

**Files:**
- Create: `stripefacades/src/com/stripe/facades/webhook/StripeWebhookFacade.java` (+ `impl/DefaultStripeWebhookFacade.java`), `stripefacades/src/com/stripe/facades/webhook/StripeWebhookReceipt.java` (`enum { ACCEPTED, DUPLICATE, INVALID_SIGNATURE }`)
- Delete: `stripecore/src/com/stripe/core/stripe/StripeWebhookService.java`, `StripeWebhookProcessResult.java`, `stripefacades/.../data/StripeWebhookResultData.java`
- Test: `stripefacades/testsrc/com/stripe/facades/webhook/impl/DefaultStripeWebhookFacadeTest.java`

**Interfaces:**
- Consumes: `StripeGateway.constructEvent`, `StripeWebhookEventDao`, `TaskService`.
- Produces: `StripeWebhookReceipt receive(String payload, String signatureHeader)`: firma inválida → `INVALID_SIGNATURE` (el controlador responde 400); id ya existente → `DUPLICATE` (200); si no, guarda `StripeWebhookEvent` (RECEIVED) y programa un `TaskModel` con `runnerBean="stripeWebhookEventTaskRunner"`, `contextItem` = el evento, `executionDate` = ahora → `ACCEPTED` (200). La inserción duplicada concurrente (violación del índice) también da `DUPLICATE`. El controlador nunca procesa el evento en el hilo de la petición.

- [ ] **Step 1: tests que fallan:** `invalidSignatureIsRejected`, `duplicateEventIsNotScheduledTwice`, `newEventIsStoredAndScheduled`, `acceptsEventWithDifferentApiVersion` (evento firmado con `api_version` distinto se guarda y se programa).
- [ ] **Step 2-4:** implementar; PASS.
- [ ] **Step 5: commit** `feat(facades): signed, idempotent webhook intake scheduled on TaskService`

### Task 10: Procesamiento de webhooks

**Files:**
- Create: `stripecore/src/com/stripe/core/webhook/StripeWebhookEventTaskRunner.java` (`TaskRunner<TaskModel>`), `stripecore/src/com/stripe/core/webhook/StripePaymentIntentEventHandler.java` (+ impl), `stripecore/src/com/stripe/core/webhook/StripeRefundEventHandler.java` (+ impl)
- Test: `stripecore/testsrc/com/stripe/core/webhook/StripeWebhookEventTaskRunnerTest.java`, `.../impl/*HandlerTest.java`

**Interfaces:**
- Consumes: DAOs, `StripeGateway`, `PaymentService`, `StripeOrderPlacementService` (Task 11), `StripeOrderCancellationService` (Task 12).
- Produces:
  - Runner: deserializa el objeto (`getObject()` o, si está vacío, `deserializeUnsafe()`), despacha por `type`, marca el evento PROCESSED / IGNORED / FAILED, incrementa `attempts`. Error temporal (`StripeGatewayException.isRetryable()`, bloqueo) → `RetryLaterException` con retraso exponencial (30 s, 2, 8, 30 min; máximo 5 intentos) y después FAILED con `lastError`.
  - `payment_intent.succeeded` / `.amount_capturable_updated` / `.processing`:
    - sin record → IGNORED;
    - record con pedido y sin AUTHORIZATION aceptada y estado AUTHORIZED/CAPTURED → `paymentService.authorize` sobre la transacción del pedido (misma llamada que la estrategia), record → AUTHORIZED/CAPTURED; el envoltorio del order process (Task 12) lo verá en su siguiente reintento;
    - record sin pedido → si `now < record.createdTime + gracePeriod` → `RetryLaterException` hasta el final del plazo; si no → `StripeOrderPlacementService.placeOrderFor(paymentIntent)`.
  - `payment_intent.payment_failed` / `.canceled`: record → FAILED/CANCELED; si tiene pedido pendiente (sin AUTHORIZATION aceptada) → `StripeOrderCancellationService.cancelForFailedPayment(order)`.
  - `charge.refunded` / `refund.updated`: actualiza la entrada REFUND_FOLLOW_ON cuyo `requestId` es el id del refund; si no existe → IGNORED.
  - Cualquier otro tipo → IGNORED.

- [ ] **Step 1: tests que fallan:** `ignoresEventForUnknownPaymentIntent`, `succeededEventAuthorizesPendingOrder`, `succeededEventWithinGracePeriodRetriesLater`, `succeededEventAfterGracePeriodPlacesOrder`, `failedEventCancelsPendingOrder`, `refundEventUpdatesRefundEntry`, `retryableErrorRetriesWithBackoff`, `fifthFailureMarksEventFailed`.
- [ ] **Step 2-4:** implementar; registrar el bean `stripeWebhookEventTaskRunner`; PASS.
- [ ] **Step 5: commit** `feat(core): webhook processing with grace period, retries and order updates`

### Task 11: Creación del pedido desde el servidor (red de seguridad)

**Files:**
- Create: `stripecore/src/com/stripe/core/order/StripeOrderPlacementService.java` (+ impl)
- Test: `stripecore/testsrc/com/stripe/core/order/impl/DefaultStripeOrderPlacementServiceTest.java`

**Interfaces:**
- Consumes: `ImpersonationService` (`commerceservices`), `CommerceCheckoutService`, `CommerceCartService`/`CartService`, `StripePaymentIntentService.releaseFunds`, `StripeOrderPlacementLockService`, `StripeAmountConverter`.
- Produces: `Optional<OrderModel> placeOrderFor(PaymentIntent paymentIntent)`:
  1. Busca el carrito por `metadata.cartCode` y `metadata.baseSiteUid`. Si no existe → `releaseFunds(pi, "cart-not-found")`, vacío.
  2. Importe y moneda deben coincidir con el carrito; si no → `releaseFunds(pi, "amount-mismatch")`, vacío.
  3. `tryAcquire(piId)`; si `false` → vacío (otro camino ya está creando el pedido).
  4. `ImpersonationContext` con `site`, `user` = `cart.user`, `currency` = `cart.currency`, `language` = `cart.user.sessionLanguage` o el del sitio (los dos últimos explícitos: `DefaultImpersonationService` no los toma de un carrito).
  5. Dentro del contexto: autoriza como en Task 7 (AUTHORIZED/CAPTURED → `commerceCheckoutService.authorizePayment`; PROCESSING → sin entrada), `commerceCheckoutService.placeOrder` con `salesApplication=WEB`, enlaza `record.order`, borra el carrito.
  6. Cualquier fallo después del bloqueo → `release(piId)` y relanza (el runner reintenta).

- [ ] **Step 1: tests que fallan:** `placesOrderInCartContext`, `missingCartReleasesFunds`, `amountMismatchReleasesFunds`, `lockHeldByOtherPathSkipsPlacement`, `failureReleasesLock`, `cartIsRemovedAfterPlacement`.
- [ ] **Step 2-4:** implementar; PASS.
- [ ] **Step 5: commit** `feat(core): server-side order placement from webhook with refund safety net`

### Task 12: Order process — espera del pago, captura y cancelación por pago fallido; fin de `stripefulfilmentprocess`

**Files:**
- Create: `stripecore/src/com/stripe/core/process/StripeProcessActionsPostProcessor.java` (`BeanPostProcessor`), `stripecore/src/com/stripe/core/process/StripeAwaitPaymentCheckOrderAction.java`, `stripecore/src/com/stripe/core/process/StripeTakePaymentAction.java`, `stripecore/src/com/stripe/core/order/StripeOrderCancellationService.java` (+ impl)
- Modify: `stripecore/resources/stripecore-items.xml` (valor `PAYMENT_PENDING` en el enum dinámico `OrderStatus`); `stripecore/resources/stripecore-spring.xml` (post-processor; entrada `PAYMENT_PENDING`→`paymentPending` añadida a `acceleratorStatusDisplayMap` con `<bean parent="mapMergeDirective">` solo si existe; si no, `statusDisplay` por defecto); `stripecore/extensioninfo.xml` (añadir `basecommerce`, `processing` si hace falta)
- Delete: la extensión completa `core-customize/hybris/custom/stripe/stripefulfilmentprocess/` y su `extName` en la receta
- Test: `stripecore/testsrc/com/stripe/core/process/*Test.java`, `stripecore/testsrc/com/stripe/core/order/impl/DefaultStripeOrderCancellationServiceTest.java`

**Interfaces:**
- Consumes: DAOs, `StripeConfigurationService` (timeout y retry), `StripeGateway`, `OrderCancelService`, `PaymentService`.
- Produces:
  - `StripeProcessActionsPostProcessor`: envuelve el bean `checkOrderAction` con `StripeAwaitPaymentCheckOrderAction(delegate)` y el bean `takePaymentAction` con `StripeTakePaymentAction(delegate)`, sea cual sea la extensión que los define (OMS, clásico o la copia del cliente). Si un bean no existe, no hace nada.
  - `StripeAwaitPaymentCheckOrderAction.executeAction(OrderProcessModel)`: si el pedido no es de Stripe o ya tiene AUTHORIZATION aceptada → delega. Si el record está PROCESSING y `now < order.date + pendingTimeout` → pone `OrderStatus.PAYMENT_PENDING` y lanza `RetryLaterException` con `getPendingPaymentRetryDelay()`. Si el record está FAILED/CANCELED o se cumple el plazo → `StripeOrderCancellationService.cancelForFailedPayment(order)` y devuelve `NOK`. Si el pedido ya está CANCELLED → `NOK`.
  - `StripeTakePaymentAction`: si el pedido no es de Stripe → delega. Si es de Stripe: para cada transacción, importe = total actual del pedido; si `importe < autorizado` → `paymentService.partialCapture(txn, importe)`; si no → `paymentService.capture(txn)`; estado `PAYMENT_CAPTURED` o `PAYMENT_NOT_CAPTURED`; devuelve OK/NOK como las acciones de SAP. No llama al delegado (evita la doble captura de OMS y el salto silencioso del clásico).
  - `StripeOrderCancellationService.cancelForFailedPayment(OrderModel)`: cancelación completa con `OrderCancelService` (petición `OrderCancelRequest` completa, motivo `OTHER`, notas `stripe-payment-failed`), cancela el PI si sigue vivo; es idempotente.

- [ ] **Step 1: tests que fallan:** `pendingPaymentRetriesLaterAndSetsPaymentPending`, `acceptedPaymentDelegates`, `expiredPendingPaymentCancelsOrder`, `failedPaymentCancelsOrder`, `nonStripeOrderDelegates`, `takePaymentCapturesCurrentOrderTotal`, `takePaymentAfterPartialCancelUsesPartialCapture`, `takePaymentDoesNotCallDelegateForStripeOrders`, `postProcessorWrapsOnlyNamedBeans`, `cancelForFailedPaymentIsIdempotent`.
- [ ] **Step 2-4:** implementar; borrar `stripefulfilmentprocess`; `ant all`; PASS.
- [ ] **Step 5: commit** `feat(core): wait for pending payments and capture current order total; remove stripefulfilmentprocess`

### Task 13: Cancelación con OMS y fulfilment clásico

**Files:**
- Create: `stripecore/src/com/stripe/core/order/StripeOrderCancelPaymentServiceStrategy.java`, `stripecore/src/com/stripe/core/order/StripeOrderCancelPaymentServiceAdapter.java`
- Modify: `stripecore/resources/stripecore-spring.xml` (alias `orderCancelPaymentServiceStrategy` → `stripeOrderCancelPaymentServiceStrategy`, parent `defaultOrderCancelPaymentServiceStrategy`); `StripeProcessActionsPostProcessor` envuelve también el bean `OmsOrderCancelPaymentServiceAdapter` (si existe) con `StripeOrderCancelPaymentServiceAdapter(delegate, strategy)`
- Test: `stripecore/testsrc/com/stripe/core/order/StripeOrderCancelPaymentServiceStrategyTest.java`, `.../StripeOrderCancelPaymentServiceAdapterTest.java`

**Interfaces:**
- Produces:
  - Estrategia, `recalculateOrderAndModifyPayments(OrderModel)` y la versión con `OrderCancelRecordEntryModel`: primero `super` (recalcula). Después, solo para pedidos de Stripe con transacción: `capturado = suma de CAPTURE/PARTIAL_CAPTURE aceptadas`; `nuevoTotal = order.totalPrice`.
    - Sin captura y `nuevoTotal == 0` → `paymentService.cancel(authorizationEntry)` (VoidCommand).
    - Sin captura y `nuevoTotal > 0` → nada (la captura cobrará el total actual, Task 12).
    - Con captura y `capturado > nuevoTotal` → `paymentService.refundFollowOn(txn, capturado - nuevoTotal)`.
    - Pedido sin `PaymentTransaction` o no Stripe → solo `super`.
  - Adaptador: para pedidos de Stripe llama a la estrategia; para el resto, al delegado.

- [ ] **Step 1: tests que fallan:** `fullCancelBeforeCaptureVoids`, `partialCancelBeforeCaptureDoesNothing`, `cancelAfterCaptureRefundsDifference`, `fullCancelAfterCaptureRefundsEverything`, `nonStripeOrderIsDelegated`, `omsAdapterDelegatesForNonStripeOrders`.
- [ ] **Step 2-4:** implementar; PASS.
- [ ] **Step 5: commit** `feat(core): cancellation adjusts Stripe payments in OMS and classic fulfilment`

### Task 14: Librería de Spartacus

**Files:**
- Modify: `js-storefront/stripestore/integrations-libs/stripe/core/services/stripe-payment-occ.service.ts`, `.../stripe-payment.service.ts`, `.../components/stripe-payment-form/stripe-payment-form.component.{ts,html}`, `.../root/config/stripe-occ.config.ts`, `.../root/model/stripe-payment.model.ts`, `.../assets/translations/en/stripePayment.json`, `.../package.json` (3.0.0), `schematics/add-stripe/index.ts` (mensajes)
- Create: `.../core/occ/stripe-order-normalizer.ts` (normalizador `ORDER_NORMALIZER`), specs `*.spec.ts` para servicio, normalizador y componente
- Modify (app de referencia): `js-storefront/stripestore/src/app/spartacus/features/stripe/stripe-feature.module.ts` (sin clave), borrar `scripts/write-stripe-config.mjs`, los hooks `pre*` de `package.json` y la línea del fichero generado en `.gitignore`

**Interfaces:**
- Consumes: endpoints de Task 8.
- Produces:
  - OCC keys: `stripeIntent` (igual), `stripeConfig: 'payments/stripe/config'`.
  - `StripePaymentOccService.getConfig(): Observable<{publishableKey: string}>`; `ensureIntent()` igual.
  - `StripePaymentService.getStripe()`: usa `StripePaymentConfig.stripePayment.publishableKey` si existe; si no, `getConfig()` del backend.
  - Flujo del componente: montar → `ensureIntent()`; pulsar pagar → `ensureIntent()` otra vez (sincroniza importe) y, si el `clientSecret` cambió, `elements.fetchUpdates()` antes de `stripe.confirmPayment` (`return_url` = URL actual, `redirect:'if_required'`); éxito o vuelta con `payment_intent` → `placeOrder()`; el servidor decide (no se usa el estado del cliente para decidir). Errores de OCC por `reason`: `STRIPE_AMOUNT_MISMATCH` → mensaje `stripePayment.amountChanged` y se vuelve a montar el Payment Element; `STRIPE_PAYMENT_FAILED` → `stripePayment.paymentFailed`; `STRIPE_ALREADY_PLACED` → navegar a la confirmación del último pedido.
  - Se quita `savePaymentMethod` (checkbox y opción).
  - Normalizador: si `order.stripePaymentInfo.cardLast4` existe, rellena `order.paymentInfo` (`cardType.name` = marca, `cardNumber` = `************`+last4, `expiryMonth`, `expiryYear`) para que el resumen estándar de Spartacus la muestre; si `paymentStatus=PENDING`, nada más (el estado lo muestra `statusDisplay`).
  - Traducciones nuevas: `stripePayment.paymentFailed`, `paymentPending`, `amountChanged`, `verificationFailed`; prefijo correcto en todas; quitar `saveForLater`; en el app i18n, `orderDetails.statusDisplay_paymentPending: "Payment pending"`.

- [ ] **Step 1: specs que fallan:** `uses publishable key from backend when not configured`, `syncs intent before confirming`, `places order after redirect return without reading client status`, `maps STRIPE_AMOUNT_MISMATCH to amountChanged message`, `normalizer maps card data to paymentInfo`.
- [ ] **Step 2:** `npx ng test sap-commerce-stripe-payment --watch=false`; FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** specs PASS; `npx ng build sap-commerce-stripe-payment && npm run build` sin errores.
- [ ] **Step 5: commit** `feat(storefront): backend key, amount sync before confirm, server-decided order placement`

### Task 15: Proyecto de referencia — OMS, EUR, Stripe CLI

**Files:**
- Modify: `core-customize/installer/recipes/b2c_occ_stripe/build.gradle` (añadir `ordermanagementfacades`, `ordermanagementwebservices`, `warehousing`, `warehousingbackoffice`, `warehousingfacades`, `warehousingwebservices`, `yacceleratorordermanagement`, `consignmenttrackingservices`, `customersupportbackoffice` si faltan; quitar `stripefulfilmentprocess`; propiedades de test `stripe.webhook.graceperiod.seconds` y `stripe.payment.pending.*` desde variables de entorno con los defaults del conector)
- Modify: `core-customize/hybris/custom/reference/stripesampledata/resources/stripesampledata/import/sampledata/stores/electronics/store.impex` (EUR en `BaseStore.currencies` y en el sitio `electronics-spa`; `submitOrderProcessCode=order-process`)
- Create: `core-customize/scripts/stripe-listen.sh` (`stripe listen --forward-to https://localhost:9002/occ/v2/electronics-spa/payments/stripe/webhook --skip-verify`, imprime el secreto `whsec_` para `STRIPE_WEBHOOK_SECRET`)
- Modify: `core-customize/manifest.json` (tests: `"packages": ["com.stripe.*"]`)

- [ ] **Step 1:** `rsync` de la receta; `./install.sh -r b2c_occ_stripe setup` e `initialize` con `.env` cargado. Esperado: sin errores de impex.
- [ ] **Step 2:** OCC `GET /occ/v2/electronics-spa/currencies` incluye `EUR`. HAC: `SELECT {code} FROM {DynamicProcessDefinition} WHERE {code}='order-process'` devuelve fila (OMS activo).
- [ ] **Step 3:** arrancar; `core-customize/scripts/stripe-listen.sh` recibe un `stripe trigger payment_intent.succeeded` y el endpoint responde 200 (evento IGNORED, sin record).
- [ ] **Step 4: commit** `chore(reference): OMS, EUR and Stripe CLI webhook forwarding`

### Task 16: Batería e2e

**Files:**
- Create: `js-storefront/stripestore/e2e/support/{stripe.ts,backend.ts,checkout.ts}`; specs `e2e/checkout/*.spec.ts`, `e2e/after-purchase/*.spec.ts`, `e2e/webhooks/*.spec.ts`
- Modify: `e2e/checkout-card.spec.ts` → `e2e/checkout/card-no-3ds.spec.ts`; `e2e/support/occ.ts` (cerrar contextos con `dispose()`), `playwright.config.ts` (proyecto por carpeta)

**Interfaces:**
- Produces (helpers):
  - `stripe.ts`: `retrievePaymentIntent(id)`, `listRefunds(paymentIntentId)` con la API REST y `STRIPE_SECRET_KEY`; `triggerResend(eventId)` vía `stripe events resend`.
  - `backend.ts`: `getOrder(customer, code)` (OCC), `getPaymentEntries(orderCode)` (HAC FlexibleSearch con el script de login CSRF del proyecto 1), `shipOrder(orderCode)` y `cancelOrder(orderCode)` y `createReturn(orderCode)` (OCC de OMS: `ordermanagementwebservices`/`warehousingwebservices`), `waitFor(predicate, timeoutMs)`.
  - `checkout.ts`: `checkoutToPayment(page, {currency})`, `payWithCard(page, number)`, `payWithBancontact(page, outcome: 'authorize'|'fail', returnToShop: boolean)`; el banner de cookies se cierra con `page.addLocatorHandler`; el producto se elige por OCC entre los que tienen stock.

- [ ] **Step 1:** escribir las 17 pruebas de la sección 9.1 del spec, cada una con su comprobación de backend y de Stripe. La 7 cierra la página después de autorizar en la página de prueba de Bancontact. La 9 lanza `placeOrder` por OCC y `stripe events resend` del `payment_intent.succeeded` a la vez. La 10 cambia el carrito por OCC entre la confirmación y la vuelta. Tiempos reducidos por propiedades (`stripe.webhook.graceperiod.seconds=20`, `stripe.payment.pending.retry.seconds=10`).
- [ ] **Step 2:** con servidor, storefront y `stripe-listen.sh` en marcha: `npm run e2e`. Esperado: 17 PASS.
- [ ] **Step 3:** repetir `npm run e2e` una segunda vez. Esperado: 17 PASS.
- [ ] **Step 4: commit** `test(e2e): 17-case Stripe payment battery`

### Task 17: Documentación y versión 3.0.0

**Files:**
- Modify: `README.md` (flujo de pago, momento del cobro, la reserva de tarjeta caduca a los 7 días, Stripe CLI, devolución en el clásico requiere sustituir el mock de SAP, propiedades nuevas, versión de API), `CHANGELOG.md` (3.0.0), `docs/migration/` (nuevo `standard-connector.md`: `StripePaymentInfo` con atributos nuevos, fin de `StripeCheckoutFacade` como alias único, fin de `stripefulfilmentprocess`, endpoints OCC nuevos, errores con código, propiedades nuevas, `PAYMENT_PENDING`)

- [ ] **Step 1:** escribir. **Step 2:** `git grep -n "stripefulfilmentprocess\|StripePaymentProvider\|StripeWebhookService\|sapGenericPaymentInfo"` → sin resultados fuera de `docs/` y `CHANGELOG.md`. **Step 3: commit** `docs: standard connector 3.0.0`
