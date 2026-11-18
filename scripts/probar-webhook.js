// Envía al servidor local un webhook con el mismo formato que usa Meta,
// firmado con WHATSAPP_APP_SECRET si está definido.
// Uso: npm run probar-webhook -- "Hola, quiero 2 botellones"
import crypto from "node:crypto";

const texto = process.argv.slice(2).join(" ") || "Hola, quiero pedir 2 botellones";
const puerto = process.env.PORT || 3000;
const telefono = "573000000001";

const cuerpo = JSON.stringify({
  object: "whatsapp_business_account",
  entry: [{
    id: "PRUEBA",
    changes: [{
      field: "messages",
      value: {
        messaging_product: "whatsapp",
        metadata: { display_phone_number: "15550000000", phone_number_id: "PRUEBA" },
        contacts: [{ profile: { name: "Cliente de Prueba" }, wa_id: telefono }],
        messages: [{
          from: telefono,
          id: "wamid.PRUEBA" + Date.now(),
          timestamp: String(Math.floor(Date.now() / 1000)),
          type: "text",
          text: { body: texto },
        }],
      },
    }],
  }],
});

const cabeceras = { "Content-Type": "application/json" };
if (process.env.WHATSAPP_APP_SECRET) {
  cabeceras["X-Hub-Signature-256"] =
    "sha256=" + crypto.createHmac("sha256", process.env.WHATSAPP_APP_SECRET).update(cuerpo).digest("hex");
}

const r = await fetch(`http://localhost:${puerto}/webhook`, { method: "POST", headers: cabeceras, body: cuerpo });
console.log(`Webhook enviado ("${texto}") -> HTTP ${r.status}`);
console.log("Mira la consola del servidor para ver la respuesta del agente.");
