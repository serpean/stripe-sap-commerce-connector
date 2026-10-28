# Verificación rápida

Fecha: 2026-10-28. Rama: `feat/verificacion-y-demo`, desde `main` (`db287e7`, versión 4.0.0 publicada).

Este trabajo va antes del proyecto 4 (actualizaciones, CI, Renovate y manifiesto CCv2). El proyecto 4 cambia dependencias y necesita muchas verificaciones. Por eso primero hacemos que verificar sea rápido.

El entorno de demo se diseñó junto con este trabajo, pero vive en el repo privado `serpean/stripe-sap-commerce-demo`, porque es infraestructura personal del propietario.

Las decisiones marcadas *(decisión de Claude)* no las pidió el propietario. El propietario puede cambiarlas.

## Por qué

En el proyecto 3, verificar cada tarea costó horas:

- La batería e2e tardaba unos 40 minutos, con `workers: 1`, y se pasaba dos veces.
- Las pruebas Java necesitaban parar el servidor SAP, porque HSQLDB bloquea sus ficheros. Después había que arrancarlo otra vez, y a veces se colgaba.
- Las pruebas unitarias necesitan la plataforma completa.
- Las esperas reales de Stripe (Multibanco unos 3 minutos, SEPA, saldo de transferencia) alargaban cada pasada.
- Una pasada usó una URL base equivocada (`:4200`, otra aplicación) y esperó 4,8 horas antes de fallar.

Al propietario le pesa sobre todo la verificación de cada tarea, más que la verificación final antes de publicar.

## Objetivos

1. **Pruebas unitarias sin la plataforma.** Las clases de prueba unitaria de `stripecore`, `stripefacades` y `stripeocc` corren con JUnit y Mockito, sin arrancar la plataforma ni parar el servidor. Objetivo: menos de 1 minuto *(decisión de Claude)*.
2. **Pruebas de integración sin parar el servidor.** La plataforma de referencia usa PostgreSQL en Docker: una base para el tenant `master` y otra para el tenant `junit`. Las pruebas de integración corren con el servidor arrancado.
3. **Solo las e2e afectadas en cada tarea.** Cada spec lleva etiquetas por área: `@card`, `@redirect`, `@sepa`, `@multibanco`, `@bank-transfer`, `@after-purchase`, `@webhooks`, `@line-items` *(decisión de Claude: lista de etiquetas)*. Un comando elige las etiquetas según los ficheros cambiados desde una rama base. Si no puede decidir, ejecuta toda la batería.
4. **Fallo rápido del entorno.** Antes de la primera prueba, el global-setup comprueba, en menos de 30 segundos:
   - que la URL base sirve nuestra tienda (por ejemplo, la respuesta contiene la app de Spartacus de `stripestore`);
   - que la HAC responde;
   - que `stripe listen` reenvía eventos al servidor.

   Si algo falla, para la batería con un mensaje que dice qué falta.
5. **Menos esperas de Stripe.** Las pruebas usan los emails de prueba que dan el resultado al momento (`succeed_immediately@`, `expire_immediately@` y los equivalentes de SEPA). Las pruebas con espera real pasan a una batería "lenta" aparte, que solo corre en la verificación final.
6. **Batería final en una pasada.** De 3 a 4 workers en paralelo, `retries: 1`, y vídeo y traza solo en el reintento (`on-first-retry`). `E2E_VIDEO=on` sigue grabando todas las pruebas. Objetivo: unos 15 minutos *(decisión de Claude)*.

## Restricciones

- Las pruebas en paralelo no pueden compartir estado. Cada prueba usa su propio carrito y su propio cliente. La recarga de stock y los cambios de plazos por método, que hoy hacen algunos specs, no deben chocar entre workers. Por ejemplo, pueden ir al global-setup o usar productos propios por spec.
- La plataforma de referencia (`/Volumes/e/y/stripe-ref-platform`) está fuera del repo. Los cambios de base de datos se documentan en el README (sección del proyecto de referencia) y se pueden aplicar con un script del repo.
- El README sigue la línea actual: inglés sencillo y frases cortas.
- Las claves de Stripe siguen en `.env`, fuera de git. No se imprimen nunca.
- No baja la cobertura: cada caso e2e de hoy sigue existiendo, en la batería normal o en la lenta.

## Fuera del alcance

- CI con e2e en GitHub Actions (proyecto 4).
- Cambiar Playwright por otra herramienta.
- Pruebas de rendimiento.

## Decisiones de Claude tras la revisión

- Las pruebas unitarias sin plataforma solo corren en local. Pueden usar los jars de la plataforma local en el classpath, pero ningún jar de SAP entra en git. Si una prueba unitaria necesita de verdad la plataforma, pasa a integración, y esas corren con el servidor arrancado.
- Los specs que cambian la configuración global del servidor (modo de captura manual o automático, plazos por método) y los que dependen del orden de los eventos (`race`, `webhook-amount-mismatch`) van en un proyecto de Playwright que no corre en paralelo. Los demás corren en paralelo, cada uno con su propio producto o con stock suficiente para todos los workers.
- La batería lenta reúne las pruebas que necesitan esperas reales de Stripe y no tienen un email o un IBAN de prueba con resultado inmediato. Corre en la verificación final, además de la batería en paralelo.

## Lo que decide quien construya

- Gradle o Maven para las pruebas unitarias sin plataforma, y cómo se obtienen las clases de SAP que necesitan (stubs, jars de la plataforma en el classpath u otro método).
- Qué pruebas unitarias actuales necesitan la plataforma de verdad y deben pasar a integración.
- El nombre del comando de e2e por área y su mapa de ficheros a etiquetas.
- El número exacto de workers, según la máquina.

## Cómo sabemos que está terminado

- Verificar una tarea que toca un área (por ejemplo, SEPA) tarda menos de 10 minutos y no para el servidor.
- La batería final completa pasa en una pasada, en unos 15 minutos.
- Una URL base equivocada falla en menos de 30 segundos.
