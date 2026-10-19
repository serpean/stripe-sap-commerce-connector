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
