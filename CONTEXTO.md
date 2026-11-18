# Contexto del proyecto: agente de WhatsApp para pedidos de agua

## Objetivo
Una empresa empacadora y distribuidora de agua (LA NUESTRA S A S) quiere que su número de WhatsApp lo atienda un agente de IA (Claude). El agente toma el pedido del cliente y lo carga en una app (panel web) para que el equipo de despacho lo vea.

**Lo que quiero ahora:** primero mostrarlo y probarlo con el **sandbox de WhatsApp de Twilio**, desde mi celular, sin el número real y sin esperar a Meta. Hay que agregar al proyecto un adaptador para Twilio.

## Qué ya está construido
Proyecto en `C:\Users\PC\Desktop\agente-agua` (Node.js 24, ES modules, Express 5, `@anthropic-ai/sdk`). Ver `README.md`.

- `src/server.js`: webhook de Meta (`GET /webhook` para verificar y `POST /webhook` para mensajes, con firma HMAC y deduplicación), API del panel (`/api/pedidos`, `/api/conversaciones`, `/api/estado`) y simulador (`POST /api/simular`). Cola por teléfono para atender los mensajes en orden. Clave opcional del panel con `PANEL_CLAVE` (usuario `admin`).
- `src/agente.js`: función `responder({ telefono, texto, nombrePerfil })` que devuelve el texto de respuesta. Hace un ciclo manual de herramientas con Claude (modelo `MODELO`, por defecto `claude-opus-5-5`, effort `low`, fallbacks del servidor). Herramientas: `crear_pedido`, `consultar_mis_pedidos`, `cancelar_pedido` y `pedir_asesor_humano`. El total lo calcula el servidor con `negocio.json`.
- `src/whatsapp.js`: envío por la WhatsApp Cloud API de Meta, marcado como leído, verificación de firma y conversión de mensajes entrantes a texto.
- `src/db.js`: pedidos, conversaciones, mensajes ya procesados y configuración del negocio, sobre SQLite (`src/basedatos.js`).
- `public/index.html`: panel de pedidos con cambio de estado, avisos de "pide asesor" y simulador de chat.
- `negocio.json`: valores iniciales del negocio. Solo se lee al crear la base; después la configuración vive en SQLite (tabla `ajustes`, clave `negocio`) y se edita desde la app.
- `scripts/probar-webhook.js`: envía un webhook con el formato de Meta al servidor local.
- `.env` (copiado de `.env.example`): `ANTHROPIC_API_KEY`, `MODELO` (ahora `claude-haiku-5-5`), `ESFUERZO` (`medium`), `WHATSAPP_*`, `PORT`, `PANEL_CLAVE`.

Arranque: `npm start` y abrir `http://localhost:3000`. Para exponer: `ngrok http 3000` (ngrok ya está instalado).

## Qué se probó
- Funciona: la verificación del webhook, el rechazo sin firma (401), el webhook firmado, crear, consultar y cancelar pedidos, y el panel.
- **Falta probar** una conversación real con Claude: todavía no se ha puesto `ANTHROPIC_API_KEY` en `.env`.

## Por qué no se usa Meta todavía
- App de Meta: "LaNuestra" (ID 1133602642756597), portfolio comercial 1662655418574396.
- Número de prueba +1 555 148 5353, Phone Number ID 1471037912756048, cuenta de WhatsApp 1042782625496362.
- La cuenta de WhatsApp está **restringida** ("Cuenta restringida", permanente) hasta verificar el negocio. Los envíos fallan con el error `131031 Business Account locked`.
- La verificación del negocio para LA NUESTRA S A S se inició el 9 de noviembre de 2026 y está pendiente, sin completar (faltan datos del negocio, confirmar la conexión y quizás documentos como la Cámara de Comercio o el RUT). La completa el dueño.
- Cuando Meta apruebe, se genera el token, se ponen `WHATSAPP_TOKEN` y `WHATSAPP_PHONE_NUMBER_ID` en `.env` y el código de Meta ya está listo.

## Tarea para la nueva sesión: adaptador de Twilio
1. Agregar `src/twilio.js` y una ruta, por ejemplo `POST /twilio/webhook`:
   - Twilio envía `application/x-www-form-urlencoded` con los campos `From` (`whatsapp:+57...`), `Body`, `ProfileName` y `MessageSid`.
   - Validar la firma `X-Twilio-Signature` con el Auth Token, usando la URL pública exacta.
   - Responder rápido (TwiML vacío o 200) y enviar la respuesta del agente por la API REST de Twilio desde el número del sandbox. Se puede usar el paquete oficial `twilio`.
   - Reutilizar `responder()`, la cola por teléfono y `db.yaProcesado()` con el `MessageSid`. Normalizar el teléfono quitando `whatsapp:+`.
2. Variables nuevas en `.env` y `.env.example`: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_WHATSAPP_FROM` (por ejemplo `whatsapp:+14155238886`) y `PUBLIC_URL` para validar la firma detrás de ngrok.
3. Mostrar en el panel o en `/api/estado` si Twilio está configurado.
4. Agregar al README la etapa Twilio: crear la cuenta, ir a Messaging > Try it out > Send a WhatsApp message, unir el celular con el código "join ...", configurar "When a message comes in" con `https://<ngrok>/twilio/webhook` (POST) y usar `ngrok http 3000`.
5. Probarlo con una petición firmada simulada antes de pedir las credenciales reales.

El código de Meta se mantiene para usarlo cuando aprueben la verificación.

## Estado (17 nov 2026): adaptador de Twilio hecho
`src/twilio.js`, ruta `POST /twilio/webhook`, variables en `.env`, chip en el panel y `twilio`/`twilioFirma` en `/api/estado`, etapa 1b en el README y `npm run probar-twilio`. Probado con peticiones firmadas simuladas: firmada 200, sin firma 403, URL distinta 403, token distinto 403, MessageSid repetido se atiende una sola vez, cuerpo alterado 403. Falta: credenciales reales de Twilio y `ANTHROPIC_API_KEY`.
- En este PC hay un `netsh portproxy` de Windows que reenvía 0.0.0.0:3000 a 172.24.32.125:3000 (WSL/Docker), así que el `.env` usa `PORT=3001` y ngrok va con `ngrok http 3001`. Dominio fijo de ngrok: https://envy-handstand-customary.ngrok-free.dev

## App Android de despacho (17 nov 2026)
Carpeta `app-android/`: Kotlin + Jetpack Compose (AGP 8.13.1, Kotlin 2.2.20, Gradle 8.14, compileSdk 36, minSdk 26, OkHttp). Paquete `co.lanuestra.despacho`. Consume la API del panel (`/api/estado`, `/api/pedidos`, `PATCH /api/pedidos/:id`, `/api/conversaciones`) por la URL de ngrok, con Basic Auth opcional (`PANEL_CLAVE`) y el encabezado `ngrok-skip-browser-warning`. Pantallas: configuración (URL + clave), lista con filtros que se actualiza cada 5 s, avisos de "pide asesor", detalle con mapa/llamar/WhatsApp y cambio de estado. Compilar: `cd app-android` y `gradlew assembleDebug` con `JAVA_HOME` = el `jbr` de Android Studio. APK copiado en `LaNuestraDespacho.apk`. Probada en el emulador Pixel_7 contra el servidor real.

## App Android v2 y gestión (nov 2026)
- Backend nuevo: `src/gestion.js` (usuarios, sesiones, inventario, lotes, pedidos manuales, gastos, reportes) y `src/rutas-gestion.js` (API). Errores de negocio → `{error}` con su código HTTP.
- Usuarios con roles: admin (todo), despacho (pedidos, pedido manual, inventario, lotes), repartidor (pedidos). Tokens Bearer de 30 días (tabla `sesiones`).
- Cuando existe el primer usuario, la API y el panel web exigen login (el panel usa Basic con usuario/clave de la app, o admin:PANEL_CLAVE).
- Inventario: cada venta descuenta stock, cancelar lo devuelve; lotes suman; ajustes exigen motivo. Alerta cuando stock <= stock_minimo del producto.
- Reportes mensuales (hora de Bogotá): ventas, costo (costo_unitario guardado en cada item), gastos, utilidad, lo más vendido, lo que más ganancia deja, por día, canal, pago, clientes y producción, más una tendencia de 6 meses.
- Celulares de 10 dígitos que empiezan por 3 se guardan con el prefijo 57.
- `NEGOCIO_ARCHIVO` (opcional) cambia el negocio.json inicial; `DATA_DIR` / `DB_ARCHIVO` cambian dónde está la base. Útil para pruebas aisladas.

## SQLite (nov 2026)
- `node:sqlite` (incluido en Node 24, sin dependencias nativas). Archivo `data/lanuestra.db`, modo WAL.
- Tablas: ajustes, usuarios, sesiones, pedidos + pedido_items, conversaciones (mensajes como JSON), mensajes_vistos, stock, movimientos, lotes, gastos.
- `basedatos.js` expone `ejecutar/fila/filas` (parámetros con nombre; solo pasa los que usa el SQL) y `transaccion(fn)` anidable. Venta + descuento de inventario, cancelación y lotes van en transacción.
- Al arrancar, si existen los `data/*.json` antiguos se importan una sola vez y se mueven a `data/respaldo-json/`.
- `npm run demo` (`scripts/datos-demo.js`): usuarios admin/carmen/pedro (clave demo1234), ~800 pedidos desde hace 5 meses, lotes y gastos. Datos repetibles (semilla fija). `--reiniciar` borra la base.
- Despliegue: `deploy/instalar.sh` (Ubuntu 24.04 en Lightsail: Node 24, pm2 con `ecosystem.config.cjs`, Caddy con HTTPS, sslip.io si no hay dominio, cron de respaldo), `deploy/actualizar.sh`, `deploy/respaldo.sh` (VACUUM INTO).
- Repo: https://github.com/JoseEnamorado23/laNuestra
- App: `app-android/` (Kotlin + Compose), pestañas Inicio, Pedidos, Inventario, Reportes y Más (contabilidad, productos y precios, configuración, usuarios, movimientos). APK en `LaNuestraDespacho.apk`.
