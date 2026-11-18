// Adaptador de WhatsApp por Twilio (sirve para el sandbox mientras Meta aprueba el negocio).
import twilio from "twilio";

let cliente = null;

export function twilioConfigurado() {
  return Boolean(process.env.TWILIO_ACCOUNT_SID && process.env.TWILIO_AUTH_TOKEN && process.env.TWILIO_WHATSAPP_FROM);
}

export function firmaActiva() {
  return Boolean(process.env.TWILIO_AUTH_TOKEN);
}

function obtenerCliente() {
  if (!cliente) cliente = twilio(process.env.TWILIO_ACCOUNT_SID, process.env.TWILIO_AUTH_TOKEN);
  return cliente;
}

// "whatsapp:+573001234567" -> "573001234567" (mismo formato que usa Meta en wa_id).
export function normalizarTelefono(from) {
  return String(from || "").replace(/^whatsapp:/i, "").replace(/^\+/, "").trim();
}

// Twilio firma con el Auth Token sobre la URL pública EXACTA que tiene configurada.
// Detrás de ngrok el servidor ve http://localhost, por eso se usa PUBLIC_URL.
export function urlPublica(req) {
  const base = process.env.PUBLIC_URL
    ? process.env.PUBLIC_URL.replace(/\/+$/, "")
    : `${req.get("x-forwarded-proto") || req.protocol}://${req.get("host")}`;
  return base + req.originalUrl;
}

export function firmaValida(req) {
  const token = process.env.TWILIO_AUTH_TOKEN;
  if (!token) return true; // sin Auth Token no se verifica (solo para pruebas locales)
  const firma = req.get("x-twilio-signature");
  if (!firma) return false;
  return twilio.validateRequest(token, firma, urlPublica(req), req.body || {});
}

// Convierte el formulario de Twilio en texto para el agente; null si no se puede atender.
export function textoDeMensaje(campos) {
  const texto = String(campos.Body || "").trim();
  if (campos.Latitude && campos.Longitude) {
    const detalle = [campos.Label, campos.Address].filter(Boolean).join(", ");
    return `[El cliente compartió su ubicación: ${detalle || "sin nombre"} — https://maps.google.com/?q=${campos.Latitude},${campos.Longitude}]`;
  }
  return texto || null;
}

export async function enviarTexto(telefono, texto) {
  if (!twilioConfigurado()) {
    console.log(`[twilio sin configurar] respuesta para ${telefono}:\n${texto}\n`);
    return;
  }
  // WhatsApp por Twilio acepta hasta 1600 caracteres por mensaje.
  for (let i = 0; i < texto.length; i += 1500) {
    await obtenerCliente().messages.create({
      from: process.env.TWILIO_WHATSAPP_FROM,
      to: `whatsapp:+${telefono}`,
      body: texto.slice(i, i + 1500),
    });
  }
}
