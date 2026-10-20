# Proyecto 2: Conector estándar de SAP Commerce y batería e2e

Fecha: 2026-10-19
Estado: borrador para aprobación
Depende de: proyecto 1 (`docs/superpowers/specs/2026-10-18-limpieza-conector-design.md`), rama `chore/limpieza-conector`, PR #1.

## 1. Intención

El conector de Stripe tiene que usar las piezas estándar de SAP Commerce 2211 y tiene que tener una batería e2e que pruebe todos los procesos de pago. El propietario aún no tiene clientes. El conector tiene que servir como base fiable para cualquier proyecto.

**Regla número uno (decisión del propietario):** nunca puede haber un cobro en Stripe sin su pedido en SAP, ni un pedido con un importe distinto al cobrado. El proceso más importante es el checkout. Si esta regla falla, el proyecto ha fallado aunque todo lo demás funcione.

## 2. Situación actual (de la revisión del proyecto 1)

- La autorización no pasa por `PaymentService`. `StripePaymentProvider.authorize` crea las `PaymentTransactionEntry` a mano.
- El importe de un PaymentIntent reutilizado no se compara con el carrito (`StripeClient.java:64-69`).
- Cada llamada crea un PaymentIntent nuevo y no cancela el anterior (`DefaultStripePaymentFacade.java:44`).
- Los pagos asíncronos terminan con el pedido rechazado: `StripeCheckAuthorizeOrderPaymentAction` solo acepta `ACCEPTED`.
- El webhook nunca encuentra el pedido: la condición está invertida (`StripeWebhookService.java:80`) y nadie escribe `orderCode` en los metadatos. Responde 200 cuando no encuentra el pedido, así que Stripe no reintenta. No hay idempotencia y se procesa en el hilo de la petición.
- La devolución es un mock (`CaptureRefundAction`).
- `stripefulfilmentprocess` es una copia completa de la plantilla `yacceleratorfulfilmentprocess` (unas 49 clases) más 2 acciones de Stripe.
- `StripeCheckoutFacade` sobrescribe el alias `checkoutFacade`. El Accelerator usa `acceleratorCheckoutFacade`, así que no le llega.
- Los populators rellenan `sapGenericPaymentInfo`, un tipo que SAP creó para OPF.
- La clave pública no llega del backend. La app la tiene en un fichero generado.
- La versión de la API de Stripe no está fijada en el código (no hay `setStripeVersion`). `Stripe.apiKey` se fija como estático global en cada llamada.
- El conector usa hoy `TransactionStatus.REVIEW` para los pagos pendientes. En SAP, `REVIEW` también significa revisión de fraude: `CheckTransactionReviewStatusAction` crea un ticket de fraude cuando la última AUTHORIZATION está en `REVIEW`.
- Tests: casi no hay. El proyecto 1 añadió una prueba e2e de checkout con tarjeta (`js-storefront/stripestore/e2e/checkout-card.spec.ts`).

## 3. Objetivos

1. El checkout cumple la regla número uno en todos los casos de la sección 6.
2. El conector se engancha solo a los puntos de extensión estándar de SAP (framework de pagos, estrategia de autorización, beans de acciones del order process, eventos de business process). No copia procesos ni facades de SAP.
3. Captura, cancelación y devolución funcionan con OMS a través de `PaymentService`.
4. El momento del cobro es configurable y por defecto sigue a SAP de serie.
5. Los webhooks son seguros, idempotentes y no bloquean la petición.
6. La batería e2e de la sección 9 pasa contra la plataforma de referencia.
7. Toda la lógica vive en `stripecore` y `stripefacades`. Así el proyecto 5 (Accelerator) solo añade vista y controlador.

## 4. No objetivos

- Añadir métodos de pago más allá de tarjeta y Bancontact. → Proyecto 3. Pero el diseño no puede depender de que el método sea una tarjeta.
- CI, Renovate y upgrades de versiones de SAP, Spartacus y Stripe SDK. → Proyecto 4. Sí se fija aquí la versión de la API de Stripe, porque cambia el comportamiento.
- Storefront Accelerator. → Proyecto 5.
- e2e del fulfilment clásico. Se cubre con tests unitarios (sección 9.3).
- Pagos guardados para reutilizar ("guardar tarjeta"). Hoy el formulario tiene un checkbox `savePaymentMethod`. *Decisión del diseñador*: se quita del formulario hasta un proyecto futuro, porque no tiene backend probado.

## 5. Antiobjetivos

El proyecto falla, aunque los tests pasen, si:

- Puede quedar dinero cobrado en Stripe sin pedido en SAP y sin devolución.
- Un pedido puede tener un importe distinto al cobrado.
- Aparecen al cliente pedidos que no son pedidos de verdad, es decir, pedidos de pagos que el cliente no llegó a hacer (por ejemplo, una redirección abandonada). **Decisión del propietario.** Un pedido con un pago "en proceso" sí es un pedido de verdad, porque el cliente ya ha hecho su parte (decisión del propietario, sección 7.3). Si el banco lo rechaza, el cliente lo ve como "cancelado: pago rechazado".
- Un webhook repetido o simultáneo crea dos pedidos, dos capturas o dos devoluciones.
- El conector copia código de SAP en lugar de engancharse a un punto de extensión.

## 6. Checkout (decisiones del propietario: opción B con protecciones)

El pedido solo se crea cuando Stripe ha aceptado el pago, o cuando lo está procesando. El carrito sigue siendo carrito hasta entonces. No se crean pedidos antes del pago.

### 6.1 Flujo normal

1. **Paso de pago:** el servidor crea o reutiliza **un solo PaymentIntent por carrito**. Los metadatos llevan como mínimo: código del carrito, uid del usuario, base site, moneda. Si el carrito cambia (importe o moneda), el PaymentIntent existente se **actualiza**, no se crea otro. Si ya no se puede actualizar (por ejemplo, porque ya está confirmado), se cancela y se crea otro.
2. **Confirmación en el navegador:** con el Payment Element. Con tarjeta puede haber 3DS. Con Bancontact (y en el futuro PayPal, Klarna...) hay redirección a la página del proveedor y vuelta a la tienda (`return_url`).
3. **Vuelta y creación del pedido:** el servidor **consulta el PaymentIntent en Stripe**. El estado que manda nunca viene del navegador.
   - `requires_capture` o `succeeded`: se comprueba el importe (6.3). Si cuadra, se crea el pedido con la autorización aceptada.
   - `processing`: se comprueba el importe y se crea el pedido con la autorización **pendiente**. El order process espera (sección 7.3).
   - `requires_payment_method`, `canceled` o cualquier otro estado: no hay pedido. El cliente vuelve al paso de pago con un aviso. El carrito sigue intacto.
4. **Camino estándar:** la creación del pedido usa el flujo de serie de SAP: `CheckoutFacade.authorizePayment` → `CommerceCheckoutService.authorizePayment` → estrategia de autorización (sección 7.1) → `PaymentService.authorize` → `placeOrder`.

### 6.2 Red de seguridad por webhook (el cliente no vuelve)

- Con el evento `payment_intent.succeeded`, `payment_intent.amount_capturable_updated` o `payment_intent.processing`: si no existe pedido para ese PaymentIntent, el conector espera un tiempo de gracia (*decisión del diseñador*: configurable, por defecto 5 minutos, para dar tiempo a la vuelta normal del cliente). Si después sigue sin pedido, lo crea desde el carrito de los metadatos, en el servidor, con el mismo camino estándar de 6.1.4. Antes reconstruye el contexto: base site, usuario, moneda e idioma, dentro de una vista local de sesión.
- Si el carrito ya no existe, o el importe no cuadra (6.3): no se crea el pedido y el conector **devuelve el dinero automáticamente** (refund, o cancelación del PaymentIntent si aún no está capturado). Lo deja registrado en el log con nivel WARN y con el id del PaymentIntent.

### 6.3 Comprobación de importe

- Antes de crear un pedido, el importe del PaymentIntent (en la unidad mínima de la moneda) y su moneda tienen que coincidir exactamente con el total del carrito. Tiene que haber una única función de conversión a la unidad mínima. Hoy hay tres (`StripeClient.java:157`, `DefaultStripeRequestFactory.java:24`, `CurrencyUtils`), y tienen que quedar en una.
- **Prevención (decisión del propietario):** justo antes de que el navegador confirme el pago, el servidor sincroniza el importe y la moneda del PaymentIntent con el carrito. La librería de Spartacus llama a ese endpoint y solo confirma si la sincronización termina bien.
- **Si aun así no coincide** después de la confirmación (solo puede pasar si el carrito cambia durante el pago, por ejemplo desde otra pestaña): Stripe no permite cambiar el importe de un PaymentIntent confirmado. El conector devuelve o libera el dinero automáticamente (refund si está cobrado, cancelación si está reservado), crea un PaymentIntent nuevo y pide al cliente que pague otra vez con un aviso. El cliente puede ver dos reservas en su tarjeta durante unos días; se acepta. Nunca se crea un pedido con un importe distinto.
- En el webhook (6.2), un importe que no coincide siempre lleva a devolver o liberar el dinero.

### 6.4 Sin duplicados

- El id del PaymentIntent tiene un **índice único en la base de datos**, en un **tipo propio** del conector, no en `AbstractOrder` ni en `PaymentInfo`. Motivo (análisis de SAP 2211.55): cada cancelación y devolución clona el pedido para el historial (`DefaultOrderHistoryService.createHistorySnapshot`), y el clon violaría un índice único en el pedido. Si la vuelta del cliente y el webhook intentan crear el pedido a la vez, solo uno lo consigue, incluso con varios nodos en CCv2. El otro detecta el pedido existente y no hace nada.

### 6.5 Un PaymentIntent huérfano

- Un PaymentIntent queda huérfano si el carrito se abandona antes de pagar. *Decisión del diseñador*: no hace falta limpieza activa en este proyecto, porque un PaymentIntent sin confirmar no cobra nada. Si un PaymentIntent huérfano llega a cobrar, se aplica 6.2.

## 7. Integración con SAP Commerce

### 7.1 Autorización

- Se sobrescribe el alias `commercePaymentAuthorizationStrategy` (`commerceservices-spring.xml:564`) con una estrategia de Stripe. Así llega a OCC (`checkoutFacade`) y al Accelerator (`acceleratorCheckoutFacade`).
- **Hecho comprobado:** el facade (`DefaultCheckoutFacade.java:528-529`) hace un cast a `CreditCardPaymentInfoModel` antes de llegar a la estrategia, y el validador de OCC `PlaceOrderCartValidator` (`:41`) rechaza el carrito si no hay datos de tarjeta. Por eso hacen falta un bean hijo de cada facade (`checkoutFacade` y `acceleratorCheckoutFacade`) que solo sobrescribe `authorizePayment` para pagos de Stripe, y un validador de OCC que acepta `StripePaymentInfo`. La lógica vive en un servicio compartido; los facades solo delegan.
- La estrategia llama a `PaymentService.authorize`, y el framework crea la entrada AUTHORIZATION. El comando de Stripe solo **consulta** el PaymentIntent ya confirmado en el navegador y devuelve el resultado. No crea entradas a mano.
- `AuthorizationCommand` frente a `SubscriptionAuthorizationCommand`: el constructor elige el que encaje mejor. La regla es que el id del PaymentIntent tiene que viajar en la petición y quedar en `requestId` de la entrada. Nota del análisis: `SubscriptionAuthorizationCommand` usa `subscriptionId`, lo que es un uso forzado del nombre.

### 7.2 Modelo de datos del pago (propuesta del diseñador, aceptada por el propietario en el resumen)

- `StripePaymentInfo` hereda del `PaymentInfo` genérico, **no** de `CreditCardPaymentInfo`. Guarda: el tipo de método de Stripe (`card`, `bancontact`, `klarna`...), el id del PaymentIntent, el id del PaymentMethod y los datos para mostrar (marca, últimos 4 dígitos y caducidad si es tarjeta).
- **El constructor tiene que comprobar primero** los puntos de SAP que solo aceptan `CreditCardPaymentInfoModel`:
  - `DefaultCheckoutFacade.authorizePayment` (`commercefacades/.../DefaultCheckoutFacade.java:525-544`, cast en la línea 529)
  - `DefaultCommercePaymentAuthorizationStrategy` (línea 55), que se sustituye por 7.1
  - `TakePaymentAction` del fulfilment clásico (líneas 36-38), que solo captura si es tarjeta
  - `AbstractOrderPopulator.addPaymentInformation` (líneas 484-493)
- Para cada uno, la solución tiene que ser el punto de extensión más pequeño: un bean de acción, una estrategia o un populator añadido. Nunca una copia del facade. Si el cast de la línea 529 obliga a sobrescribir `authorizePayment` en el facade, se hace con un bean hijo para **los dos** facades (`checkoutFacade` y `acceleratorCheckoutFacade`) y se justifica en el plan.
- OCC expone los datos de `StripePaymentInfo` para la confirmación y el historial de pedidos. Un DTO o campo propio está bien, pero no `sapGenericPaymentInfo`. Spartacus los muestra en la confirmación y en el detalle de pedido.
- Se eliminan los populators de `sapGenericPaymentInfo` y cualquier uso de `SAPGenericPaymentInfo`.

### 7.3 Order process y pagos pendientes

- **No se usa `REVIEW` para los pagos pendientes.** En SAP, `REVIEW` dispara la revisión de fraude. Un pago pendiente **no tiene entrada AUTHORIZATION**; el estado "en proceso" vive en el registro propio del PaymentIntent (6.4). La entrada AUTHORIZATION aceptada se crea cuando Stripe confirma el pago.
- **Mecanismo de espera (decisión del diseñador tras el análisis de SAP):** añadir un nodo al proceso de OMS exige copiar el proceso completo en un `DynamicProcessDefinition`, que es justo lo que el diseño quiere evitar. En su lugar, el conector envuelve la primera acción del proceso (bean `checkOrderAction`, que existe en OMS y en el clásico) con un `BeanPostProcessor`. Para un pedido de Stripe sin autorización aceptada, el envoltorio lanza `RetryLaterException` y el proceso se reintenta al cabo de un tiempo configurable. Así el plazo es una propiedad, el mismo código sirve para OMS y para el clásico, y no depende del orden de carga de las extensiones.
- El webhook, cuando el pago pendiente termina, añade la entrada correspondiente y dispara el evento con `BusinessProcessService.triggerEvent`.
- Si el pago pendiente falla, o caduca el plazo (*decisión del diseñador*: configurable, por defecto 24 horas), el pedido se cancela con el camino estándar de cancelación.
- **Pedido con pago "en proceso" (decisión del propietario):** se crea como pedido de verdad con estado de pago pendiente. El cliente lo ve como "pago pendiente" en la confirmación y en su cuenta. Si el banco lo rechaza, pasa a "cancelado: pago rechazado".
- **OMS no tiene paso de comprobación de autorización** (comprobado por el revisor del diseño). El order process de OMS (`projectdata-dynamic-business-process-order.impex`, DynamicProcessDefinition) ejecuta `checkOrder`, después `fraudCheck`, después el sourcing. El conector añade un paso de espera del pago al proceso de OMS, y al clásico si hace falta. El pedido no puede pasar al sourcing ni a la captura hasta que el pago está aceptado.
- `stripefulfilmentprocess` deja de ser una copia de la plantilla. Queda solo lo que el conector necesita: beans de acción que sustituyen a los de SAP por id, y la definición del proceso solo si es imprescindible. *Decisión del diseñador*: si al final cabe en `stripecore`, la extensión desaparece y se anota en la guía de migración.

### 7.4 Momento del cobro (decisión del propietario)

- **Por defecto, como SAP de serie:** se autoriza al crear el pedido (`capture_method=manual`) y se captura cuando el order process llega al paso de cobro (`TakePaymentAction` en el clásico; captura de OMS al enviar).
- **Alternativa: cobro inmediato** (`capture_method=automatic`). La captura de SAP llega igualmente, y el comando de captura de Stripe comprueba que el PaymentIntent ya está cobrado y registra la entrada CAPTURE como aceptada, sin cobrar dos veces.
- Se elige con la propiedad `stripe.capture.mode` (`manual` | `automatic`). Los métodos que no admiten reserva se cobran siempre al momento, aunque el modo sea `manual`. La lista de métodos que admiten reserva sigue en `stripe.capture.manual.methods`.
- Una reserva de tarjeta caduca en Stripe a los 7 días. El README tiene que advertirlo. Detectar y gestionar autorizaciones caducadas queda fuera de este proyecto.

### 7.5 Captura, cancelación y devolución

- Comandos del framework de pagos registrados para el proveedor `Stripe`: captura, captura parcial, void, devolución sobre un pago (follow-on refund). Ya existen capture, void y follow-on refund (`stripecore-commands-spring.xml:32-57`). Se añade la captura parcial y se revisa el mapeo de estados de cada uno. Hoy hay cuatro mapeos que no coinciden; tienen que quedar en uno.
- **Cancelación (hecho comprobado):** OMS nunca llama a `PaymentService.cancel`. La cancelación llega al pago solo por el alias `orderCancelPaymentServiceStrategy` (`basecommerce-spring.xml:537`) y, después del sourcing, por el bean `OmsOrderCancelPaymentServiceAdapter` (`warehousing-spring.xml:728-730`), que hoy solo escribe en el log. El conector sobrescribe la estrategia y envuelve ese adaptador. La estrategia ajusta el pago al nuevo total del pedido: void si no queda nada por cobrar y no hay captura; reembolso de la diferencia si ya hay captura. En el clásico, `CancelWholeOrderAuthorizationAction` sí llama a `PaymentService.cancel`, que llega a `VoidCommand`.
- **Captura con cancelación parcial previa:** la captura cobra el total actual del pedido, no el importe autorizado. Así nunca se cobra más que el pedido.
- **Cancelación con el dinero ya cobrado (decisión del propietario):** en modo `automatic`, y con métodos que no admiten reserva (Bancontact), el PaymentIntent ya está `succeeded` al crear el pedido, y Stripe no permite anularlo. En ese caso la cancelación hace un **reembolso completo** y lo registra como entrada de devolución en la transacción del pedido. La cancelación nunca deja el dinero cobrado sin pedido activo.
- **Devolución:** con OMS, `CaptureRefundAction` de `yacceleratorordermanagement` llama a `paymentService.refundFollowOn` y llega a Stripe. El conector no copia esa acción.
- **Fulfilment clásico:** la acción de devolución de SAP es un mock. El conector no la sustituye. El README explica que el cliente tiene que cambiarla por una que llame a `refundFollowOn`.
- Todas las llamadas a Stripe que crean o cambian dinero llevan una clave de idempotencia **estable**: por ejemplo, el código de la entrada de transacción. Hoy llevan un UUID aleatorio (`StripeClient.java:221-227`), que no protege nada.

### 7.6 Webhooks

- **Endpoint:** el actual `POST /{baseSiteId}/payments/stripe/webhook` de `stripeocc`. La firma se comprueba con `Webhook.constructEvent`, y una firma inválida devuelve 400.
- **Respuesta rápida:** el endpoint guarda el evento (id, tipo, carga) y responde 200 enseguida. El procesamiento va aparte, en una tarea o un business process de SAP (*decisión del diseñador*: `TaskService` o un business process; elige el constructor).
- **Idempotencia:** un evento con un id ya recibido no se procesa otra vez. El id del evento lleva índice único.
- **Errores:** si el procesamiento falla por un error temporal, se reintenta. Si el evento no corresponde a nada de SAP (por ejemplo, otro sistema que usa la misma cuenta de Stripe), se ignora y se registra.
- **Búsqueda del pedido** por el id del PaymentIntent (6.4), no por los metadatos de cliente. Se corrige la condición invertida.
- **Eventos mínimos:** `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.processing`, `payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded` y `refund.updated`. El constructor puede añadir más si el diseño los necesita.
- El `client_secret` del PaymentIntent no se guarda en la base de datos. Hoy se guarda en `requestToken` (`StripeWebhookService.java:113-115`).

### 7.7 Otras piezas

- **Clave pública:** un endpoint OCC la entrega por base site (`GET /{baseSiteId}/payments/stripe/config`, *decisión del diseñador* sobre el nombre). La librería de Spartacus la lee de ahí. La configuración en el front (`StripePaymentConfig.publishableKey`) queda como alternativa opcional. El fichero generado del proyecto de referencia (`write-stripe-config.mjs`) desaparece si deja de hacer falta.
- **Versión de la API de Stripe:** se fija en el código. *Decisión del diseñador*: la versión por defecto del SDK `stripe-java` 30.0.0, escrita explícitamente como constante. Hay que dejar de usar el `Stripe.apiKey` estático global y pasar `RequestOptions` o un `StripeClient` por llamada.
- **Configuración:** se lee en cada uso, no se guarda en caché hasta reiniciar (`StripeClient.java:146-151`). Las propiedades se pueden sobrescribir por base site (*decisión del diseñador*: `stripe.<propiedad>.<baseSiteUid>` con caída a `stripe.<propiedad>`).
- **Errores hacia el cliente:** el mensaje de error de Stripe no se devuelve tal cual por OCC (`StripePaymentController.java:49`). Se devuelve un código de error estable y un texto genérico, y el detalle va al log.
- **Datos sensibles en el log:** nunca se escriben claves, `client_secret` ni datos de tarjeta.

## 8. Proyecto de referencia

- OMS es la configuración de referencia (**decisión del propietario**). La receta `b2c_occ_stripe` cambia a las extensiones de OMS (`yacceleratorordermanagement`, warehousing, ordermanagement y las que pidan). El constructor toma la lista de la receta `cx` de SAP 2211.55.
- La tienda `electronics` añade la moneda **EUR** (**decisión del propietario**) para probar métodos locales. La cuenta de Stripe de pruebas está en España (EUR). Tiene activos tarjeta, Klarna, Bancontact, EPS, BLIK y Amazon Pay, entre otros; no tiene PayPal ni iDEAL. El cambio va en `stripesampledata`.
- Los webhooks llegan a `localhost` con la **Stripe CLI** (`stripe listen --forward-to https://localhost:9002/occ/v2/electronics-spa/payments/stripe/webhook`; **decisión del propietario**). La CLI está instalada (`/opt/homebrew/bin/stripe`, 1.34.0). El secreto que imprime `stripe listen` se usa como `STRIPE_WEBHOOK_SECRET`.

## 9. Batería de pruebas

### 9.1 e2e (Playwright, en el proyecto de referencia)

Cada prueba comprueba la interfaz **y** el estado en el backend (pedido, entradas de transacción y su estado) por OCC o HAC, y el estado del PaymentIntent en Stripe por su API.

*Checkout:*
1. Tarjeta sin 3DS (`4242 4242 4242 4242`): pedido creado, AUTHORIZATION aceptada.
2. Tarjeta con 3DS superado (`4000 0025 0000 3155`): pedido creado.
3. Tarjeta con 3DS fallido: no hay pedido; aviso en el paso de pago; carrito intacto.
4. Tarjeta rechazada (`4000 0000 0000 0002`): no hay pedido.
5. El carrito cambia después de crear el PaymentIntent y antes de pagar: el importe se sincroniza antes de confirmar, y el importe del pedido coincide con el cobrado. Variante: el carrito cambia durante el pago; el dinero se devuelve o se libera y el cliente tiene que pagar otra vez.
6. Bancontact en EUR con vuelta del cliente: pedido creado.
7. Bancontact, el cliente paga y no vuelve (se cierra la página): el webhook crea el pedido después del tiempo de gracia.
8. Bancontact cancelado en la página del proveedor: no hay pedido; carrito intacto.
9. La vuelta del cliente y el webhook llegan a la vez: un solo pedido.
10. El importe no cuadra cuando llega el webhook: no hay pedido; el dinero se devuelve.

*Después de la compra (OMS):*
11. Modo manual: la captura se hace al enviar el pedido; CAPTURE aceptada; PaymentIntent `succeeded`.
12. Modo automático: cobro al crear el pedido; la captura de SAP solo registra; Stripe no cobra dos veces.
13. Cancelación antes de la captura: void; PaymentIntent `canceled`.
14. Devolución: refund en Stripe; entrada REFUND_FOLLOW_ON aceptada.
17. Cancelación con el dinero ya cobrado (modo automático o Bancontact): reembolso completo en Stripe; entrada de devolución aceptada; pedido cancelado.

*Webhooks:*
15. Firma inválida: 400; nada cambia.
16. Evento duplicado: se procesa una vez.

Notas:
- El tiempo de gracia de 6.2 y los plazos de 7.3 se pueden reducir por configuración en las pruebas.
- Los casos 9 y 10 pueden forzar las condiciones de carrera o el cambio de carrito por OCC o con eventos de la Stripe CLI (`stripe trigger`, `stripe events resend`). El constructor elige.
- La prueba existente (`checkout-card.spec.ts`) se integra como caso 1.
- Mejoras pendientes del proyecto 1 que se aplican aquí: esperar el banner de cookies con un manejador, cerrar los contextos de petición, y no depender del stock de un único producto.

### 9.2 Tests unitarios y de integración (JUnit, con TDD)

- Cada comando, la estrategia de autorización, la comprobación de importe, la conversión a unidad mínima, el mapeo de estados, el procesamiento de webhooks y la idempotencia tienen tests `@UnitTest` o `@IntegrationTest`.
- El cliente de Stripe se aísla detrás de una interfaz del conector para poder probar sin red.
- El `manifest.json` de CCv2 tiene que poder ejecutar estos tests: los paquetes `com.acme.*` de hoy se cambian a los del conector (`com.stripe.*`).

### 9.3 Fulfilment clásico

- Sin e2e. Tests unitarios que prueban que `TakePaymentAction` (o el enganche que haga falta) captura un pedido con `StripePaymentInfo`.

## 10. Compatibilidad

Se puede romper la compatibilidad (decisión del propietario en el proyecto 1). Los cambios incompatibles se añaden a la guía de migración (`docs/migration/`) y al `CHANGELOG.md` como versión 3.0.0. Ejemplos: el tipo de `StripePaymentInfo`, la desaparición de `StripeCheckoutFacade` y de `stripefulfilmentprocess`, y los endpoints OCC.

## 11. Lo que queda al constructor

- La forma exacta de cada punto de extensión, dentro de las reglas de las secciones 7.1 a 7.3.
- El atributo y la tabla del índice único (6.4).
- `TaskService` o business process para los webhooks (7.6).
- El estado de `TransactionStatus` para pagos pendientes, con justificación (7.3).
- Cómo forzar en las pruebas las carreras y los cambios de carrito (9.1).
- La lista de extensiones de OMS en la receta (8).
- El orden de las tareas. Recomendación: primero el modelo de datos y la autorización, después el checkout con sus protecciones, después el webhook, y al final la parte de después de la compra.
