# Verificación rápida y entorno de demo

Fecha: 2026-10-28. Rama: `feat/verificacion-y-demo`, desde `main` (`db287e7`, versión 4.0.0 publicada).

Este trabajo va antes del proyecto 4 (actualizaciones, CI, Renovate y manifiesto CCv2). El proyecto 4 cambia dependencias y necesita muchas verificaciones. Por eso primero hacemos que verificar sea rápido.

Las decisiones marcadas *(decisión de Claude)* no las pidió el propietario. El propietario puede cambiarlas.

## Parte A: verificación rápida

### Por qué

En el proyecto 3, verificar cada tarea costó horas:

- La batería e2e tardaba unos 40 minutos, con `workers: 1`, y se pasaba dos veces.
- Las pruebas Java necesitaban parar el servidor SAP, porque HSQLDB bloquea sus ficheros. Después había que arrancarlo otra vez, y a veces se colgaba.
- Las pruebas unitarias necesitan la plataforma completa.
- Las esperas reales de Stripe (Multibanco unos 3 minutos, SEPA, saldo de transferencia) alargaban cada pasada.
- Una pasada usó una URL base equivocada (`:4200`, otra aplicación) y esperó 4,8 horas antes de fallar.

Al propietario le pesa sobre todo la verificación de cada tarea, más que la verificación final antes de publicar.

### Objetivos

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

### Restricciones

- Las pruebas en paralelo no pueden compartir estado. Cada prueba usa su propio carrito y su propio cliente. La recarga de stock y los cambios de plazos por método, que hoy hacen algunos specs, no deben chocar entre workers. Por ejemplo, pueden ir al global-setup o usar productos propios por spec.
- La plataforma de referencia (`/Volumes/e/y/stripe-ref-platform`) está fuera del repo. Los cambios de base de datos se documentan en el README (sección del proyecto de referencia) y se pueden aplicar con un script del repo.
- El README sigue la línea actual: inglés sencillo y frases cortas.
- Las claves de Stripe siguen en `.env`, fuera de git. No se imprimen nunca.
- No baja la cobertura: cada caso e2e de hoy sigue existiendo, en la batería normal o en la lenta.

### Fuera del alcance

- CI con e2e en GitHub Actions (proyecto 4).
- Cambiar Playwright por otra herramienta.
- Pruebas de rendimiento.

### Decisiones de Claude tras la revisión

- Las pruebas unitarias sin plataforma solo corren en local. Pueden usar los jars de la plataforma local en el classpath, pero ningún jar de SAP entra en git. Si una prueba unitaria necesita de verdad la plataforma, pasa a integración, y esas corren con el servidor arrancado.
- Los specs que cambian la configuración global del servidor (modo de captura manual o automático, plazos por método) y los que dependen del orden de los eventos (`race`, `webhook-amount-mismatch`) van en un proyecto de Playwright que no corre en paralelo. Los demás corren en paralelo, cada uno con su propio producto o con stock suficiente para todos los workers.
- La batería lenta reúne las pruebas que necesitan esperas reales de Stripe y no tienen un email o un IBAN de prueba con resultado inmediato. Corre en la verificación final, además de la batería en paralelo.

### Lo que decide quien construya

- Gradle o Maven para las pruebas unitarias sin plataforma, y cómo se obtienen las clases de SAP que necesitan (stubs, jars de la plataforma en el classpath u otro método).
- Qué pruebas unitarias actuales necesitan la plataforma de verdad y deben pasar a integración.
- El nombre del comando de e2e por área y su mapa de ficheros a etiquetas.
- El número exacto de workers, según la máquina.

## Parte B: entorno de demo

### Por qué

El propietario quiere enseñar el conector. La demo es sobre todo para él. Solo necesita existir cuando la va a usar, y siempre muestra la última versión publicada.

### Objetivos

1. **El ZIP de SAP Commerce en un bucket privado.** El ZIP de la plataforma (`CXCOMCL221100U_55-*.ZIP`) va a un bucket nuevo de Cloudflare R2, solo para esto y separado de los backups. Su token solo puede leer ese bucket (decisión del propietario: Cloudflare R2). Los runners de GitHub no compilan SAP.
2. **Imagen y volcado por versión, creados por el propio servidor de la demo** *(decisión de Claude)*. La primera vez que "Demo up" arranca una versión publicada nueva, el servidor de la demo:
   - descarga el ZIP desde R2 y el zip del release desde GitHub;
   - construye la imagen Docker (receta del proyecto de referencia: `b2c_occ_stripe`, OMS, `stripesampledata` y `spartacussampledata`, con `ant production`);
   - inicializa PostgreSQL con los datos de ejemplo;
   - sube la imagen a GHCR privado y el volcado (`pg_dump`) a R2.

   Las veces siguientes, la demo descarga la imagen y el volcado y no compila nada. La imagen lleva binarios de SAP con licencia, así que nunca va a un registro público.
3. **"Demo up".** Un workflow de GitHub que se lanza a mano:
   1. crea un servidor de Hetzner de unos 32 GB, x86, desde un snapshot base con Docker;
   2. lo añade al Coolify que el propietario ya tiene, mediante su API en `https://coolify.serpean.dev`;
   3. despliega la última versión publicada en ese servidor: SAP Commerce, Solr, PostgreSQL (con el volcado) y Spartacus con SSR *(decisión de Claude: todo en un solo servidor)*.

   Objetivo: de 10 a 15 minutos con imagen y volcado ya creados. La primera vez de cada versión tarda más (unos 60-90 minutos), porque compila e inicializa.
4. **"Demo down".** Un workflow que borra el servidor. Solo se pagan las horas de uso más el snapshot.
5. **Dominio y acceso con Cloudflare** *(decisión de Claude; el DNS de `serpean.dev` está en Cloudflare)*.
   - Un Cloudflare Tunnel (`cloudflared` en el servidor de la demo) publica `demo.serpean.dev`. Así no hay que cambiar el DNS cada vez que cambia la IP del servidor, y el servidor no abre puertos a internet.
   - Un solo hostname. `/` va a Spartacus SSR; `/occ`, `/authorizationserver`, `/medias` y la Backoffice van a SAP. Así el navegador no hace llamadas a otro origen.
   - Cloudflare Access protege todo el hostname con el email del propietario (código de un solo uso). Esto sustituye a la contraseña y al límite por IP, y también cubre la HAC.
   - Solo la ruta del webhook de Stripe queda fuera de Access, porque el conector ya comprueba la firma.
6. **Stripe en modo test.** Las claves de test de la cuenta del propietario van como secretos, nunca en el repo. Hay un webhook fijo en Stripe hacia `demo.serpean.dev`, con su secreto fijo *(decisión de Claude)*. "Demo up" lo activa y "Demo down" lo desactiva, con una clave restringida que solo puede editar webhooks. Así Stripe no acumula fallos. Con un dominio real, Apple Pay y Google Pay se pueden registrar y probar.

### Restricciones

- El Coolify actual corre en un servidor de Hetzner sin recursos suficientes. La demo usa un servidor aparte, gestionado desde el mismo Coolify.
- x86, no ARM: SAP Commerce está más probado en x86.
- Licencia de SAP: la demo es de uso personal, con acceso restringido y sin acceso público.
- Los secretos (token de Hetzner, token de la API de Coolify, token de R2, token de Cloudflare para el Tunnel y Access, claves de Stripe, credencial npm de SAP y acceso a GHCR) van en los secretos de GitHub Actions o de Coolify. No se imprimen en los logs.
- `stripesampledata` trae el secreto OAuth conocido `secret`. Se acepta en la demo porque Cloudflare Access protege todo el hostname y la demo solo existe mientras se usa.
- La infraestructura de la demo debe poder reproducirse desde el repo. Nada se configura a mano sin documentarlo.

### Fuera del alcance

- Demo abierta al público y datos reales.
- Mantener la demo siempre encendida.
- Enseñar versiones que no están publicadas, como ramas o `main`.
- CCv2: el manifiesto CCv2 es parte del proyecto 4.

### Lo que decide quien construya

- Cómo se describe el despliegue en Coolify: docker compose, la API de Coolify, o los dos.
- El tipo exacto de servidor de Hetzner (x86, CCX o CPX) y su región.
- Cómo se crea y se actualiza el snapshot base.
- Cómo se une el servidor nuevo a Coolify (clave SSH de Coolify en el snapshot o mediante la API).

## Orden

1. Parte A primero, porque acelera todo lo demás, incluida la parte B.
2. Parte B después.

## Cómo sabemos que está terminado

- **Parte A:**
  - verificar una tarea que toca un área (por ejemplo, SEPA) tarda menos de 10 minutos y no para el servidor;
  - la batería final completa pasa en una pasada, en unos 15 minutos;
  - una URL base equivocada falla en menos de 30 segundos.
- **Parte B:**
  - "Demo up" deja la última versión publicada accesible con contraseña, y un pago de test con tarjeta crea un pedido;
  - "Demo down" borra el servidor;
  - en GitHub no queda ningún secreto en logs ni en imágenes públicas.
