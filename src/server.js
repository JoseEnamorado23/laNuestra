import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as db from "./db.js";
import { responder, agenteConfigurado, mensajeDeError } from "./agente.js";
import * as whatsapp from "./whatsapp.js";
import * as twilio from "./twilio.js";
import * as rutas from "./rutas-gestion.js";

const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PORT = Number(process.env.PORT || 3000);
const VERIFY_TOKEN = process.env.WHATSAPP_VERIFY_TOKEN || "agua-pruebas-2026";

const app = express();
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf; } }));

// Un cliente puede mandar varios mensajes seguidos: se atienden en orden, uno a la vez.
const colas = new Map();
function enCola(telefono, tarea) {
  const anterior = colas.get(telefono) || Promise.resolve();
  const siguiente = anterior.then(tarea, tarea);
  colas.set(telefono, siguiente);
  siguiente.finally(() => { if (colas.get(telefono) === siguiente) colas.delete(telefono); });
  return siguiente;
}

// ================= Webhook de WhatsApp (Meta) =================

// Verificación: Meta llama a esta ruta una vez al registrar el webhook.
app.get("/webhook", (req, res) => {
  const modo = req.query["hub.mode"];
  const token = req.query["hub.verify_token"];
  if (modo === "subscribe" && token === VERIFY_TOKEN) {
    console.log("Webhook verificado por Meta.");
    return res.status(200).send(String(req.query["hub.challenge"] ?? ""));
  }
  res.sendStatus(403);
});

// Mensajes entrantes.
app.post("/webhook", (req, res) => {
  if (!whatsapp.firmaValida(req.rawBody, req.get("x-hub-signature-256"))) {
    console.warn("Webhook rechazado: firma inválida.");
    return res.sendStatus(401);
  }
  // Meta exige responder 200 rápido; el mensaje se procesa después.
  res.sendStatus(200);

  for (const entrada of req.body?.entry || []) {
    for (const cambio of entrada.changes || []) {
      const valor = cambio.value || {};
      for (const mensaje of valor.messages || []) {
        if (db.yaProcesado(mensaje.id)) continue;
        const contacto = (valor.contacts || []).find((c) => c.wa_id === mensaje.from) || valor.contacts?.[0];
        enCola(mensaje.from, () => atenderWhatsapp(mensaje, contacto?.profile?.name));
      }
    }
  }
});

async function atenderWhatsapp(mensaje, nombrePerfil) {
  await whatsapp.marcarLeido(mensaje.id);
  await atender(whatsapp.enviarTexto, mensaje.from, whatsapp.textoDeMensaje(mensaje), nombrePerfil);
}

// Común a Meta y Twilio: pasa el texto al agente y envía la respuesta por el canal.
async function atender(enviarTexto, telefono, texto, nombrePerfil) {
  try {
    if (!texto) {
      await enviarTexto(telefono, "Por ahora solo puedo leer mensajes de texto 🙏 ¿Me escribes tu pedido?");
      return;
    }
    console.log(`[cliente ${telefono}] ${texto}`);
    const respuesta = await responder({ telefono, texto, nombrePerfil });
    console.log(`[agente -> ${telefono}] ${respuesta}`);
    await enviarTexto(telefono, respuesta);
  } catch (error) {
    console.error(`Error atendiendo a ${telefono}:`, mensajeDeError(error));
    try {
      await enviarTexto(telefono, "Tuvimos un inconveniente técnico. Intenta de nuevo en unos minutos, por favor.");
    } catch (e) { console.error("Tampoco se pudo avisar al cliente:", mensajeDeError(e)); }
  }
}

// ================= Webhook de WhatsApp (Twilio, sandbox) =================

app.post("/twilio/webhook", express.urlencoded({ extended: false }), (req, res) => {
  if (!twilio.firmaValida(req)) {
    console.warn(`Webhook de Twilio rechazado: firma inválida (URL usada: ${twilio.urlPublica(req)}).`);
    return res.sendStatus(403);
  }
  // TwiML vacío: la respuesta del agente se envía después por la API REST.
  res.type("text/xml").send("<Response></Response>");

  const campos = req.body || {};
  const telefono = twilio.normalizarTelefono(campos.From);
  if (!campos.MessageSid || !telefono || db.yaProcesado(campos.MessageSid)) return;
  enCola(telefono, () => atender(twilio.enviarTexto, telefono, twilio.textoDeMensaje(campos), campos.ProfileName));
});

// ================= Panel y API interna =================

// Inicio de sesión y primer administrador: sin sesión.
app.use(rutas.publicas);
// Todo lo demás (API y panel web) requiere sesión: ver src/rutas-gestion.js.
app.use(rutas.autenticar);
app.use(rutas.privadas);

app.get("/api/estado", (req, res) => {
  res.json({
    usuario: req.usuario,
    negocio: db.leerNegocio().nombre,
    agente: agenteConfigurado(),
    whatsapp: whatsapp.whatsappConfigurado(),
    firma: Boolean(process.env.WHATSAPP_APP_SECRET),
    twilio: twilio.twilioConfigurado(),
    twilioFirma: twilio.firmaActiva(),
  });
});

app.get("/api/pedidos", rutas.requiere("pedidos"), (_req, res) => res.json(db.listarPedidos()));

app.get("/api/conversaciones", rutas.requiere("pedidos"), (_req, res) => res.json(db.listarConversaciones()));

app.delete("/api/conversaciones/:telefono", rutas.requiere("configuracion"), (req, res) => {
  db.borrarConversacion(req.params.telefono);
  res.json({ ok: true });
});

// Simulador: mismo agente y mismas herramientas, sin pasar por WhatsApp.
app.post("/api/simular", rutas.requiere("configuracion"), async (req, res) => {
  const telefono = String(req.body?.telefono || "").trim();
  const texto = String(req.body?.texto || "").trim();
  if (!telefono || !texto) return res.status(400).json({ error: "Faltan telefono y texto" });
  if (!agenteConfigurado()) {
    return res.status(503).json({ error: "Falta ANTHROPIC_API_KEY en el archivo .env; sin ella el agente no puede responder." });
  }
  try {
    const respuesta = await enCola(telefono, () => responder({ telefono, texto, nombrePerfil: "" }));
    res.json({ respuesta });
  } catch (error) {
    console.error("Error en el simulador:", error);
    res.status(500).json({ error: mensajeDeError(error) });
  }
});

app.use(express.static(path.join(raiz, "public")));
app.use(rutas.manejarErrores);

app.listen(PORT, () => {
  console.log(`\nPanel de pedidos y simulador:  http://localhost:${PORT}`);
  console.log(`Webhook para Meta:             http://localhost:${PORT}/webhook  (exponer con: ngrok http ${PORT})`);
  console.log(`Token de verificación:         ${VERIFY_TOKEN}`);
  console.log(`Claude:   ${agenteConfigurado() ? "configurado" : "FALTA ANTHROPIC_API_KEY"}`);
  console.log(`WhatsApp: ${whatsapp.whatsappConfigurado() ? "configurado" : "sin credenciales (las respuestas solo salen en esta consola)"}`);
  const baseTwilio = process.env.PUBLIC_URL ? process.env.PUBLIC_URL.replace(/\/+$/, "") : "https://<ngrok>";
  console.log(`Twilio:   ${twilio.twilioConfigurado() ? "configurado" : "sin credenciales (las respuestas solo salen en esta consola)"}`);
  console.log(`Webhook para Twilio:           ${baseTwilio}/twilio/webhook${twilio.firmaActiva() ? "" : "  (SIN verificar firma: falta TWILIO_AUTH_TOKEN)"}\n`);
});
