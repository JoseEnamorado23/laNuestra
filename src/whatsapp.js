// Cliente mínimo de la WhatsApp Cloud API de Meta.
import crypto from "node:crypto";

const VERSION = process.env.GRAPH_VERSION || "v23.0";

export function whatsappConfigurado() {
  return Boolean(process.env.WHATSAPP_TOKEN && process.env.WHATSAPP_PHONE_NUMBER_ID);
}

async function llamar(cuerpo) {
  const url = `https://graph.facebook.com/${VERSION}/${process.env.WHATSAPP_PHONE_NUMBER_ID}/messages`;
  const r = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.WHATSAPP_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ messaging_product: "whatsapp", ...cuerpo }),
  });
  if (!r.ok) {
    throw new Error(`WhatsApp API ${r.status}: ${await r.text()}`);
  }
  return r.json();
}

export async function enviarTexto(telefono, texto) {
  if (!whatsappConfigurado()) {
    // Modo prueba: sin credenciales de Meta, la respuesta solo se muestra en consola.
    console.log(`[whatsapp sin configurar] respuesta para ${telefono}:\n${texto}\n`);
    return;
  }
  // WhatsApp acepta hasta 4096 caracteres por mensaje.
  for (let i = 0; i < texto.length; i += 4000) {
    await llamar({ to: telefono, type: "text", text: { body: texto.slice(i, i + 4000) } });
  }
}

// Marca el mensaje como leído y muestra "escribiendo..." mientras el agente piensa.
export async function marcarLeido(idMensaje) {
  if (!whatsappConfigurado()) return;
  try {
    await llamar({ status: "read", message_id: idMensaje, typing_indicator: { type: "text" } });
  } catch (error) {
    console.warn("No se pudo marcar como leído:", error.message);
  }
}

// Meta firma cada webhook con la clave secreta de la app (cabecera X-Hub-Signature-256).
export function firmaValida(cuerpoCrudo, cabecera) {
  const secreto = process.env.WHATSAPP_APP_SECRET;
  if (!secreto) return true; // sin secreto configurado no se verifica (solo para pruebas)
  if (!cabecera || !cuerpoCrudo) return false;
  const esperada = "sha256=" + crypto.createHmac("sha256", secreto).update(cuerpoCrudo).digest("hex");
  const a = Buffer.from(esperada);
  const b = Buffer.from(cabecera);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

// Convierte un mensaje entrante de WhatsApp en texto para el agente.
// Devuelve null si el tipo de mensaje no se puede atender.
export function textoDeMensaje(mensaje) {
  switch (mensaje.type) {
    case "text":
      return mensaje.text?.body || null;
    case "button":
      return mensaje.button?.text || null;
    case "interactive":
      return mensaje.interactive?.button_reply?.title || mensaje.interactive?.list_reply?.title || null;
    case "location": {
      const u = mensaje.location || {};
      const detalle = [u.name, u.address].filter(Boolean).join(", ");
      return `[El cliente compartió su ubicación: ${detalle || "sin nombre"} — https://maps.google.com/?q=${u.latitude},${u.longitude}]`;
    }
    default:
      return null;
  }
}
