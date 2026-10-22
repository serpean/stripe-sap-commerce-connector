# Métodos de pago de Stripe y release en GitHub: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** todos los métodos de pago de Stripe para una cuenta de España cumplen la regla número uno en SAP, incluidos los que se pagan días después (SEPA, Multibanco, transferencia), y un workflow de GitHub publica la release con los descargables.

**Arquitectura:** los métodos se tratan por grupo de comportamiento (spec, sección 3). Un estado nuevo del PaymentIntent, `AWAITING_FUNDS`, representa "instrucciones emitidas, el cliente aún no ha pagado" y crea el pedido en "pago pendiente" igual que `PROCESSING`. La espera del pago (`StripeAwaitPaymentCheckOrderAction`) usa plazos por método. La transferencia usa un `Customer` de Stripe por cuenta y un servicio que devuelve el saldo que no corresponde a ningún pedido. Las líneas del pedido se envían como parámetros extra y nunca cambian el importe. La librería de Spartacus muestra las instrucciones. Un workflow con tag `v*` empaqueta extensiones, librería y licencia.

**Tecnologías:** SAP Commerce 2211.55, Java 17 (SapMachine 17.0.12), stripe-java 30.0.0 (API `2025-09-30.clover`), JUnit 4 + Mockito, Spartacus 2211.37, Angular 19, Playwright 1.49.1, Stripe CLI 1.34.0, GitHub Actions, Node 22.

**Spec:** `docs/superpowers/specs/2026-10-21-metodos-de-pago-y-release-design.md`. Base: proyecto 2 (`docs/superpowers/specs/2026-10-19-conector-estandar-design.md`, decisiones en `docs/superpowers/notes/2026-10-21-proyecto2-decisiones.md`).

## Restricciones globales

- Rama: `feat/metodos-de-pago`. Autor: `git -c user.name=serpean -c user.email=sergi9p@gmail.com commit ...`. Sin metadatos de Claude en los commits.
- **Regla número uno:** nunca un cobro en Stripe sin pedido en SAP y sin devolución; nunca un pedido con un importe distinto al cobrado; ningún pedido pasa al sourcing sin que el dinero haya llegado o esté reservado.
- Se mantienen todas las restricciones globales del plan del proyecto 2 (`docs/superpowers/plans/2026-10-20-conector-estandar.md`): solo puntos de extensión de SAP, nada de `REVIEW`, proveedor `"Stripe"`, idempotencia estable, nada de secretos ni `client_secret` en logs, códigos de error estables por OCC, comandos de test con `ant` en `/Volumes/e/y/stripe-ref-platform`, `rsync` de la receta antes de `install.sh`, `@UnitTest`/`@IntegrationTest`.
- Nunca se imprime ni se pasa por línea de comandos una clave de `.env`, ni el contenido de `js-storefront/stripestore/.npmrc`. Para llamar a Stripe con `curl`: `printf 'user = "%s:"\n' "$STRIPE_SECRET_KEY" | curl -K - ...`.
- Nunca se matan procesos que no haya arrancado la propia tarea. Antes de un `kill`, `ps -p <pid> -o command=`.
- Propiedades nuevas (todas con override por sitio `.<baseSiteUid>` al final):
  - `stripe.payment.pending.timeout.<tipo>.seconds`; defaults en `stripecore/project.properties`: `stripe.payment.pending.timeout.sepa_debit.seconds=864000`, `stripe.payment.pending.timeout.multibanco.seconds=1036800`, `stripe.payment.pending.timeout.customer_balance.seconds=1209600`. Sin propiedad por tipo → `stripe.payment.pending.timeout.seconds` (86400).
  - `stripe.capture.manual.methods=card,link,paypal,amazon_pay,revolut_pay,mobilepay,satispay,klarna,sequra,scalapay,billie`.
  - `stripe.customer.create=true` (crear `Customer` de Stripe en cada PaymentIntent; *decisión del plan*: sin `Customer` Stripe no ofrece la transferencia).
  - `stripe.cashbalance.refund.delay.seconds=3600` (espera antes de devolver saldo sin pedido).
- Grupos (spec, sección 3): métodos de pago diferido (grupo E) = `multibanco`, `customer_balance`. Tipos de `next_action` que significan "instrucciones emitidas": `multibanco_display_details`, `display_bank_transfer_instructions`.
- Versión del conector y de la librería al terminar: **3.1.0**.
- Release: secreto `SAP_NPM_AUTH`, registro `https://73554900100900004337.npmsrv.base.repositories.cloud.sap/` para el scope `@spartacus`. Licencia Apache-2.0, línea `Copyright 2026 serpean`.
- El zip de la release nunca contiene: plataforma SAP, `spartacussampledata`, `stripesampledata`, el storefront de ejemplo, la receta, `.env`, `.npmrc`, `lib/`, `classes/`, `gensrc/`, `extensioninfo.xsd`, `items.xsd`.
- Cuenta de pruebas de Stripe: ya tiene activos `sepa_debit`, `multibanco`, `customer_balance`, `paypal`, `sequra`, `bizum`, `google_pay` (spec 2.1). Ninguna tarea cambia la configuración de la cuenta.

## Puntos de atención en la revisión

1. **El cliente vuelve atrás y cambia el carrito después de recibir el cupón de Multibanco o las instrucciones de transferencia, sin pedido creado:** el PaymentIntent con instrucciones no se actualiza (el cupón tiene el importe antiguo); se cancela y se crea uno nuevo. → test `DefaultStripePaymentIntentServiceTest.intentWithIssuedInstructionsIsReplacedNotUpdated` (Task 5).
2. **El dinero llega justo cuando vence el plazo:** antes de cancelar por plazo, se lee el PaymentIntent vivo; si ya está pagado, el pedido sigue. → test `StripeAwaitPaymentCheckOrderActionTest.deadlineDoesNotCancelWhenLiveIntentIsPaid` (Task 6).
3. **Carritos raros para las líneas del pedido:** producto a 0 €, descuento del 100 %, más de 200 líneas, descuento mayor que la suma de líneas. El pago sigue siempre, con o sin líneas. → tests de `DefaultStripeLineItemsBuilderTest` (Task 3).
4. **Invitado que paga con Multibanco o transferencia:** el email del invitado se conoce después de crear el PaymentIntent; el `Customer` y el pago llevan el email antes de confirmar. → test `DefaultStripeCustomerServiceTest.guestCustomerEmailIsUpdatedWhenKnown` (Task 4).
5. **`cash_balance.funds_available` repetido o reenviado:** el saldo se devuelve una sola vez. → test `DefaultStripeCashBalanceServiceTest.refundKeyIsStablePerBalanceAmount` (Task 7).

---

### Task 1: Configuración por método y grupos

**Files:**
- Create: `core-customize/hybris/custom/stripe/stripecore/src/com/stripe/core/payment/StripePaymentMethodGroups.java`
- Modify: `stripecore/src/com/stripe/core/config/StripeConfigurationService.java`, `.../config/impl/DefaultStripeConfigurationService.java`, `stripecore/project.properties`
- Test: `stripecore/testsrc/com/stripe/core/config/impl/DefaultStripeConfigurationServiceTest.java`, `stripecore/testsrc/com/stripe/core/payment/StripePaymentMethodGroupsTest.java`

**Interfaces:**
- Produces:
  - `StripeConfigurationService`: `Duration getPendingPaymentTimeout(String paymentMethodType)` (tipo `null` o sin propiedad → `getPendingPaymentTimeout()`), `boolean isCustomerCreationEnabled()`, `Duration getCashBalanceRefundDelay()`. Clave por tipo: `stripe.payment.pending.timeout.<tipo>.seconds`, resuelta con `readSeconds` (override por sitio incluido).
  - `StripePaymentMethodGroups` (clase final, métodos estáticos): `boolean isDeferred(String paymentMethodType)` (true para `multibanco`, `customer_balance`); `boolean isInstructionsAction(String nextActionType)` (true para `multibanco_display_details`, `display_bank_transfer_instructions`).

- [ ] **Step 1: tests que fallan**
  - `DefaultStripeConfigurationServiceTest.pendingTimeoutPerMethod`: con `stripe.payment.pending.timeout.sepa_debit.seconds=864000` → `getPendingPaymentTimeout("sepa_debit") == Duration.ofSeconds(864000)`.
  - `.pendingTimeoutPerMethodFallsBackToGlobal`: `getPendingPaymentTimeout("card") == Duration.ofSeconds(86400)`; `getPendingPaymentTimeout(null)` igual.
  - `.pendingTimeoutPerMethodSiteOverride`: `...sepa_debit.seconds.electronics-spa=60` con sitio `electronics-spa` → 60 s.
  - `.defaultManualMethods`: sin propiedad → `{card,link,paypal,amazon_pay,revolut_pay,mobilepay,satispay,klarna,sequra,scalapay,billie}`.
  - `.customerAndCashBalanceDefaults`: `isCustomerCreationEnabled()==true`, `getCashBalanceRefundDelay()==Duration.ofSeconds(3600)`.
  - `StripePaymentMethodGroupsTest.groups`: `isDeferred("multibanco")`, `isDeferred("customer_balance")`, `!isDeferred("sepa_debit")`, `!isDeferred(null)`; `isInstructionsAction("multibanco_display_details")`, `isInstructionsAction("display_bank_transfer_instructions")`, `!isInstructionsAction("redirect_to_url")`, `!isInstructionsAction("use_stripe_sdk")`.
- [ ] **Step 2:** `ant unittests -Dtestclasses.extensions=stripecore -Dtestclasses.annotations=unittests -Dtestclasses.packages=com.stripe.core.config.*,com.stripe.core.payment.*`. Esperado: FAIL.
- [ ] **Step 3:** implementar. Actualizar `DEFAULT_MANUAL_METHODS` y `project.properties` con los valores de las restricciones globales.
- [ ] **Step 4:** tests en PASS; `ant all` en verde.
- [ ] **Step 5: commit** `feat(core): per-method pending timeouts and payment method groups`

### Task 2: Modelo de datos

**Files:**
- Modify: `stripecore/resources/stripecore-items.xml`, `stripecore/resources/localization/stripecore-locales_en.properties` (y `_es` si existe), `stripecore/src/com/stripe/core/status/StripePaymentIntentStatus.java`
- Create: `stripecore/src/com/stripe/core/customer/dao/StripeCustomerDao.java`, `.../customer/dao/impl/DefaultStripeCustomerDao.java`
- Test: `stripecore/testsrc/com/stripe/core/status/StripePaymentIntentStatusTest.java`, `stripecore/testsrc/com/stripe/core/dao/impl/StripeRecordsIntegrationTest.java`

**Interfaces:**
- Produces:
  - `StripePaymentInfo` atributos nuevos: `instructionsUrl` (String), `paymentReference` (String), `instructionsExpireAt` (java.util.Date), `amountRemaining` (Long, unidades mínimas), `sepaMandateId` (String), `captureBefore` (java.util.Date).
  - `StripePaymentIntentState` valor nuevo `AWAITING_FUNDS`.
  - Tipo nuevo `StripeCustomer` (tabla `stripe_customer`, typecode `29103`): `customer` (Customer, obligatorio), `accountKey` (String, obligatorio), `stripeCustomerId` (String, obligatorio); índice único `customerAccountIdx` (`customer`, `accountKey`); índice `stripeCustomerIdx` (`stripeCustomerId`).
  - `StripeCustomerDao`: `Optional<StripeCustomerModel> find(CustomerModel customer, String accountKey)`, `Optional<StripeCustomerModel> findByStripeCustomerId(String stripeCustomerId)`.
  - `StripePaymentIntentStatus` valor nuevo `AWAITING_FUNDS` y `public static StripePaymentIntentStatus of(PaymentIntent paymentIntent)`: `requires_action` con `next_action.type` en `StripePaymentMethodGroups.isInstructionsAction` → `AWAITING_FUNDS`; resto igual que `of(String)`.

- [ ] **Step 1: tests que fallan**
  - `StripePaymentIntentStatusTest.multibancoVoucherIsAwaitingFunds`: PI `requires_action` con `next_action.type="multibanco_display_details"` → `AWAITING_FUNDS`.
  - `.bankTransferInstructionsAreAwaitingFunds`: `display_bank_transfer_instructions` → `AWAITING_FUNDS`.
  - `.threeDsIsStillRequiresAction`: `requires_action` con `use_stripe_sdk` → `REQUIRES_ACTION`; con `redirect_to_url` → `REQUIRES_ACTION`.
  - `StripeRecordsIntegrationTest.stripeCustomerIsUniquePerAccount`: guardar dos `StripeCustomer` con el mismo `customer` y `accountKey` → la segunda falla; con otro `accountKey` → OK; `find` y `findByStripeCustomerId` los devuelven.
- [ ] **Step 2:** ejecutar unit e integración de `stripecore` (`ant yunitinit` tras cambiar items). Esperado: FAIL.
- [ ] **Step 3:** implementar items, DAO (FlexibleSearch) y bean `stripeCustomerDao`.
- [ ] **Step 4:** PASS; `ant all` en verde.
- [ ] **Step 5: commit** `feat(core): data model for deferred payments and Stripe customers`

### Task 3: Líneas del pedido

**Files:**
- Create: `stripecore/src/com/stripe/core/payment/StripeLineItemsBuilder.java`, `.../payment/impl/DefaultStripeLineItemsBuilder.java`
- Modify: `stripecore/src/com/stripe/core/payment/impl/DefaultStripePaymentIntentService.java` (`buildCreateParams`, `update`), `stripecore/resources/stripecore-spring.xml`
- Test: `stripecore/testsrc/com/stripe/core/payment/impl/DefaultStripeLineItemsBuilderTest.java`, `.../DefaultStripePaymentIntentServiceTest.java`

**Interfaces:**
- Consumes: `StripeAmountConverter.toMinorUnits`, `chargeAmountMinor`.
- Produces: `StripeLineItemsBuilder.Map<String, Object> build(AbstractOrderModel order, long amountMinor)` (bean `stripeLineItemsBuilder`). Devuelve parámetros extra con forma de formulario de Stripe (`amount_details`, `payment_details`) para `putAllExtraParam`, o un mapa vacío si las líneas no cuadran. `DefaultStripePaymentIntentService` los añade al crear y al actualizar.

Algoritmo (fija el reparto que la spec deja al constructor):
- Cada entrada: `product_name` = nombre del producto (máx. 1024 caracteres), `quantity`, `unit_cost` = `toMinorUnits(basePrice)`, `discount_amount` = `toMinorUnits(basePrice × quantity − totalPrice de la entrada)` si es > 0.
- `L` = Σ (`unit_cost × quantity − discount_amount`); `S` = `toMinorUnits(deliveryCost)`; `T` = `toMinorUnits(totalTax)` si `net`, si no 0.
- `D` = `L + S + T − amountMinor` (descuentos del pedido más redondeo). Si `D < 0`, `D > L`, alguna línea queda negativa o hay más de 200 entradas → mapa vacío y un WARN con el código del carrito.
- `amount_details.shipping.amount` = S, `amount_details.tax.total_tax_amount` = T, `amount_details.discount_amount` = D (solo si > 0), `payment_details.order_reference` = código del carrito. Si la documentación de Stripe permite cambiar `order_reference` en un PaymentIntent ya confirmado, `DefaultStripeOrderPlacementService` lo cambia al código del pedido tras crearlo (sin fallar el pedido si Stripe lo rechaza); si no, se queda el del carrito y se anota en el commit.
- Antes de implementar, el constructor comprueba en `docs.stripe.com/payments/payment-line-items` la fórmula exacta que Stripe valida y si `2025-09-30.clover` necesita una cabecera de vista previa. Si la fórmula no coincide con la de arriba, adapta el algoritmo y lo anota en el commit; si hace falta vista previa, para y lo comenta.

- [ ] **Step 1: tests que fallan** (`DefaultStripeLineItemsBuilderTest`)
  - `linesAddUpToAmount`: 2 entradas (10,00 € × 2; 5,00 € × 1), envío 4,99 €, bruto → `L + S + T − D == amountMinor` y `D == 0`.
  - `orderDiscountGoesToDiscountAmount`: mismo carrito con 3,00 € de descuento de pedido → `discount_amount == 300`.
  - `netPricesSendTax`: `net=true`, `totalTax=4,20` → `total_tax_amount == 420`.
  - `freeProductLineIsAllowed`: entrada a 0 € → mapa no vacío, línea con `unit_cost == 0`.
  - `fullDiscountStillAddsUp`: descuento del 100 % sin envío → `D == L`, mapa no vacío.
  - `discountAboveLinesReturnsEmpty`: `amountMinor` inventado que obliga a `D > L` → mapa vacío, WARN en el log (`StripeLogCapture`).
  - `moreThan200EntriesReturnsEmpty`.
  - `DefaultStripePaymentIntentServiceTest.createSendsLineItems` y `.updateSendsLineItems`: los parámetros enviados al gateway llevan `amount_details`; con builder vacío, no lo llevan y el importe es el mismo.
- [ ] **Step 2:** ejecutar. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: commit** `feat(core): send cart line items to Stripe without changing the amount`

### Task 4: Cliente de Stripe

**Files:**
- Create: `stripecore/src/com/stripe/core/customer/StripeCustomerService.java`, `.../customer/impl/DefaultStripeCustomerService.java`
- Modify: `stripecore/src/com/stripe/core/gateway/StripeGateway.java`, `.../gateway/impl/DefaultStripeGateway.java`, `DefaultStripePaymentIntentService.java`, `stripecore-spring.xml`
- Test: `stripecore/testsrc/com/stripe/core/customer/impl/DefaultStripeCustomerServiceTest.java`, `DefaultStripePaymentIntentServiceTest.java`, `DefaultStripeGatewayTest.java`

**Interfaces:**
- Consumes: `StripeCustomerDao` (Task 2), `isCustomerCreationEnabled()` (Task 1).
- Produces:
  - `StripeGateway`: `Customer createCustomer(CustomerCreateParams params, String idempotencyKey)`, `Customer updateCustomer(String id, CustomerUpdateParams params, String idempotencyKey)`, `String retrieveAccountKey()` (id de la cuenta + `":live"` o `":test"`; se guarda en memoria por clave secreta).
  - `StripeCustomerService.Optional<String> customerIdFor(CartModel cart)` (bean `stripeCustomerService`):
    - deshabilitado → vacío;
    - cliente registrado → `StripeCustomer` de (`cart.user`, `accountKey`); si no existe, lo crea con clave `"customer-" + user.pk + "-" + accountKey`;
    - invitado (`CustomerType.GUEST`) → crea uno por carrito con clave `"customer-cart-" + cart.code` y no lo guarda en `StripeCustomer`;
    - email: el de contacto del cliente (`contactEmail` del invitado o `uid`). Si el `Customer` ya existe y el email ha cambiado, lo actualiza.
  - `DefaultStripePaymentIntentService` pone `customer` al crear y al actualizar. Si Stripe responde `resource_missing` para el `customer`, borra el `StripeCustomer`, crea otro y repite una vez.

- [ ] **Step 1: tests que fallan**
  - `DefaultStripeCustomerServiceTest.registeredCustomerIsCreatedOnceAndReused`: dos llamadas → un `createCustomer`, un `StripeCustomer` guardado.
  - `.separateCustomerPerAccount`: otro `accountKey` → otro `createCustomer`.
  - `.guestGetsOneCustomerPerCart`: dos llamadas con el mismo carrito → misma clave de idempotencia `"customer-cart-<code>"`; nada guardado.
  - `.guestCustomerEmailIsUpdatedWhenKnown`: invitado sin email y después con email → `updateCustomer` con el email.
  - `.disabledReturnsEmpty`.
  - `DefaultStripePaymentIntentServiceTest.missingStripeCustomerIsRecreated`: `createPaymentIntent` lanza `StripeGatewayException` con código `resource_missing` → se borra el `StripeCustomer`, se crea otro, y el segundo intento lleva el id nuevo.
- [ ] **Step 2:** ejecutar. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS; `ant all` en verde.
- [ ] **Step 5: commit** `feat(core): Stripe customer per account for bank transfers`

### Task 5: Pedido con instrucciones de pago (grupo E) y datos del pago

**Files:**
- Create: `stripecore/src/com/stripe/core/payment/StripePaymentDetailsCopier.java`, `.../payment/impl/DefaultStripePaymentDetailsCopier.java`
- Modify:
  - `stripecore/.../payment/impl/DefaultStripePaymentIntentService.java` (`prepareForCart`)
  - `stripecore/.../order/impl/DefaultStripeOrderPlacementService.java` (`PLACEABLE`, `authorize`)
  - `stripefacades/.../checkout/impl/DefaultStripeCheckoutAuthorizer.java` (`WITH_MONEY`, `switch`)
  - `stripecore/.../webhook/impl/DefaultStripePaymentIntentEventHandler.java` (`CONFIRMATION_EVENTS`, `ALIVE`, `stateOf`)
  - `stripecore/.../webhook/StripeWebhookEventTaskRunner.java` (`dispatch`)
  - `stripefacades/.../order/converters/populators/StripeOrderPaymentPopulator.java`, `stripefacades/resources/stripefacades-beans.xml`
  - `stripeocc/resources/stripeocc-beans.xml`, `stripeocc/resources/occ/v2/stripeocc/web/spring/stripeocc-web-spring.xml`
- Test: `DefaultStripePaymentIntentServiceTest`, `DefaultStripeOrderPlacementServiceTest`, `DefaultStripeCheckoutAuthorizerTest`, `DefaultStripePaymentIntentEventHandlerTest`, `StripeWebhookEventTaskRunnerTest`, `StripeOrderPaymentPopulatorTest`, `stripecore/testsrc/com/stripe/core/payment/impl/DefaultStripePaymentDetailsCopierTest.java`

**Interfaces:**
- Consumes: `StripePaymentIntentStatus.of(PaymentIntent)` y `AWAITING_FUNDS` (Task 2).
- Produces:
  - `StripePaymentDetailsCopier.void copy(PaymentIntent paymentIntent, StripePaymentInfoModel paymentInfo)` (bean `stripePaymentDetailsCopier`). Copia lo que ya copiaba el autorizador (tipo, marca, últimos 4, caducidad) y además: Multibanco → `paymentReference` (`entity` + `reference`), `instructionsUrl` (`hosted_voucher_url`), `instructionsExpireAt` (`expires_at`); transferencia → `paymentReference` (`reference`), `instructionsUrl` (`hosted_instructions_url`), `amountRemaining` (`amount_remaining`); SEPA → `sepaMandateId` (`latest_charge.payment_method_details.sepa_debit.mandate`); tarjeta y otros con reserva → `captureBefore` (`latest_charge.payment_method_details.<tipo>.capture_before`, si existe). El autorizador y el manejador de webhooks usan este bean en lugar de su copia propia.
  - Todas las llamadas que deciden con el estado del PaymentIntent usan `of(PaymentIntent)`, no `of(String)`.
  - `AWAITING_FUNDS` se comporta como `PROCESSING` al crear el pedido: está en `PLACEABLE`, `WITH_MONEY` y `ALIVE`; sin entrada AUTHORIZATION; estado del registro `AWAITING_FUNDS`.
  - `prepareForCart`: un PaymentIntent `AWAITING_FUNDS` sin pedido no se actualiza; se cancela (`releaseFunds`) y se crea uno nuevo.
  - Eventos nuevos en `dispatch` hacia `stripePaymentIntentEventHandler`: `payment_intent.requires_action` y `payment_intent.partially_funded` (en `CONFIRMATION_EVENTS`). Un `requires_action` que no es `AWAITING_FUNDS` → IGNORED. `partially_funded` actualiza `amountRemaining` del pedido y no cambia su estado.
  - `StripePaymentInfoData` y `StripePaymentInfoWsDTO` campos nuevos: `instructionsUrl`, `paymentReference`, `instructionsExpireAt` (Date), `amountRemaining` (String, importe formateado con la moneda del pedido). Mapeo OCC: `DEFAULT` y `FULL` incluyen los cuatro. `paymentStatus` = `"PENDING"` también para `AWAITING_FUNDS`.

- [ ] **Step 1: tests que fallan**
  - `DefaultStripePaymentIntentServiceTest.intentWithIssuedInstructionsIsReplacedNotUpdated`.
  - `DefaultStripeOrderPlacementServiceTest.awaitingFundsPlacesPendingOrderWithoutAuthorization`.
  - `DefaultStripeCheckoutAuthorizerTest.awaitingFundsIsAcceptedAsPending` y `.threeDsRequiresActionIsStillPaymentFailed`.
  - `DefaultStripePaymentIntentEventHandlerTest.requiresActionWithVoucherPlacesOrderAfterGracePeriod`, `.requiresActionForThreeDsIsIgnored`, `.partiallyFundedUpdatesAmountRemaining`.
  - `StripeWebhookEventTaskRunnerTest.routesRequiresActionAndPartiallyFunded`.
  - `DefaultStripePaymentDetailsCopierTest.multibanco`, `.bankTransfer`, `.sepaMandate`, `.cardCaptureBefore` (con objetos `PaymentIntent` deserializados de JSON de ejemplo en `testsrc`).
  - `StripeOrderPaymentPopulatorTest.copiesInstructionsAndMarksPending`.
- [ ] **Step 2:** ejecutar unit de `stripecore`, `stripefacades`, `stripeocc`. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS; `ant all` en verde.
- [ ] **Step 5: commit** `feat: place pending orders for voucher and bank transfer payments`

### Task 6: Espera del pago por método y pedidos pendientes

**Files:**
- Modify: `stripecore/src/com/stripe/core/process/StripeAwaitPaymentCheckOrderAction.java`, `stripecore/.../order/impl/DefaultStripePaymentAdjustmentService.java`
- Create: `stripecore/src/com/stripe/core/order/StripePendingPaymentCancelDenialStrategy.java`
- Modify: `stripecore/resources/stripecore-spring.xml` (registro de la estrategia de denegación)
- Test: `StripeAwaitPaymentCheckOrderActionTest`, `stripecore/testsrc/com/stripe/core/order/StripePendingPaymentCancelDenialStrategyTest.java`, `DefaultStripeOrderCancellationServiceIntegrationTest`

**Interfaces:**
- Consumes: `getPendingPaymentTimeout(String)` (Task 1), `AWAITING_FUNDS` (Task 2), `StripePaymentInfo.paymentMethodType`.
- Produces:
  - La acción calcula el plazo con `getPendingPaymentTimeout(paymentInfo.getPaymentMethodType())`. `AWAITING_FUNDS` y `PROCESSING` esperan (`RetryLaterException`). Al vencer el plazo, vuelve a leer el PaymentIntent: si está pagado, sigue; si no, cancela con `cancelForFailedPayment`.
  - `StripePendingPaymentCancelDenialStrategy implements OrderCancelDenialStrategy`: deniega la cancelación **parcial** de un pedido de Stripe cuyo registro está en `PROCESSING` o `AWAITING_FUNDS`, con un `DefaultOrderCancelDenialReason` (código `stripe-payment-pending`, descripción en inglés). La cancelación completa no se deniega. Se añade a la lista de estrategias de denegación de `defaultOrderCancelService` con un `ListMergeDirective` (el constructor confirma el nombre del bean y la propiedad en `basecommerce-spring.xml` de 2211.55).
  - `DefaultStripePaymentAdjustmentService`: `AWAITING_FUNDS` se trata igual que `PROCESSING` (log, sin ajuste).

- [ ] **Step 1: tests que fallan**
  - `StripeAwaitPaymentCheckOrderActionTest.sepaUsesItsOwnTimeout`: pedido SEPA de hace 2 días → espera (con el plazo global de 1 día habría cancelado).
  - `.awaitingFundsWaits`.
  - `.deadlineDoesNotCancelWhenLiveIntentIsPaid`.
  - `.multibancoProcessingAfterVoucherExpiryKeepsWaitingUntilDeadline`.
  - `StripePendingPaymentCancelDenialStrategyTest.deniesPartialCancelWhilePending`, `.allowsFullCancelWhilePending`, `.allowsPartialCancelWhenPaid`, `.ignoresNonStripeOrders`.
  - `DefaultStripeOrderCancellationServiceIntegrationTest.partialCancelOfPendingOrderIsDeniedByOrderCancelService` (a través de `OrderCancelService.isCancelPossible`).
- [ ] **Step 2:** ejecutar. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS (unit e integración); `ant all` en verde.
- [ ] **Step 5:** carga de los reintentos (spec 7.3): medir en la plataforma de referencia el coste de una ejecución de la acción en espera (consultas y llamada a Stripe). Con un reintento cada 300 s durante 14 días son unas 4.000 ejecuciones por pedido. Si cada ejecución llama a Stripe, añadir la propiedad `stripe.payment.pending.retry.<tipo>.seconds` (por defecto 3600 para `multibanco` y `customer_balance`, misma resolución que el plazo) con su test `StripeAwaitPaymentCheckOrderActionTest.deferredMethodsUseLongerRetry`, y anotarlo en el commit.
- [ ] **Step 6: commit** `feat(core): per-method pending payment wait and no partial cancel while pending`

### Task 7: Saldo de transferencia sin pedido

**Files:**
- Create: `stripecore/src/com/stripe/core/customer/StripeCashBalanceService.java`, `.../customer/impl/DefaultStripeCashBalanceService.java`, `stripecore/src/com/stripe/core/customer/StripeCashBalanceRefundTaskRunner.java`
- Modify: `StripeGateway`, `DefaultStripeGateway`, `StripeWebhookEventTaskRunner.java` (`dispatch`), `stripecore-spring.xml`
- Test: `stripecore/testsrc/com/stripe/core/customer/impl/DefaultStripeCashBalanceServiceTest.java`, `StripeWebhookEventTaskRunnerTest`

**Interfaces:**
- Consumes: `StripeCustomerDao.findByStripeCustomerId`, `getCashBalanceRefundDelay()`, registros `StripePaymentIntentRecord`.
- Produces:
  - `StripeGateway`: `long retrieveCashBalance(String customerId, String currency)` (unidades mínimas disponibles), `Refund createCashBalanceRefund(String customerId, long amount, String currency, String idempotencyKey)`. Antes de implementar, el constructor confirma en la documentación de Stripe la llamada de devolución de saldo (`/v1/refunds` con `customer` y `origin=customer_balance`) y que Stripe pide los datos bancarios al cliente.
  - `StripeCashBalanceService`: `void scheduleRefundCheck(String customerId, String currency)` (crea una tarea con `stripeCashBalanceRefundTaskRunner`, retraso `getCashBalanceRefundDelay()`, una sola tarea pendiente por cliente y moneda) y `void refundUnappliedBalance(String customerId, String currency)`:
    - si el cliente tiene algún registro de PaymentIntent de transferencia en `CREATED` o `AWAITING_FUNDS` con pedido activo o carrito existente → no devuelve nada (Stripe aún puede aplicarlo);
    - si no, devuelve el saldo completo con clave `"cash-refund-" + customerId + "-" + currency + "-" + saldo`.
  - `dispatch`: `cash_balance.funds_available` → `scheduleRefundCheck`, resultado PROCESSED.
  - Fallo de Stripe al devolver → reintento con la misma pauta del proyecto 2 (5 intentos) y ERROR en el log al agotarse.
- Decisión del plan: la cancelación de un PaymentIntent de transferencia financiado en parte devuelve ese dinero al saldo del cliente en Stripe; el evento `cash_balance.funds_available` posterior lo devuelve con este mismo servicio. No hay otra ruta de devolución para la transferencia.

- [ ] **Step 1: tests que fallan**
  - `DefaultStripeCashBalanceServiceTest.refundsBalanceWhenNoOpenTransfer`.
  - `.keepsBalanceWhileTransferIsOpen`.
  - `.nothingToRefundWhenBalanceIsZero`.
  - `.refundKeyIsStablePerBalanceAmount`: dos ejecuciones con el mismo saldo → misma clave.
  - `.onlyOnePendingCheckPerCustomerAndCurrency`.
  - `StripeWebhookEventTaskRunnerTest.fundsAvailableSchedulesRefundCheck`.
- [ ] **Step 2:** ejecutar. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS.
- [ ] **Step 5: commit** `feat(core): refund bank transfer balance that matches no open order`

### Task 8: Reserva para todos los métodos, fecha límite de captura y devoluciones fuera de plazo

**Files:**
- Modify: `DefaultStripePaymentIntentService.buildPaymentMethodOptions`, `stripecore/src/com/stripe/core/commands/impl/AbstractStripeCommand.java`, `.../commands/impl/AbstractStripeCaptureCommand.java`, `.../commands/impl/StripeRefundCommand.java`, `DefaultStripePaymentAdjustmentService.java`
- Test: `DefaultStripePaymentIntentServiceTest`, `StripeCaptureCommandTest`, `StripeRefundCommandTest`, `stripecore/testsrc/com/stripe/core/order/impl/DefaultStripePaymentAdjustmentServiceTest.java` (crear si no existe)

**Interfaces:**
- Produces:
  - `buildPaymentMethodOptions` pide `capture_method=manual` para cada método de `getManualCaptureMethods()`. Para los que `stripe-java` 30.0.0 tiene clase de opciones, con la clase; para los demás, con `putExtraParam("payment_method_options[<tipo>][capture_method]", "manual")`. Se quitan `affirm` y `afterpay_clearpay`. Un tipo desconocido se ignora con WARN.
  - `AbstractStripeCommand.PERMANENT_CODES = Set.of("charge_expired_for_capture", "charge_expired_for_refund")` (el constructor confirma los códigos exactos en la documentación de errores de Stripe y ajusta el conjunto). Con uno de esos códigos, el resultado es `REJECTED` con detalle `"stripe:" + code`, nunca `ERROR`.
  - `DefaultStripePaymentAdjustmentService`: un resultado `REJECTED` de devolución o anulación no programa reintento; deja ERROR en el log con el código del pedido y el motivo.

- [ ] **Step 1: tests que fallan**
  - `DefaultStripePaymentIntentServiceTest.manualCaptureForEveryConfiguredMethod`: con la lista por defecto, los parámetros enviados tienen `capture_method=manual` para los 12 tipos, y no para `sepa_debit` ni `bancontact`.
  - `.unknownManualMethodIsIgnored`.
  - `StripeCaptureCommandTest.expiredAuthorizationIsRejectedNotRetried`.
  - `StripeRefundCommandTest.refundOutsideWindowIsRejected`.
  - `DefaultStripePaymentAdjustmentServiceTest.rejectedRefundIsNotRetried`.
- [ ] **Step 2:** ejecutar. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS; `ant all` en verde.
- [ ] **Step 5: commit** `feat(core): manual capture for every eligible method and permanent Stripe failures`

### Task 9: Librería de Spartacus

**Files:**
- Modify: `js-storefront/stripestore/integrations-libs/stripe/root/model/stripe-payment.model.ts`, `.../core/services/stripe-payment.service.ts`, `.../components/stripe-payment-form/stripe-payment-form.component.ts`, `.../assets/translations/en/stripePayment.json`, `.../components/stripe-payment-components.module.ts`, `.../package.json` (`homepage`, `repository.url`)
- Create: `.../components/stripe-payment-instructions/stripe-payment-instructions.component.{ts,html,scss}` y su `index.ts`
- Test: `stripe-payment.service.spec.ts`, `stripe-payment-form.component.spec.ts`, `stripe-payment-instructions.component.spec.ts`

**Interfaces:**
- Consumes: campos OCC de Task 5.
- Produces:
  - `StripePaymentInfo` (TS) añade `instructionsUrl?`, `paymentReference?`, `instructionsExpireAt?`, `amountRemaining?`; `cardExpMonth?` y `cardExpYear?` pasan a `number`.
  - Tras `confirmPayment`, un PaymentIntent en `requires_action` con `next_action.type` `multibanco_display_details` o `display_bank_transfer_instructions` se trata como "pedir el pedido" (igual que `processing`), no como error. Cualquier otro `requires_action` sigue como hoy.
  - El formulario envía `payment_method_data.billing_details.email` con el email del cliente del carrito (`uid` o email del invitado). Multibanco lo exige.
  - `StripePaymentInstructionsComponent` (selector `cx-stripe-payment-instructions`): con `paymentStatus === 'PENDING'` e `instructionsUrl`, muestra referencia, importe pendiente, caducidad y un enlace "Ver instrucciones de pago" (`target="_blank"`, `rel="noopener"`). Se registra como componente CMS en los slots de confirmación (`OrderConfirmationTotalsComponent` como vecino) y detalle de pedido (`AccountOrderDetailsTotalsComponent` como vecino) mediante `provideOutlet` en `OrderOutlets` / `cx-order-details` si Spartacus 2211.37 los expone; si no, con `CmsConfig` y una entrada en los datos de ejemplo de `stripesampledata`. El constructor elige el mecanismo tras comprobarlo y lo anota en el commit.
  - Textos nuevos en `stripePayment.json`: `instructionsTitle`, `instructionsReference`, `instructionsAmount`, `instructionsExpire`, `instructionsLink`.
  - `package.json`: `homepage` y `repository.url` → `https://github.com/serpean/stripe-sap-commerce-connector`.

- [ ] **Step 1: tests que fallan**
  - `stripe-payment.service.spec.ts`: `multibanco voucher after confirm places the order`; `3DS requires_action is not placed`.
  - `stripe-payment-form.component.spec.ts`: `sends the cart email as billing email`.
  - `stripe-payment-instructions.component.spec.ts`: `shows reference, amount, expiry and link when pending`; `renders nothing when paid`; `renders nothing without instructionsUrl`.
- [ ] **Step 2:** `cd js-storefront/stripestore && npx ng test sap-commerce-stripe-payment --watch=false --browsers=ChromeHeadless`. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** tests en PASS y `npx ng build sap-commerce-stripe-payment` sin errores.
- [ ] **Step 5: commit** `feat(spartacus): payment instructions for vouchers and bank transfers`

### Task 10: Batería e2e — grupos A a D

**Files:**
- Modify: `js-storefront/stripestore/e2e/support/checkout.ts`, `e2e/support/stripe.ts`, `e2e/global-setup.ts`
- Create: `e2e/checkout/klarna.spec.ts`, `e2e/checkout/redirect-instant.spec.ts`, `e2e/checkout/sepa-pending.spec.ts`, `e2e/checkout/sepa-failed.spec.ts`, `e2e/checkout/line-items.spec.ts`

**Interfaces:**
- Consumes: helpers existentes de `support/*.ts` (`checkoutToPayment`, `expectOrderConfirmation`, `waitForOrderStatus`, `waitForPaymentEntry`, `shipOrder`, `getPaymentIntentRecord`, `retrievePaymentIntent`, `setConfig`).
- Produces (helpers):
  - `checkout.ts`: `payWithKlarna(page, outcome: 'approve'|'decline')`, `payWithRedirectMethod(page, method: string, outcome: 'authorize'|'fail')`, `payWithSepa(page, iban: string)`, `SEPA_IBANS = { succeedsAfterDelay, fails }` (IBAN de prueba de `docs.stripe.com/testing#sepa-direct-debit`).
  - `global-setup.ts` añade a `TEST_TIMINGS`: `stripe.payment.pending.timeout.sepa_debit.seconds=600`.

Casos (spec 9.1):
- [ ] **Step 1:** `klarna.spec.ts`: pedido con reserva (`requires_capture`), envío en OMS, captura por el total (`netCaptured == importe del pedido`).
- [ ] **Step 2:** `redirect-instant.spec.ts`: `bizum` si Stripe tiene simulación en test; si no, `eps`. Pedido cobrado al momento. El constructor anota qué método usó.
- [ ] **Step 3:** `sepa-pending.spec.ts`: el pedido queda en `PAYMENT_PENDING` con `paymentStatus=PENDING` en OCC; después pasa a aceptado y se puede enviar; `netCaptured == importe`. Cubre el `PAYMENT_PENDING` que el proyecto 2 no pudo probar.
- [ ] **Step 4:** `sepa-failed.spec.ts`: el pedido pasa a cancelado y su stock vuelve (stock disponible igual al de antes del pedido).
- [ ] **Step 5:** `line-items.spec.ts`: tras pagar con tarjeta, el PaymentIntent (`expand[]=amount_details.line_items`) tiene una línea por entrada y la suma cuadra con `amount`. Si hay simulación para `sequra`, se paga con `sequra` en lugar de tarjeta.
- [ ] **Step 6:** ejecutar `npm run e2e` con el servidor y el listener arrancados (ver restricciones del proyecto 2). Esperado: todos los casos nuevos y los 23 anteriores en verde.
- [ ] **Step 7: commit** `test(e2e): Klarna, instant redirect, SEPA pending and failed, line items`

### Task 11: Batería e2e — grupo E

**Files:**
- Modify: `e2e/support/checkout.ts`, `e2e/support/stripe.ts`, `e2e/support/backend.ts`, `e2e/global-setup.ts`
- Create: `e2e/checkout/multibanco-paid.spec.ts`, `e2e/checkout/multibanco-expired.spec.ts`, `e2e/checkout/bank-transfer.spec.ts`

**Interfaces:**
- Produces (helpers):
  - `checkout.ts`: `payWithMultibanco(page, email: string)`, `payWithBankTransfer(page)`, `expectPaymentInstructions(page): Promise<{reference: string}>`.
  - `stripe.ts`: `fundCashBalance(customerId: string, amountMinor: number, reference: string)` (test helper de Stripe `POST /v1/test_helpers/customers/{id}/fund_cash_balance`), `simulateMultibancoPayment(...)` o el mecanismo de test que documente Stripe para Multibanco.
  - `backend.ts`: `getStripeCustomerOf(customerUid): Promise<string|undefined>`.
  - `global-setup.ts`: `stripe.payment.pending.timeout.multibanco.seconds=120`, `stripe.payment.pending.timeout.customer_balance.seconds=180`, `stripe.cashbalance.refund.delay.seconds=10`.

- [ ] **Step 1:** `multibanco-paid.spec.ts`: el pedido existe en "pago pendiente", la confirmación muestra las instrucciones, Stripe simula el pago, el pedido pasa a aceptado y se envía.
- [ ] **Step 2:** `multibanco-expired.spec.ts`: sin pago, al vencer el plazo corto el pedido pasa a cancelado y el PaymentIntent está `canceled`. Si Stripe no permite simular la caducidad, el caso se cubre por integración y se anota como limitación.
- [ ] **Step 3:** `bank-transfer.spec.ts`, tres casos:
  - exacto: `fundCashBalance` por el importe con la referencia → pedido aceptado;
  - de menos: financiar la mitad → el pedido sigue pendiente; al vencer el plazo, cancelado; el saldo vuelve al cliente (reembolso `origin=customer_balance` en Stripe por la mitad);
  - de más: financiar importe + 5,00 € → pedido aceptado y reembolso de 5,00 € del saldo.
- [ ] **Step 4:** con un cliente en "pago pendiente" de transferencia, comprobar por backend que la cancelación parcial se deniega (`cancelOrder(orderCode, {entryNumber: 0, quantity: 1})` falla) y la completa funciona.
- [ ] **Step 5:** ejecutar `npm run e2e`. Esperado: toda la batería en verde, dos veces seguidas.
- [ ] **Step 6: commit** `test(e2e): Multibanco and bank transfer, paid, expired, under and over paid`

### Task 12: Release en GitHub

**Files:**
- Create: `LICENSE`, `scripts/release/build-release.sh`, `scripts/release/check-version.sh`, `.github/workflows/release.yml`
- Modify: `.gitignore` (añadir `dist-release/`)

**Interfaces:**
- Produces:
  - `scripts/release/check-version.sh <tag>`: falla (exit 1, mensaje claro) si `<tag>` sin la `v` no es igual a `version` de `js-storefront/stripestore/integrations-libs/stripe/package.json` o si `CHANGELOG.md` no tiene `## [<versión>]`. Imprime la sección del changelog en `dist-release/RELEASE_NOTES.md`.
  - `scripts/release/build-release.sh <version> <tgz-path>`: crea `dist-release/stripe-sap-commerce-connector-<version>.zip` con `stripe/stripecore`, `stripe/stripefacades`, `stripe/stripeocc` (desde `git ls-files`, así nunca entra nada ignorado), `storefront/<tgz>`, `README.md`, `CHANGELOG.md`, `LICENSE`, `docs/migration/`, y `SHA256SUMS` (de todo lo anterior). Después lista el zip y falla si encuentra alguna ruta prohibida de las restricciones globales o un fichero que contenga `sk_live_`, `sk_test_`, `rk_`, `whsec_` o `_auth=`.
  - `release.yml`: disparadores `push: tags: ['v*']` y `workflow_dispatch` (entrada `version`). Pasos: checkout; Node 22 con caché npm; `check-version.sh`; escribir `js-storefront/stripestore/.npmrc` desde `secrets.SAP_NPM_AUTH` (con `@spartacus:registry`, `_auth` y `always-auth=true`), sin imprimirlo; `npm ci`; `npx ng test sap-commerce-stripe-payment --watch=false --browsers=ChromeHeadless`; `npx ng build sap-commerce-stripe-payment`; `npm pack` en `dist/sap-commerce-stripe-payment`; `build-release.sh`; con tag → `gh release create <tag> --notes-file dist-release/RELEASE_NOTES.md` con el zip; con `workflow_dispatch` → solo `actions/upload-artifact`. Permisos: `contents: write`. El `.npmrc` se borra en un paso `if: always()`.
  - `LICENSE`: texto completo de Apache-2.0 y `Copyright 2026 serpean` en la sección de aplicación.

- [ ] **Step 1:** `check-version.sh v9.9.9` falla; `check-version.sh v3.0.0` funciona con el estado actual (versión 3.0.0 y sección `[3.0.0]`).
- [ ] **Step 2:** en local, construir la librería (`npx ng build sap-commerce-stripe-payment`, `npm pack`) y ejecutar `build-release.sh 3.0.0 <tgz>`. Comprobar con `unzip -l` que el zip tiene solo lo previsto, que `SHA256SUMS` verifica (`shasum -a 256 -c`) y que la comprobación de rutas prohibidas falla si se le pasa un zip con `stripesampledata` (prueba manual con un zip falso en el scratchpad).
- [ ] **Step 3:** `actionlint .github/workflows/release.yml` si está instalado; si no, revisión manual del YAML.
- [ ] **Step 4: commit** `ci: GitHub release with connector zip, Spartacus package and checksums`
- [ ] **Step 5:** pedir permiso al propietario para subir la rama y lanzar el workflow a mano (`gh workflow run release.yml --ref feat/metodos-de-pago -f version=3.0.0`). Con su permiso, comprobar que termina en verde y revisar el artefacto. No crear tags ni releases.

### Task 13: Documentación y versión 3.1.0

**Files:**
- Modify: `README.md` (Configuration, All properties, Pending payments, Operations runbook, Known limitations, sección nueva "Payment methods"), `CHANGELOG.md`, `docs/migration/standard-connector.md` (o `docs/migration/3.1.0.md` nuevo), `js-storefront/stripestore/integrations-libs/stripe/package.json` (versión `3.1.0`)

- [ ] **Step 1:** README:
  - tabla "Payment methods": cada método de la spec, sección 3, con grupo, reserva sí/no y qué activar en el Dashboard (PayPal: conectar cuenta; Bizum: NIF; SEPA: verificación; Apple Pay y Google Pay: registrar el dominio);
  - propiedades nuevas con sus defaults;
  - "Pending payments": plazos por método, `AWAITING_FUNDS`, instrucciones en Spartacus, el stock no se reserva hasta el pago (decisión del propietario: como SAP de serie) y el riesgo de agotarse;
  - runbook: devolución fuera de plazo (devolver a mano), reserva caducada, saldo de transferencia devuelto, cancelación parcial denegada mientras el pago está pendiente;
  - limitaciones conocidas: Accelerator, email con instrucciones, Apple Pay y Google Pay en local, y los casos e2e que no se pudieron simular.
- [ ] **Step 2:** `CHANGELOG.md` `## [3.1.0] - <fecha>` con Added/Changed/Fixed; guía de migración: actualización del sistema por los tipos nuevos, propiedades nuevas, `stripe.capture.manual.methods` cambia su default.
- [ ] **Step 3:** versión `3.1.0` en el `package.json` de la librería; `scripts/release/check-version.sh v3.1.0` funciona.
- [ ] **Step 4:** verificación completa: `ant all`, unit e integración de las tres extensiones, tests de la librería, `npm run e2e` dos veces seguidas en verde.
- [ ] **Step 5: commit** `docs: payment methods, deferred payments and release 3.1.0`
