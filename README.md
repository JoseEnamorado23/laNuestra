# La Nuestra: pedidos de agua por WhatsApp

Un agente de IA (Claude) atiende el WhatsApp del negocio y toma los pedidos. Una app Android (`app-android/`) y un panel web manejan pedidos, inventario, lotes de producción, gastos, reportes mensuales y usuarios. Los datos viven en SQLite.

```
Cliente en WhatsApp -> Meta (Cloud API) -> POST /webhook -> agente (Claude + herramientas) -> pedido en el panel
                                                         <- respuesta por WhatsApp
Cliente en WhatsApp -> Twilio (sandbox)  -> POST /twilio/webhook -> el mismo agente  (mientras Meta aprueba)
```

## Arrancar

Requiere Node.js 24 (trae SQLite incluido, no hay que instalar nada más).

```
npm install
cp .env.example .env      # y pon ANTHROPIC_API_KEY
npm run demo              # opcional: carga ~6 meses de datos de muestra
npm start
```

Abre http://localhost:3001 : a la izquierda los pedidos, a la derecha un simulador de WhatsApp.

- **Datos:** `data/lanuestra.db` (SQLite). Para respaldar, `bash deploy/respaldo.sh` o copiar el archivo con el servidor apagado.
- **Configuración del negocio** (productos, precios, costos, horario, zonas, pagos): se edita desde la app (Más → Configuración / Productos). `negocio.json` solo se usa la primera vez que se crea la base.
- **Datos de muestra:** `npm run demo` agrega usuarios `admin`, `carmen` (despacho) y `pedro` (repartidor), todos con contraseña `demo1234`, más pedidos, lotes y gastos desde hace 5 meses. `npm run demo -- --reiniciar` borra la base y empieza de cero. **Cambia esas contraseñas si el servidor es público.**
- Si existen los `data/*.json` de la versión anterior, se importan solos a SQLite al arrancar y quedan copiados en `data/respaldo-json/`.

## Etapa 1 - Probar sin WhatsApp (simulador)

1. Pon `ANTHROPIC_API_KEY` en `.env` y ejecuta `npm start`.
2. Escribe en el simulador del panel como si fueras el cliente. El pedido aparece a la izquierda.

`npm run probar-webhook -- "Hola, quiero 2 botellones"` envía un webhook con el formato real de Meta al servidor local; la respuesta sale en la consola del servidor.

## Etapa 1b - Probar desde tu celular con el sandbox de Twilio

Sirve mientras Meta tiene la cuenta restringida. El sandbox es un número compartido de Twilio (+1 415 523 8886); solo te pueden escribir los celulares que se unen con un código.

1. Crea una cuenta gratis en https://www.twilio.com/try-twilio y verifica tu celular.
2. En la consola (https://console.twilio.com), página de inicio, copia a `.env`:
   - **Account SID** -> `TWILIO_ACCOUNT_SID`
   - **Auth Token** -> `TWILIO_AUTH_TOKEN`
3. Ve a **Messaging > Try it out > Send a WhatsApp message**. Ahí sale el número del sandbox y un código tipo `join palabra-palabra`. Desde tu celular manda ese mensaje por WhatsApp a ese número. Copia el número como `TWILIO_WHATSAPP_FROM=whatsapp:+14155238886`.
4. En otra terminal ejecuta `ngrok http 3001` y copia la URL `https://....ngrok-free.app` en `PUBLIC_URL` (sin barra final). Reinicia `npm start`.
5. En la misma página de Twilio, pestaña **Sandbox settings**, en **When a message comes in** pon `https://....ngrok-free.app/twilio/webhook`, método **POST**, y guarda.
6. Escribe al número del sandbox desde tu celular. El agente responde y el pedido aparece en el panel.

Notas:
- La firma `X-Twilio-Signature` se valida con el Auth Token sobre `PUBLIC_URL`. Si ngrok cambia de URL (pasa cada vez que lo reinicias en el plan gratis), cambia `PUBLIC_URL`, reinicia el servidor y actualiza la URL en Twilio; si no, el servidor responde 403.
- La unión al sandbox vence a los 3 días sin escribir: se vuelve a mandar el `join ...`.
- `npm run probar-twilio -- "Hola, quiero 2 botellones"` envía un webhook firmado igual que Twilio al servidor local (con `--sin-firma` comprueba que se rechace).

## Etapa 2 - Probar con el número de prueba de Meta (sin el número real)

1. En https://developers.facebook.com crea una app de tipo **Empresa** y agrégale el producto **WhatsApp**.
2. En **WhatsApp > Configuración de la API** Meta te da un **número de prueba**. Copia a `.env`:
   - el token de acceso temporal -> `WHATSAPP_TOKEN` (vence a las 24 h; se genera otro en la misma página)
   - el identificador del número de teléfono -> `WHATSAPP_PHONE_NUMBER_ID`
3. En la misma página, en "Para", agrega tu celular personal como destinatario (el número de prueba solo puede escribirle a números verificados, máximo 5).
4. En **Configuración > Básica** copia la clave secreta de la app -> `WHATSAPP_APP_SECRET`.
5. Pon una `PANEL_CLAVE` en `.env`, reinicia (`npm start`) y en otra terminal ejecuta `ngrok http 3001`.
6. En **WhatsApp > Configuración > Webhook**:
   - URL de devolución de llamada: `https://TU-SUBDOMINIO.ngrok-free.app/webhook`
   - Token de verificación: el valor de `WHATSAPP_VERIFY_TOKEN`
   - Suscríbete al campo **messages**.
7. Desde tu celular escríbele al número de prueba. El agente responde y el pedido aparece en el panel.

## Etapa 3 - Número real

Se registra el número del negocio en la misma app de Meta (no puede estar activo en la app normal de WhatsApp al mismo tiempo, salvo con la modalidad de coexistencia de WhatsApp Business), se crea un token permanente con un usuario del sistema y se cambian `WHATSAPP_TOKEN` y `WHATSAPP_PHONE_NUMBER_ID`. El código no cambia. Hace falta además un servidor con HTTPS fijo en lugar de ngrok.

## Archivos

- `src/server.js` - webhook, API del panel y simulador
- `src/agente.js` - instrucciones del agente y herramientas (crear, consultar y cancelar pedido, pedir asesor)
- `src/whatsapp.js` - envío de mensajes y verificación de firma (Meta)
- `src/twilio.js` - lo mismo para Twilio (sandbox)
- `src/basedatos.js` - esquema SQLite, transacciones e importación de los JSON antiguos
- `src/db.js` - pedidos, conversaciones y configuración
- `src/gestion.js` - usuarios, inventario, lotes, gastos y reportes
- `src/rutas-gestion.js` - API de la app (login, pedidos manuales, inventario, reportes...)
- `scripts/datos-demo.js` - datos de muestra
- `deploy/` - instalación en AWS Lightsail, actualización y respaldos
- `app-android/` - app Android (Kotlin + Jetpack Compose)
- `public/index.html` - panel

## Subir a AWS Lightsail (para mostrarlo)

1. En https://lightsail.aws.amazon.com: **Create instance** → Linux/Unix → **OS Only → Ubuntu 24.04 LTS** → plan de **1 GB** (o 512 MB para una demo) → nombre `la-nuestra` → **Create**.
2. En la instancia, pestaña **Networking**:
   - **Attach static IP** (gratis mientras esté conectada).
   - En el firewall IPv4 deja **SSH (22)** y agrega **HTTP (80)** y **HTTPS (443)**.
3. Pestaña **Connect** → **Connect using SSH** (abre una terminal en el navegador) y ejecuta:
   ```
   git clone https://github.com/JoseEnamorado23/laNuestra.git ~/laNuestra
   bash ~/laNuestra/deploy/instalar.sh
   ```
   Instala Node 24, pm2 y Caddy (HTTPS automático), carga los datos de muestra y deja todo encendido aunque el servidor se reinicie. Sin dominio usa una dirección gratis tipo `https://3-85-12-40.sslip.io`; con dominio: `bash ~/laNuestra/deploy/instalar.sh pedidos.midominio.co` (apunta antes el dominio a la IP).
4. Pon las claves: `nano ~/laNuestra/.env` (`ANTHROPIC_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `PANEL_CLAVE`) y luego `pm2 reload la-nuestra`.
5. En Twilio (**Sandbox settings → When a message comes in**) pon `https://TU-DIRECCION/twilio/webhook`.
6. En la app Android, en la pantalla de inicio de sesión, toca **Servidor** y escribe `https://TU-DIRECCION`.

Después:
- Actualizar con los cambios de GitHub: `bash ~/laNuestra/deploy/actualizar.sh` (hace respaldo antes).
- Ver registros: `pm2 logs la-nuestra`.
- Respaldo diario automático de la base en `~/laNuestra/respaldos/` (se guardan 14 días). Activa también los *snapshots* automáticos de Lightsail.
