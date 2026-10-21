# Proyecto 3: Métodos de pago de Stripe y release en GitHub

Fecha: 2026-10-21
Estado: borrador para aprobación
Depende de: proyecto 2 (`docs/superpowers/specs/2026-10-19-conector-estandar-design.md`), fusionado en `main` con la PR #2 (`70b18a2`).

## 1. Intención

El conector tiene que aceptar **todos los métodos de pago que Stripe ofrece a una cuenta de España**, y cada método tiene que cumplir la regla número uno en SAP. El propietario no tiene datos de clientes sobre qué métodos piden. Por eso el objetivo es el máximo posible, no una lista elegida.

Además, el repositorio tiene que crear una **release en GitHub** con los descargables del conector (decisión del propietario: forma parte de este proyecto).

**Regla número uno (del proyecto 2, sigue en vigor):** nunca puede haber un cobro en Stripe sin su pedido en SAP (o una devolución), ni un pedido con un importe distinto del cobrado. Ningún pedido se envía sin que el dinero haya llegado o esté reservado.

## 2. Situación actual

- El PaymentIntent se crea con `automatic_payment_methods` (`DefaultStripePaymentIntentService.buildCreateParams`). Cualquier método activo en el Dashboard de Stripe ya aparece en el Payment Element.
- La captura manual se pide por método con `payment_method_options.<método>.capture_method=manual`, solo para los métodos de `stripe.capture.manual.methods` (hoy `card,paypal,klarna`). El código también conoce `affirm` y `afterpay_clearpay`, que no existen para cuentas de España.
- El pedido se crea con los estados `AUTHORIZED`, `CAPTURED` y `PROCESSING` del PaymentIntent. `REQUIRES_ACTION` no crea pedido.
- Un solo plazo para los pagos pendientes: `stripe.payment.pending.timeout.seconds=86400` (24 horas).
- No se envían líneas de pedido a Stripe.
- El conector no crea clientes de Stripe (`Customer`). El proyecto 2 quitó la opción de guardar la tarjeta.
- No hay ningún workflow de GitHub Actions (`.github/` no existe).
- La librería de Spartacus (`js-storefront/stripestore/integrations-libs/stripe`, paquete `sap-commerce-stripe-payment` 3.0.0) apunta en `homepage` y `repository` a `github.com/SAP-samples/sap-commerce-stripe-payment`, que no es este repositorio.

### 2.1 Cuenta de pruebas (comprobado el 2026-10-21)

La cuenta es de España (`country=ES`, `default_currency=eur`) y usa la configuración de métodos "Default". Con permiso del propietario, se activaron por API: `sepa_debit`, `multibanco`, `customer_balance`, `paypal`, `sequra`, `bizum` y `google_pay`. Todos quedaron con `available=true`. El estado anterior está guardado fuera del repositorio.

Ya estaban activos: `card`, `apple_pay`, `link`, `samsung_pay`, `amazon_pay`, `bancontact`, `eps`, `blik`, `mb_way`, `satispay`, `klarna`, `kakao_pay`, `naver_pay`, `payco` y `pix`.

## 3. Métodos disponibles para una cuenta de España

Fuente: documentación de Stripe (`docs.stripe.com/payments/payment-methods/payment-method-support` y la página de cada método), consultada el 2026-10-21. Cuando la tabla general y la página del método no coinciden, manda la página del método.

El conector trata los métodos por **grupo de comportamiento**, no uno a uno:

| Grupo | Métodos | Cuándo se confirma | Reserva (captura manual) |
|---|---|---|---|
| A. Tarjeta y carteras | `card` (y Apple Pay, Google Pay, Samsung Pay), `link`, `paypal`, `amazon_pay`, `revolut_pay`, `mobilepay`, `satispay`, `alipay`, `wechat_pay` | al momento (3DS puede pedir una acción) | sí: tarjeta, carteras de tarjeta, Link, PayPal, Amazon Pay, Revolut Pay, MobilePay, Satispay. No: Alipay, WeChat Pay |
| B. Redirección y pago en tiempo real | `bancontact`, `eps`, `ideal`, `wero`, `p24`, `blik`, `twint`, `swish`, `mb_way`, `bizum` | al momento, tras la redirección o la app | no (Wero: la documentación dice que sí; el constructor lo comprueba) |
| C. Compra ahora y paga después | `klarna`, `sequra`, `scalapay`, `billie`, `mondu` | al momento, tras la aprobación | sí. Klarna: 28 días |
| D. Adeudo bancario | `sepa_debit` | "en proceso" unos 6 días hábiles | no |
| E. El cliente paga más tarde | `multibanco`, `customer_balance` (transferencia bancaria UE) | días después, o nunca | no |

**Datos clave por método** (para el constructor; la documentación de Stripe manda):

- Bizum: solo clientes de España, EUR, de 0,50 € a 5.000 €. En modo live, la capacidad queda pendiente hasta que la cuenta tiene el NIF.
- seQura: solo España, EUR, de 29 € a 3.000 €, captura manual. Pide las líneas del pedido.
- Klarna España: pagar ahora hasta 10.000 €, pagar después hasta 500 €, en 3 plazos de 1 € a 3.000 €. Captura en 28 días.
- PayPal: reserva de 10 días (más 10 automáticos si el saldo se liquida en Stripe). En live hay que conectar una cuenta de PayPal en el Dashboard.
- Tarjeta: reserva de 7 días.
- SEPA: fondos en T+6 días hábiles. Disputa sin motivo durante 8 semanas. Máximo 10.000 € por pago.
- Multibanco: cupón de 7 días. Después, el PaymentIntent puede quedar "en proceso" hasta 4 días más por retrasos del banco, y luego falla (`payment_intent.payment_failed`). Pide `billing_details.email`.
- Transferencia bancaria (`customer_balance` con `eu_bank_transfer`): pide un `Customer` de Stripe en el PaymentIntent. El cliente puede pagar de menos (el PaymentIntent queda financiado en parte) o de más (lo que sobra queda en el saldo del cliente en Stripe).
- Plazos de devolución: de 90 a 730 días según el método.

**No disponibles para cuentas de España** (no entran en el proyecto): Affirm, Afterpay/Clearpay, Zip, Cash App Pay, Boleto, OXXO, Konbini, Alma, Pay by Bank (Reino Unido), Bacs, BECS y ACH. Pix es solo para clientes de Brasil en BRL.

## 4. Objetivos

1. Cada grupo de la sección 3 cumple la regla número uno, con OMS como referencia.
2. Los grupos D y E funcionan de principio a fin: pedido en "pago pendiente", pago que llega más tarde o nunca, y cancelación o devolución.
3. El conector envía las líneas del pedido a Stripe con la misma regla de importe del proyecto 2.
4. Plazos de "pago pendiente" por método.
5. La lista de métodos con reserva cubre todos los que la admiten en una cuenta de España.
6. La batería e2e cubre un caso por grupo, con métodos que Stripe permite simular en modo test.
7. Una GitHub Action crea la release con el zip de descargables al subir un tag.
8. El README tiene una tabla de métodos: grupo, reserva y qué hay que activar en el Dashboard de Stripe.

## 5. No objetivos

- Accelerator (JSP). Es el proyecto 5. Toda la lógica nueva vive en `stripecore` y `stripefacades`, así que el proyecto 5 solo añade la vista.
- El email de confirmación con las instrucciones de pago. Las plantillas de email son de cada tienda.
- Publicar la librería en npm (decisión del propietario: de momento solo GitHub).
- CI en cada PR, Renovate y el manifiesto de CCv2. Son el proyecto 4.
- Apple Pay y Google Pay en local: necesitan un dominio real registrado en Stripe. Solo se documenta el registro del dominio.
- Métodos de otros países sin uso en una tienda en EUR (Kakao Pay, Naver Pay, Payco, Pix): no se tocan ni se prueban.
- Guardar métodos de pago para próximos pedidos.
- Disputas (chargebacks) y conciliación periódica completa.
- Reglas propias de B2B para Billie y Mondu. Se incluyen en la lista de reserva, sin pruebas e2e.

## 6. Antiobjetivos

El proyecto ha fallado, aunque todo lo demás funcione, si:

- un pedido pasa al sourcing o se envía sin que el dinero haya llegado o esté reservado;
- un cliente paga y no tiene pedido, ni devolución;
- un pedido de transferencia sigue adelante por un importe distinto del cobrado;
- las líneas del pedido cambian el importe que se cobra, o hacen fallar pagos que hoy funcionan;
- el zip de la release contiene contenido de SAP o secretos;
- se publica una release con la librería sin compilar o con tests fallidos.

## 7. Diseño del conector

### 7.1 Grupo E: el cliente paga más tarde (decisiones del propietario)

- **Cuándo se crea el pedido.** El pedido se crea cuando el PaymentIntent queda en `requires_action` con instrucciones de pago para más tarde: `next_action.type` igual a `multibanco_display_details` o `display_bank_transfer_instructions`. Se crea con el estado "pago pendiente" del proyecto 2. Es un pedido real: el cliente lo ve en su historial.
- **Stock** (*decisión del propietario: como SAP de serie*). Con OMS, el stock se reserva en el sourcing, y el sourcing espera a que llegue el dinero. Mientras el pedido está en "pago pendiente", no reserva stock, igual que SEPA en el proyecto 2. Si al llegar el pago no hay stock, se aplica el comportamiento estándar de OMS, y si el pedido se cancela, el conector devuelve el dinero. El README documenta este riesgo. Cualquier otro `requires_action` (3DS, redirección) sigue sin crear pedido.
- **Red de seguridad por webhook.** Si el cliente cierra el navegador después de recibir el cupón o las instrucciones, el webhook crea el pedido, igual que en el proyecto 2. El constructor elige el evento (por ejemplo `payment_intent.requires_action`) y lo añade al listener de la Stripe CLI y a la documentación.
- **Qué ve el cliente** (*decisión del diseñador, aceptada por el propietario*). En la página de confirmación y en el detalle del pedido: la referencia, el importe, la fecha de caducidad y el enlace a la página de instrucciones que aloja Stripe (`hosted_voucher_url` o `hosted_instructions_url`). Se guardan en `StripePaymentInfo` y se exponen por OCC. La pantalla no muestra el IBAN en el propio storefront: el enlace de Stripe ya lo muestra.
- **Cuándo se cancela.** Multibanco: si el pago no llega en el plazo del grupo (7 días del cupón más 4 de retraso del banco). Transferencia: tras un plazo configurable, por defecto 14 días (*decisión del diseñador, aceptada por el propietario*). Al cancelar, el conector cancela el PaymentIntent, el pedido pasa a "cancelado: pago rechazado" y el stock se libera.
- **Multibanco "en proceso".** Para Multibanco, `processing` significa "cupón caducado, puede llegar un pago tardío". No es un pago en camino como en SEPA. El pedido ya existe y sigue en "pago pendiente" hasta que llega el pago o falla.
- **Transferencia con pago de menos** (*decisión del diseñador, aceptada por el propietario*). El pedido sigue en "pago pendiente" hasta el plazo. Si al final el pago no está completo, el pedido se cancela y se devuelve lo que pagó el cliente.
- **Transferencia con pago de más** (*decisión del diseñador, aceptada por el propietario*). El pedido sigue adelante y el conector devuelve la diferencia, que Stripe deja en el saldo del cliente.
- **Captura.** Estos métodos no admiten reserva. Cuando el pago llega, el pedido ya está cobrado y continúa el proceso de OMS, igual que Bancontact en el proyecto 2.
- **Cliente de Stripe** (*decisión del diseñador, aceptada por el propietario*). La transferencia necesita un `Customer` de Stripe en el PaymentIntent. Para un cliente registrado, el conector crea el `Customer` la primera vez y lo reutiliza. Para un invitado, crea uno nuevo por carrito. El momento exacto de crearlo lo decide el constructor; sin `Customer`, Stripe no muestra la transferencia.
- **Dónde se guarda el id del `Customer`** (*decisión del diseñador, tras la revisión*). En un tipo propio que relaciona el cliente de SAP con la cuenta de Stripe (id de cuenta y modo test o live), no en un atributo simple de `CustomerModel`. Así funciona con claves distintas por sitio y entre test y live sin otra actualización del sistema. Si Stripe responde que el `Customer` no existe, el conector crea uno nuevo y sustituye el id.
- **Dinero de transferencia que no corresponde a ningún pedido abierto** (*decisión del diseñador, tras la revisión*). Por la regla número uno, nunca se queda dinero sin pedido:
  - el conector usa la conciliación automática de Stripe, que asigna cada transferencia a su PaymentIntent por la referencia. Un cliente registrado puede tener varios pedidos por transferencia abiertos;
  - el conector escucha `cash_balance.funds_available`. Si queda saldo del cliente que no se aplica a ningún PaymentIntent abierto (pago tardío tras la cancelación, referencia equivocada o pago de más), lo devuelve al cliente;
  - el constructor comprueba en la documentación de Stripe cómo se devuelve un saldo de transferencia (Stripe puede pedir al cliente sus datos bancarios por email) y cuánto esperar antes de devolverlo, para no devolver dinero que Stripe aún va a conciliar.
- **Cambios en un pedido en "pago pendiente"** (*decisión del diseñador, tras la revisión*). Grupos D y E: mientras el pedido espera el dinero, el conector rechaza la cancelación parcial con un error claro para atención al cliente. El importe del cupón, de la transferencia o del adeudo ya está fijado. La cancelación completa sí se permite: cancela el PaymentIntent y, si llega dinero después, se devuelve por la regla anterior.

### 7.2 Grupo D: SEPA Direct Debit

- El pedido se crea en `processing`, como ya hace el proyecto 2. Pasa a "pago pendiente" y espera con el mecanismo actual (`RetryLaterException` en `checkOrderAction`).
- El plazo de espera por defecto para SEPA es de 10 días naturales (6 días hábiles más margen). *Decisión del diseñador.*
- El mandato lo muestra y lo acepta el Payment Element. El conector guarda el id del mandato en `StripePaymentInfo` si Stripe lo da (`charge.payment_method_details.sepa_debit.mandate`).

### 7.3 Plazos de "pago pendiente" por método

- Nueva propiedad por tipo de método: `stripe.payment.pending.timeout.<tipo>.seconds`. Si no existe, se usa `stripe.payment.pending.timeout.seconds` (24 horas). La sustitución por sitio del proyecto 2 (`stripe.<nombre>.<baseSiteUid>`) sigue funcionando.
- Valores por defecto (*decisión del diseñador*): `sepa_debit` 10 días, `multibanco` 12 días, `customer_balance` 14 días.
- El tiempo entre reintentos sigue en `stripe.payment.pending.retry.seconds`. El constructor comprueba que un reintento cada 5 minutos durante 14 días no carga el servidor; si la carga es alta, propone un reintento más largo para estos métodos.

### 7.4 Líneas del pedido

- El conector envía `amount_details.line_items` (nombre del producto, precio unitario, cantidad, impuesto y descuento) y los campos de envío, impuesto y descuento del pedido. Se envían al crear el PaymentIntent y en cada sincronización de importe antes de confirmar.
- **Las líneas nunca cambian el importe.** El importe sigue siendo la regla R6 del proyecto 2. Las líneas tienen que sumar exactamente ese importe, porque Stripe comprueba la suma y rechaza el PaymentIntent si no cuadra. Los descuentos del pedido y las diferencias de redondeo van en el descuento del pedido. El constructor elige cómo repartirlos.
- **Si las líneas no se pueden cuadrar**, el conector no las envía, deja un WARN en el log y el pago sigue sin ellas. Así nunca falla un pago que hoy funciona. seQura puede no aparecer en ese caso.
- `payment_details.order_reference` lleva el código del carrito (el pedido aún no existe). El constructor comprueba si Stripe permite cambiarlo después al código del pedido.
- El constructor comprueba si la versión de API `2025-09-30.clover` y `stripe-java` 30.0.0 necesitan alguna opción de vista previa para `amount_details.line_items`. Si la necesitan, lo comenta antes de seguir.
- Klarna (`product_url`, `image_url`) y PayPal (`category`) tienen campos extra que mejoran la aprobación. Son opcionales y se dejan al constructor.

### 7.5 Reserva por método

- La lista por defecto de `stripe.capture.manual.methods` pasa a incluir todos los métodos de la sección 3 que admiten reserva en una cuenta de España: `card,link,paypal,amazon_pay,revolut_pay,mobilepay,satispay,klarna,sequra,scalapay,billie,mondu`. Se quitan `affirm` y `afterpay_clearpay`.
- `buildPaymentMethodOptions` pide la reserva para cada método de la lista que tenga opción `capture_method` en `stripe-java` 30.0.0. Si algún método no la tiene en esa versión, el constructor lo anota y ese método se cobra al momento.
- **Fecha límite de captura** (*decisión del diseñador*). Cuando Stripe la da (`capture_before` en el cargo), el conector la guarda en `StripePaymentInfo`. Una captura que falla porque la reserva caducó da un error claro en el log y en la transacción, sin reintentos sin fin. Detectar reservas a punto de caducar sigue fuera del proyecto.

### 7.6 Devoluciones fuera de plazo

Si Stripe rechaza una devolución porque el plazo del método ha pasado, el conector la marca como rechazada con un motivo claro y no la reintenta. El runbook del README explica qué hacer (devolución manual al cliente).

### 7.7 Spartacus

- Mostrar las instrucciones del grupo E en la confirmación y en el detalle del pedido (7.1), con textos traducibles.
- Comprobar que el formulario no falla cuando un método desaparece del Payment Element al cambiar el importe del carrito (Stripe oculta los métodos fuera de sus límites).
- Corregir `homepage` y `repository` del `package.json` de la librería para que apunten a `https://github.com/serpean/stripe-sap-commerce-connector`.

## 8. Release en GitHub (decisiones del propietario)

- **Cuándo.** Un workflow de GitHub Actions se lanza al subir un tag `v*` (por ejemplo `v3.1.0`). Publica en GitHub Releases. No publica nada en npm. *Decisión del diseñador:* también se puede lanzar a mano (`workflow_dispatch`) para generar el zip como artefacto sin publicar la release.
- **Contenido del zip** (*decisión del diseñador, aceptada por el propietario*):
  - las extensiones `stripecore`, `stripefacades` y `stripeocc` en código fuente, listas para copiar en `hybris/bin/custom`, sin ficheros generados (`classes`, `gensrc`, `eclipsebin` y similares);
  - la librería de Spartacus compilada, como paquete `.tgz` de `npm pack`;
  - `README.md`, `CHANGELOG.md`, `LICENSE` y la guía de migración;
  - un fichero de sumas SHA-256 de todo lo anterior.
- **No contiene:** la plataforma SAP, `spartacussampledata`, la extensión de referencia `stripesampledata`, el storefront de ejemplo, la receta ni ningún secreto.
- **Comprobaciones antes de publicar.** El workflow compila la librería y pasa sus tests. Si algo falla, no hay release. Las extensiones Java no se compilan en GitHub, porque la plataforma SAP tiene licencia.
- **Acceso a Spartacus.** La librería importa `@spartacus/*`, que SAP solo publica en su registro privado. El workflow crea el `.npmrc` en tiempo de ejecución con el secreto `SAP_NPM_AUTH` (ya creado en el repositorio por el propietario) y el registro `https://73554900100900004337.npmsrv.base.repositories.cloud.sap/`. El `.npmrc` no se guarda en ningún artefacto. El `.tgz` no lleva código de Spartacus: lo declara en `peerDependencies`.
- **Versiones** (*decisión del diseñador, aceptada por el propietario*). El tag tiene que coincidir con la versión del `package.json` de la librería y con una sección del `CHANGELOG.md`. Si no coinciden, el workflow falla.
- **Licencia** (*decisión del propietario*). Apache-2.0, con el propietario como titular del copyright. El proyecto añade un fichero `LICENSE` en la raíz del repositorio con el texto completo de Apache-2.0 y la línea `Copyright 2026 serpean`. El `package.json` de la librería ya declara `Apache-2.0`.
- **Notas de la release.** Salen de la sección del `CHANGELOG.md` que corresponde al tag.
- **Primera release.** Este proyecto termina con la versión `3.1.0` (*decisión del diseñador*). El propietario sube el tag cuando quiera; el constructor no publica releases.

## 9. Pruebas

### 9.1 e2e (Playwright, plataforma de referencia)

La batería del proyecto 2 sigue pasando entera. Se añade un caso por grupo, con métodos que Stripe permite simular en modo test:

| Caso | Método | Qué comprueba |
|---|---|---|
| Reserva de tarjeta y BNPL | `klarna` | pedido con reserva, captura en OMS por el total |
| Redirección inmediata | `bizum` o, si no hay simulación en test, otro del grupo B | pedido cobrado al momento |
| Adeudo en proceso | `sepa_debit` con un IBAN de prueba que tarda | pedido en "pago pendiente", después aceptado y enviado. Cubre por fin el estado `PAYMENT_PENDING`, que el proyecto 2 no pudo probar |
| Adeudo rechazado | `sepa_debit` con un IBAN de prueba que falla | "cancelado: pago rechazado", stock liberado |
| Cupón pagado | `multibanco` con los datos de prueba de Stripe | pedido pendiente con instrucciones visibles, después pagado y enviado |
| Cupón caducado | `multibanco` | pedido cancelado tras el plazo (plazo corto en la configuración de test) |
| Transferencia exacta | `customer_balance` con la ayuda de test de Stripe para financiar el saldo | pedido pagado |
| Transferencia de menos | ídem | pedido cancelado al plazo y devolución de lo pagado |
| Transferencia de más | ídem | pedido enviado y devolución de la diferencia |
| Líneas del pedido | `sequra`, o comprobación por API si no hay simulación | el PaymentIntent tiene las líneas y la suma cuadra con el importe |

El constructor comprueba qué simulación existe en modo test para cada método. Si un caso no se puede simular, lo cubre con tests de integración y lo anota como limitación.

### 9.2 Tests unitarios y de integración (JUnit, con TDD)

Lógica de grupos y plazos, construcción y cuadre de líneas, `Customer` de Stripe por cuenta, pago de menos y de más, saldo sin pedido, cancelación parcial rechazada en "pago pendiente", devoluciones fuera de plazo, fecha límite de captura y la creación de pedido en `requires_action` con instrucciones.

### 9.3 Release

El workflow se prueba con un lanzamiento manual (`workflow_dispatch`) que genera el zip sin publicar. El constructor revisa el contenido del zip y comprueba que no hay secretos ni contenido de SAP.

## 10. Compatibilidad

Sin rupturas previstas sobre 3.0.0. Las propiedades nuevas tienen valores por defecto. Si el constructor necesita romper algo, lo anota en el `CHANGELOG.md` y en la guía de migración. El tipo nuevo para el `Customer` de Stripe y los atributos nuevos de `StripePaymentInfo` necesitan una actualización del sistema.

## 11. Lo que queda al constructor

- El evento de webhook que crea el pedido del grupo E.
- El momento de crear el `Customer` de Stripe.
- Cómo repartir descuentos y redondeos en las líneas del pedido.
- Los campos extra de Klarna y PayPal.
- Qué métodos tienen opción de reserva en `stripe-java` 30.0.0.
- Qué simulación de test usar en cada caso e2e.
- La estructura interna del workflow de release (pasos, versión de Node, nombre del zip).
