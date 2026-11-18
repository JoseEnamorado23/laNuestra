// Acceso a datos de pedidos, conversaciones y configuración, sobre SQLite (ver basedatos.js).
import {
  ejecutar, fila, filas, transaccion, leerAjuste, guardarAjuste, insertarPedido, armarPedidos,
  guardarConversacionFila, filaAConversacion,
} from "./basedatos.js";

export function leerNegocio() {
  return leerAjuste("negocio");
}

export function guardarNegocio(negocio) {
  guardarAjuste("negocio", negocio);
}

// ---------- Pedidos ----------
export const ESTADOS = ["nuevo", "confirmado", "en_camino", "entregado", "cancelado"];

export function listarPedidos({ desde, hasta } = {}) {
  return armarPedidos(filas(`SELECT * FROM pedidos
    WHERE (:desde IS NULL OR creado >= :desde) AND (:hasta IS NULL OR creado < :hasta)
    ORDER BY numero DESC`, { desde, hasta }));
}

export function pedidosDeCliente(telefono) {
  return armarPedidos(filas("SELECT * FROM pedidos WHERE telefono = :telefono ORDER BY numero DESC LIMIT 5", { telefono }));
}

export function buscarPedido(id) {
  const f = fila("SELECT * FROM pedidos WHERE lower(id) = lower(:id)", { id: String(id).trim() });
  return f ? armarPedidos([f])[0] : undefined;
}

export function crearPedido(datos) {
  return transaccion(() => {
    const numero = fila("SELECT COALESCE(MAX(numero), 0) + 1 AS n FROM pedidos").n;
    const pedido = {
      id: "P-" + String(numero).padStart(4, "0"),
      estado: "nuevo",
      creado: new Date().toISOString(),
      ...datos,
    };
    insertarPedido(pedido, numero);
    return buscarPedido(pedido.id);
  });
}

// Guarda estado, historial y la marca de inventario de un pedido.
export function actualizarPedido(id, { estado, historial, inventario_descontado }) {
  ejecutar(`UPDATE pedidos SET estado = :estado, historial = :historial, inventario_descontado = :inv, actualizado = :ahora
    WHERE lower(id) = lower(:id)`, {
    id, estado, historial: JSON.stringify(historial || []), inv: Boolean(inventario_descontado),
    ahora: new Date().toISOString(),
  });
  return buscarPedido(id);
}

export function cambiarEstado(id, estado) {
  const p = buscarPedido(id);
  if (!p) return null;
  return actualizarPedido(p.id, { ...p, estado });
}

// ---------- Conversaciones (historial por teléfono) ----------
const MAX_MENSAJES = 30;

export function leerConversacion(telefono) {
  const f = fila("SELECT * FROM conversaciones WHERE telefono = :telefono", { telefono });
  return f ? filaAConversacion(f) : { nombre: "", mensajes: [], requiere_humano: null };
}

export function guardarConversacion(telefono, conversacion) {
  conversacion.mensajes = conversacion.mensajes.slice(-MAX_MENSAJES);
  // El historial que se envía a Claude debe empezar con un mensaje del cliente.
  while (conversacion.mensajes.length && conversacion.mensajes[0].role !== "user") {
    conversacion.mensajes.shift();
  }
  conversacion.actualizado = new Date().toISOString();
  guardarConversacionFila(telefono, conversacion);
}

export function marcarAtendida(telefono) {
  return ejecutar("UPDATE conversaciones SET requiere_humano = NULL WHERE telefono = :telefono", { telefono }).changes > 0;
}

export function borrarConversacion(telefono) {
  ejecutar("DELETE FROM conversaciones WHERE telefono = :telefono", { telefono });
}

export function listarConversaciones() {
  return filas("SELECT * FROM conversaciones ORDER BY actualizado DESC")
    .map((f) => ({ telefono: f.telefono, ...filaAConversacion(f) }));
}

export function conversacionesConAsesor() {
  return fila("SELECT COUNT(*) AS n FROM conversaciones WHERE requiere_humano IS NOT NULL").n;
}

// ---------- Mensajes ya procesados (Meta y Twilio reintentan los webhooks) ----------
export function yaProcesado(idMensaje) {
  const r = ejecutar("INSERT OR IGNORE INTO mensajes_vistos (id, fecha) VALUES (:id, :fecha)",
    { id: String(idMensaje), fecha: new Date().toISOString() });
  if (r.changes === 0) return true;
  // Limpieza: solo hace falta recordar los últimos días.
  if (Math.random() < 0.02) {
    ejecutar("DELETE FROM mensajes_vistos WHERE fecha < :limite", { limite: new Date(Date.now() - 7 * 864e5).toISOString() });
  }
  return false;
}
