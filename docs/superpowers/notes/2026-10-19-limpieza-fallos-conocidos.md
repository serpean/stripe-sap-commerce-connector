# Fallos conocidos encontrados durante la limpieza

## Build de partida (tarea 1), 2026-10-19

- Plataforma: SAP Commerce 2211.55 en `/Volumes/e/y/stripe-ref-platform`, con `hybris/bin/custom` enlazado al repositorio.
- Java: SapMachine 17.0.12. La plataforma 2211.55 exige Java 17 (`util.xml`, `required_java_version`) y rechaza Java 21.
- La receta se copia con `rsync` a `installer/recipes/b2c_occ_stripe`. Con un symlink, Gradle no encuentra `installer-platform-plugin`.
- Comandos: `./install.sh -r b2c_occ_stripe setup -A initAdminPassword=nimda` y después `ant clean all`.

### Resultado del backend: falla la compilación

`ant clean all` falla en `stripecore` con 8 errores `cannot find symbol: variable WAITING` en `TransactionStatus`:

- `DefaultStripePaymentTransactionService.java:94`
- `StripePaymentProvider.java:120,181,194,201,211`
- `StripeWebhookService.java:156,171`

Causa: el valor `TransactionStatus.WAITING` (y `REQUESTED`) no existe en SAP Commerce. Lo añade la extensión de OPF `opfservices` en `opfservices-beans.xml:12-16`. La receta no incluye `opfservices`, así que el conector solo compilaba en un entorno con OPF. Es una dependencia oculta de OPF en el código Java. `StripeCheckoutFacade.java:85` también usa `WAITING`.

Decisión: se sustituye `WAITING` por `REVIEW`, el estado estándar de SAP para pagos pendientes. El comportamiento no cambia: `CheckAuthorizeOrderPaymentAction` solo acepta `ACCEPTED`, así que antes rechazaba los dos estados igual. El proyecto 2 revisa el mapeo completo de estados.

### Resultado del front: compila

`npm ci`, `ng build sap-commerce-stripe-payment` y `ng build` terminan sin errores, con avisos de módulos CommonJS. Para instalar los paquetes `@spartacus/*` hace falta acceso al repositorio npm de SAP. Ese acceso se configura en `js-storefront/stripestore/.npmrc`, que está fuera de git.

## Tarea 2

- `StripePaymentControllerTest.java:31` no compila: llama a `createOrUpdateIntent()`, un método que no existe. `ant all` también compila `testsrc`, así que bloqueaba la build. Se elimina. El proyecto 2 escribe los tests nuevos.
- Los `buildcallbacks.xml` de `stripecore`, `stripefacades` y `stripefulfilmentprocess` copiaban el `.classpath` de Eclipse durante `ant clean`. Al eliminar los ficheros de IDE, la build fallaba. Se quitan esos pasos, que solo sirven al IDE.
- Con `lib/stripe-java-30.0.0.jar` fuera de git, Maven lo descarga en la build (`usemaven=true`) y `ant all` termina con `BUILD SUCCESSFUL`.

## Tareas 7 a 9: plataforma de referencia y e2e

- La HAC se sirve en la raíz (`https://localhost:9002/`), no en `/hac`.
- macOS mata el wrapper de Tomcat (`Killed: 9`) en el primer arranque, hasta que el usuario lo permite en la configuración de seguridad.
- El paquete oficial `spartacussampledata` 221121.x solo crea clientes OAuth públicos (`authorization_code`). La app de referencia usa el cliente por defecto de Spartacus, `mobile_android`, y la prueba e2e necesita `client_credentials` para registrar clientes. `stripesampledata` crea ese cliente (solo referencia).
- El producto 1934793 está agotado. La prueba usa 816324, y la ruta de producto de Spartacus necesita el código y el nombre.
- Resultado: `npm run e2e` pasa dos veces seguidas. En el backend, cada pedido tiene una transacción `*-STRIPE` con AUTHORIZATION y CAPTURE en estado `ACCEPTED`, y el pedido llega a `COMPLETED`. No se ha encontrado ningún fallo del conector que bloquee el checkout con tarjeta sin 3DS.
