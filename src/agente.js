// El agente: recibe el texto de un cliente y devuelve la respuesta.
// Claude decide cuándo llamar a las herramientas (crear pedido, consultar, cancelar...).
import Anthropic from "@anthropic-ai/sdk";
import * as db from "./db.js";
import * as gestion from "./gestion.js";

const MODELO = process.env.MODELO || "claude-haiku-5-5";
// Cuánto piensa el modelo antes de responder: low, medium, high, xhigh o max.
// En Haiku "medium" es buen equilibrio: revisa mejor el pedido y sigue siendo barato.
const ESFUERZO = process.env.ESFUERZO || "medium";
// Si el modelo principal rechaza un mensaje por sus filtros de seguridad, la API
// reintenta sola con otro modelo. Solo estos modelos aceptan ese parámetro.
const CON_RESPALDO = /^claude-(opus-5|sonnet-5-5|fable-5)/.test(MODELO);
const MAX_VUELTAS = 8;

let cliente;
function claude() {
  cliente ??= new Anthropic();
  return cliente;
}

export function agenteConfigurado() {
  return Boolean(process.env.ANTHROPIC_API_KEY || process.env.ANTHROPIC_AUTH_TOKEN);
}

function dinero(valor, moneda) {
  return "$" + Number(valor).toLocaleString("es-CO") + " " + moneda;
}

function instrucciones(negocio) {
  const productos = negocio.productos
    .filter((p) => p.disponible)
    .map((p) => `- ${p.nombre} | id: ${p.id} | precio: ${dinero(p.precio, negocio.moneda)}`)
    .join("\n");
  // Fecha y hora de Colombia, para que sepa si el pedido alcanza a salir hoy.
  const hoy = new Date().toLocaleString("es-CO", {
    timeZone: "America/Bogota",
    weekday: "long", year: "numeric", month: "long", day: "numeric",
    hour: "numeric", minute: "2-digit",
  });

  return `Eres ${negocio.nombre_agente}, quien atiende el WhatsApp de ${negocio.nombre}, una empacadora y distribuidora de agua. Los clientes te escriben para pedir agua a domicilio y tu trabajo es tomarles el pedido completo y registrarlo en el sistema, de modo que el equipo de despacho lo vea en su panel y lo entregue.

Datos del negocio
- Horario de atención y entregas: ${negocio.horario}
- Zonas de entrega: ${negocio.zonas_de_entrega}
- Costo del domicilio: ${negocio.costo_domicilio ? dinero(negocio.costo_domicilio, negocio.moneda) : "gratis"}
- Pedido mínimo: ${negocio.pedido_minimo ? dinero(negocio.pedido_minimo, negocio.moneda) : "no hay"}
- Tiempo de entrega: ${negocio.tiempo_de_entrega}
- Métodos de pago: ${negocio.metodos_de_pago.join(", ")}
- Datos para transferencia: ${negocio.datos_transferencia}
- Fecha y hora actual en Colombia: ${hoy}.

Productos disponibles
${productos}

Cómo tomar un pedido
Para registrar un pedido necesitas: qué productos y cuántos, el nombre de quien recibe, la dirección completa de entrega (con barrio y alguna referencia si la dan) y el método de pago. Pide lo que falte de forma natural, sin interrogar: si el cliente ya dio varios datos en un mensaje, no se los vuelvas a pedir. Cuando lo tengas todo, envíale un resumen con los productos, el total y la dirección, y pregúntale si lo confirma. Registra el pedido con crear_pedido solo después de que el cliente confirme ese resumen; un pedido registrado le llega de inmediato al equipo de despacho, así que uno equivocado genera un viaje perdido. Después de registrarlo, dale el número de pedido que te devuelve la herramienta.

Los precios y totales salen únicamente de la lista de productos y de lo que devuelven las herramientas. No ofrezcas descuentos, productos, zonas ni horarios que no estén aquí. Si te preguntan algo que no sabes (facturación, convenios para empresas, reclamos por un pedido ya entregado) o el cliente pide hablar con una persona, usa pedir_asesor_humano y avísale que alguien del equipo le escribirá.

Si el cliente quiere cambiar un pedido que ya registraste, cancélalo con cancelar_pedido y crea uno nuevo con los datos corregidos. Solo se pueden cancelar pedidos que aún no han salido a entrega.

Estilo
Esto es WhatsApp: escribe mensajes cortos, cálidos y directos, en español, como una persona amable del negocio. Sin tablas ni encabezados; para resaltar usa *negrita* de WhatsApp con un solo asterisco. Un emoji de vez en cuando está bien. Atiende solo asuntos del negocio; si te escriben de otra cosa, reconduce con amabilidad hacia el pedido.`;
}

const herramientas = [
  {
    name: "crear_pedido",
    description:
      "Registra un pedido confirmado en el sistema de despacho. Llamar solo cuando el cliente ya confirmó el resumen con productos, total y dirección. Devuelve el número de pedido y el total calculado por el sistema.",
    strict: true,
    input_schema: {
      type: "object",
      properties: {
        nombre_cliente: { type: "string", description: "Nombre de quien recibe el pedido" },
        direccion: { type: "string", description: "Dirección completa de entrega, con barrio y referencias" },
        items: {
          type: "array",
          description: "Productos del pedido",
          items: {
            type: "object",
            properties: {
              producto_id: { type: "string", description: "El id exacto del producto en la lista" },
              cantidad: { type: "integer", description: "Cantidad de unidades, mayor que cero" },
            },
            required: ["producto_id", "cantidad"],
            additionalProperties: false,
          },
        },
        metodo_pago: { type: "string", description: "Exactamente uno de los métodos de pago del negocio, tal como aparecen en la lista" },
        notas: { type: "string", description: "Indicaciones extra del cliente (hora preferida, con cuánto paga, etc.). Vacío si no hay." },
      },
      required: ["nombre_cliente", "direccion", "items", "metodo_pago", "notas"],
      additionalProperties: false,
    },
  },
  {
    name: "consultar_mis_pedidos",
    description: "Devuelve los últimos pedidos de este cliente con su estado actual. Usar cuando pregunte por su pedido o quiera repetir uno anterior.",
    strict: true,
    input_schema: { type: "object", properties: {}, required: [], additionalProperties: false },
  },
  {
    name: "cancelar_pedido",
    description: "Cancela un pedido de este cliente que todavía no ha salido a entrega.",
    strict: true,
    input_schema: {
      type: "object",
      properties: { pedido_id: { type: "string", description: "Número de pedido, por ejemplo P-0012" } },
      required: ["pedido_id"],
      additionalProperties: false,
    },
  },
  {
    name: "pedir_asesor_humano",
    description: "Marca la conversación para que una persona del equipo la atienda. Usar cuando el cliente lo pida o cuando no puedas resolver su solicitud.",
    strict: true,
    input_schema: {
      type: "object",
      properties: { motivo: { type: "string", description: "Qué necesita el cliente, en una frase" } },
      required: ["motivo"],
      additionalProperties: false,
    },
  },
];

// Ejecuta una herramienta. El teléfono viene del canal (WhatsApp), nunca del modelo.
export function ejecutarHerramienta(nombre, entrada, contexto) {
  const { telefono, negocio, conversacion } = contexto;

  if (nombre === "crear_pedido") {
    try {
      const pedido = gestion.crearPedido(entrada, { telefono, origen: "whatsapp" });
      conversacion.nombre = pedido.nombre_cliente;
      return { ok: true, pedido_id: pedido.id, total: pedido.total, moneda: negocio.moneda, items: pedido.items };
    } catch (error) {
      if (error instanceof gestion.ErrorNegocio) return { error: error.message };
      throw error;
    }
  }

  if (nombre === "consultar_mis_pedidos") {
    return {
      pedidos: db.pedidosDeCliente(telefono).map((p) => ({
        pedido_id: p.id, estado: p.estado, creado: p.creado, total: p.total,
        direccion: p.direccion, items: p.items.map((i) => `${i.cantidad} x ${i.nombre}`),
      })),
    };
  }

  if (nombre === "cancelar_pedido") {
    const pedido = db.buscarPedido(entrada.pedido_id);
    if (!pedido || pedido.telefono !== telefono) return { error: "No encontré ese pedido a nombre de este cliente." };
    if (!["nuevo", "confirmado"].includes(pedido.estado)) {
      return { error: `El pedido está en estado "${pedido.estado}" y ya no se puede cancelar.` };
    }
    gestion.cambiarEstadoPedido(pedido.id, "cancelado", { nombre: "Agente WhatsApp" });
    return { ok: true, pedido_id: pedido.id, estado: "cancelado" };
  }

  if (nombre === "pedir_asesor_humano") {
    conversacion.requiere_humano = { motivo: entrada.motivo, desde: new Date().toISOString() };
    return { ok: true, mensaje: "Conversación marcada; un asesor la verá en el panel." };
  }

  return { error: `Herramienta desconocida: ${nombre}` };
}

// Procesa un mensaje del cliente y devuelve el texto de respuesta.
export async function responder({ telefono, texto, nombrePerfil }) {
  const negocio = db.leerNegocio();
  const conversacion = db.leerConversacion(telefono);
  if (!conversacion.nombre && nombrePerfil) conversacion.nombre = nombrePerfil;
  const contexto = { telefono, negocio, conversacion };

  // Entre turnos solo se guarda texto (cliente / agente). Dentro del turno se
  // conserva el contenido completo para que el ciclo de herramientas funcione.
  const mensajes = [...conversacion.mensajes, { role: "user", content: texto }];
  let respuesta = "";

  for (let vuelta = 0; vuelta < MAX_VUELTAS; vuelta++) {
    const r = await claude().beta.messages.create({
      model: MODELO,
      max_tokens: 8000,
      output_config: { effort: ESFUERZO },
      system: [{ type: "text", text: instrucciones(negocio), cache_control: { type: "ephemeral" } }],
      tools: herramientas,
      messages: mensajes,
      ...(CON_RESPALDO ? { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" } : {}),
    });

    if (r.stop_reason === "refusal") {
      respuesta = "Disculpa, con eso no te puedo ayudar por aquí. ¿Te ayudo con un pedido de agua?";
      break;
    }

    const texto_r = r.content.filter((b) => b.type === "text").map((b) => b.text).join("\n").trim();
    const llamadas = r.content.filter((b) => b.type === "tool_use");

    if (r.stop_reason !== "tool_use" || llamadas.length === 0) {
      respuesta = texto_r;
      break;
    }

    mensajes.push({ role: "assistant", content: r.content });
    mensajes.push({
      role: "user",
      content: llamadas.map((llamada) => {
        let resultado;
        try {
          resultado = ejecutarHerramienta(llamada.name, llamada.input, contexto);
        } catch (error) {
          resultado = { error: "Fallo interno al ejecutar la herramienta: " + error.message };
        }
        console.log(`[herramienta] ${llamada.name}`, JSON.stringify(llamada.input), "->", JSON.stringify(resultado));
        return {
          type: "tool_result",
          tool_use_id: llamada.id,
          content: JSON.stringify(resultado),
          is_error: Boolean(resultado.error),
        };
      }),
    });
  }

  if (!respuesta) {
    respuesta = "Tuve un problema procesando tu mensaje. ¿Me lo repites, por favor?";
  }

  conversacion.mensajes = [
    ...conversacion.mensajes,
    { role: "user", content: texto },
    { role: "assistant", content: respuesta },
  ];
  db.guardarConversacion(telefono, conversacion);
  return respuesta;
}

export function mensajeDeError(error) {
  if (error instanceof Anthropic.AuthenticationError) return "La clave ANTHROPIC_API_KEY no es válida.";
  if (error instanceof Anthropic.RateLimitError) return "Límite de uso de la API de Claude alcanzado; intenta en un momento.";
  if (error instanceof Anthropic.APIError) return `Error de la API de Claude (${error.status}): ${error.message}`;
  return error.message;
}
