// Envía al servidor local un webhook con el mismo formato que usa Twilio (formulario),
// firmado con TWILIO_AUTH_TOKEN si está definido, sobre la URL de PUBLIC_URL.
// Uso: npm run probar-twilio -- "Hola, quiero 2 botellones"
//      npm run probar-twilio -- --sin-firma "hola"     (debe dar 403 si hay Auth Token)
import twilio from "twilio";

const argumentos = process.argv.slice(2);
const sinFirma = argumentos.includes("--sin-firma");
const texto = argumentos.filter((a) => a !== "--sin-firma").join(" ") || "Hola, quiero pedir 2 botellones";
const puerto = process.env.PORT || 3000;
const ruta = "/twilio/webhook";
// La firma se calcula sobre la URL que Twilio tiene configurada (la pública),
// aunque la petición se envíe a localhost.
const urlFirmada = (process.env.PUBLIC_URL ? process.env.PUBLIC_URL.replace(/\/+$/, "") : `http://localhost:${puerto}`) + ruta;

const campos = {
  SmsMessageSid: "SMPRUEBA" + Date.now(),
  MessageSid: "SMPRUEBA" + Date.now(),
  AccountSid: process.env.TWILIO_ACCOUNT_SID || "ACPRUEBA",
  From: "whatsapp:+573000000002",
  To: process.env.TWILIO_WHATSAPP_FROM || "whatsapp:+14155238886",
  Body: texto,
  ProfileName: "Cliente Twilio de Prueba",
  WaId: "573000000002",
  NumMedia: "0",
  NumSegments: "1",
  MessageType: "text",
  ApiVersion: "2010-04-01",
};

const cabeceras = { "Content-Type": "application/x-www-form-urlencoded" };
if (process.env.TWILIO_AUTH_TOKEN && !sinFirma) {
  cabeceras["X-Twilio-Signature"] = twilio.getExpectedTwilioSignature(process.env.TWILIO_AUTH_TOKEN, urlFirmada, campos);
}

const r = await fetch(`http://localhost:${puerto}${ruta}`, {
  method: "POST",
  headers: cabeceras,
  body: new URLSearchParams(campos).toString(),
});
console.log(`Webhook Twilio enviado ("${texto}", firmado para ${urlFirmada}) -> HTTP ${r.status} ${await r.text()}`);
console.log("Mira la consola del servidor para ver la respuesta del agente.");
