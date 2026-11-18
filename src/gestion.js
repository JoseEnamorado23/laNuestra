// Gestión del negocio: usuarios, pedidos (con inventario), lotes de producción,
// gastos y reportes. Todo se guarda en SQLite (ver basedatos.js).
import crypto from "node:crypto";
import * as db from "./db.js";
import { ejecutar, fila, filas, transaccion } from "./basedatos.js";

export class ErrorNegocio extends Error {
  constructor(mensaje, status = 400) {
    super(mensaje);
    this.status = status;
  }
}

const nuevoId = (prefijo) => prefijo + "-" + crypto.randomBytes(4).toString("hex");

// Fecha "YYYY-MM-DD" en hora de Colombia.
export function diaBogota(fecha = new Date()) {
  return new Date(fecha).toLocaleDateString("en-CA", { timeZone: "America/Bogota" });
}

// ======================= Usuarios y sesiones =======================

export const ROLES = {
  admin: "Administrador",
  despacho: "Despacho",
  repartidor: "Repartidor",
};

// Qué puede hacer cada rol.
export const PERMISOS = {
  admin: ["pedidos", "pedido_manual", "inventario", "lotes", "reportes", "contabilidad", "configuracion", "usuarios"],
  despacho: ["pedidos", "pedido_manual", "inventario", "lotes"],
  repartidor: ["pedidos"],
};

const DURACION_SESION = 30 * 24 * 3600 * 1000;

function hashClave(clave, sal = crypto.randomBytes(16).toString("hex")) {
  return { sal, hash: crypto.scryptSync(String(clave), sal, 64).toString("hex") };
}

function claveCorrecta(u, clave) {
  const { hash } = hashClave(clave, u.sal);
  return crypto.timingSafeEqual(Buffer.from(hash, "hex"), Buffer.from(u.hash, "hex"));
}

const publico = (u) => ({
  id: u.id, usuario: u.usuario, nombre: u.nombre, rol: u.rol, activo: Boolean(u.activo),
  creado: u.creado, permisos: PERMISOS[u.rol] || [],
});

const usuarioPorId = (id) => fila("SELECT * FROM usuarios WHERE id = :id", { id });
const usuarioPorNombre = (usuario) => fila("SELECT * FROM usuarios WHERE usuario = :usuario", { usuario: String(usuario || "").toLowerCase() });

export function hayUsuarios() {
  return fila("SELECT COUNT(*) AS n FROM usuarios").n > 0;
}

export function listarUsuarios() {
  return filas("SELECT * FROM usuarios ORDER BY creado").map(publico);
}

function validarDatosUsuario({ usuario, nombre, rol, clave }, { nuevo }) {
  if (nuevo || usuario !== undefined) {
    if (!/^[a-z0-9._-]{3,30}$/i.test(String(usuario || ""))) {
      throw new ErrorNegocio("El usuario debe tener entre 3 y 30 letras, números, punto o guion, sin espacios.");
    }
  }
  if ((nuevo || nombre !== undefined) && !String(nombre || "").trim()) throw new ErrorNegocio("Falta el nombre.");
  if ((nuevo || rol !== undefined) && !ROLES[rol]) throw new ErrorNegocio("Rol inválido.");
  if ((nuevo || clave !== undefined) && String(clave || "").length < 6) {
    throw new ErrorNegocio("La contraseña debe tener al menos 6 caracteres.");
  }
}

export function crearUsuario(datos) {
  validarDatosUsuario(datos, { nuevo: true });
  const usuario = String(datos.usuario).toLowerCase();
  if (usuarioPorNombre(usuario)) throw new ErrorNegocio("Ese usuario ya existe.");
  const u = {
    id: nuevoId("U"), usuario, nombre: String(datos.nombre).trim(), rol: datos.rol,
    activo: true, creado: new Date().toISOString(), ...hashClave(datos.clave),
  };
  ejecutar(`INSERT INTO usuarios (id, usuario, nombre, rol, activo, creado, sal, hash)
    VALUES (:id, :usuario, :nombre, :rol, :activo, :creado, :sal, :hash)`, u);
  return publico(u);
}

export function actualizarUsuario(id, datos, quien) {
  const u = usuarioPorId(id);
  if (!u) throw new ErrorNegocio("Usuario no encontrado.", 404);
  validarDatosUsuario(datos, { nuevo: false });
  const adminsActivos = fila("SELECT COUNT(*) AS n FROM usuarios WHERE rol = 'admin' AND activo = 1").n;
  const quedariaSinAdmin = (u.rol === "admin" && ((datos.rol && datos.rol !== "admin") || datos.activo === false)) && adminsActivos <= 1;
  if (quedariaSinAdmin) throw new ErrorNegocio("Debe quedar al menos un administrador activo.");
  if (quien?.id === id && datos.activo === false) throw new ErrorNegocio("No puedes desactivar tu propio usuario.");
  if (datos.nombre !== undefined) u.nombre = String(datos.nombre).trim();
  if (datos.rol !== undefined) u.rol = datos.rol;
  if (datos.activo !== undefined) u.activo = Boolean(datos.activo);
  if (datos.clave) Object.assign(u, hashClave(datos.clave));
  transaccion(() => {
    ejecutar("UPDATE usuarios SET nombre = :nombre, rol = :rol, activo = :activo, sal = :sal, hash = :hash WHERE id = :id", u);
    if (datos.activo === false || datos.clave) ejecutar("DELETE FROM sesiones WHERE usuario_id = :id", { id });
  });
  return publico(u);
}

export function iniciarSesion(usuario, clave) {
  const u = usuarioPorNombre(usuario);
  if (!u || !u.activo || !claveCorrecta(u, clave)) throw new ErrorNegocio("Usuario o contraseña incorrectos.", 401);
  const token = crypto.randomBytes(32).toString("hex");
  ejecutar("INSERT INTO sesiones (token, usuario_id, expira) VALUES (:token, :usuario_id, :expira)",
    { token, usuario_id: u.id, expira: Date.now() + DURACION_SESION });
  ejecutar("DELETE FROM sesiones WHERE expira < :ahora", { ahora: Date.now() });
  return { token, usuario: publico(u) };
}

export function cerrarSesion(token) {
  ejecutar("DELETE FROM sesiones WHERE token = :token", { token });
}

export function usuarioDeToken(token) {
  const u = fila(`SELECT u.* FROM sesiones s JOIN usuarios u ON u.id = s.usuario_id
    WHERE s.token = :token AND s.expira > :ahora AND u.activo = 1`, { token, ahora: Date.now() });
  return u ? publico(u) : null;
}

// Para el panel web (Basic Auth del navegador) con usuario y contraseña.
export function usuarioDeCredenciales(usuario, clave) {
  const u = usuarioPorNombre(usuario);
  return u && u.activo && claveCorrecta(u, clave) ? publico(u) : null;
}

// ======================= Negocio (configuración) =======================

export function leerNegocio() {
  return db.leerNegocio();
}

export function guardarNegocio(datos) {
  const actual = db.leerNegocio();
  const texto = (v, campo) => {
    if (v === undefined) return actual[campo];
    return String(v).trim();
  };
  const numero = (v, campo) => {
    if (v === undefined) return actual[campo];
    const n = Number(v);
    if (!Number.isFinite(n) || n < 0) throw new ErrorNegocio(`Valor inválido en ${campo}.`);
    return Math.round(n);
  };
  const nuevo = {
    ...actual,
    nombre: texto(datos.nombre, "nombre"),
    nombre_agente: texto(datos.nombre_agente, "nombre_agente"),
    horario: texto(datos.horario, "horario"),
    zonas_de_entrega: texto(datos.zonas_de_entrega, "zonas_de_entrega"),
    tiempo_de_entrega: texto(datos.tiempo_de_entrega, "tiempo_de_entrega"),
    datos_transferencia: texto(datos.datos_transferencia, "datos_transferencia"),
    costo_domicilio: numero(datos.costo_domicilio, "costo_domicilio"),
    pedido_minimo: numero(datos.pedido_minimo, "pedido_minimo"),
  };
  if (!nuevo.nombre) throw new ErrorNegocio("El nombre del negocio no puede quedar vacío.");
  if (datos.metodos_de_pago !== undefined) {
    const metodos = [...new Set((datos.metodos_de_pago || []).map((m) => String(m).trim().toLowerCase()).filter(Boolean))];
    if (!metodos.length) throw new ErrorNegocio("Debe haber al menos un método de pago.");
    nuevo.metodos_de_pago = metodos;
  }
  if (datos.productos !== undefined) {
    if (!Array.isArray(datos.productos) || !datos.productos.length) throw new ErrorNegocio("Debe haber al menos un producto.");
    const ids = new Set();
    nuevo.productos = datos.productos.map((p) => {
      const nombre = String(p.nombre || "").trim();
      if (!nombre) throw new ErrorNegocio("Hay un producto sin nombre.");
      let id = String(p.id || "").trim() ||
        nombre.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40);
      while (ids.has(id)) id += "-2";
      ids.add(id);
      const precio = Number(p.precio), costo = Number(p.costo ?? 0), minimo = Number(p.stock_minimo ?? 0);
      if (!Number.isFinite(precio) || precio <= 0) throw new ErrorNegocio(`Precio inválido en "${nombre}".`);
      if (!Number.isFinite(costo) || costo < 0) throw new ErrorNegocio(`Costo inválido en "${nombre}".`);
      if (!Number.isFinite(minimo) || minimo < 0) throw new ErrorNegocio(`Stock mínimo inválido en "${nombre}".`);
      return {
        id, nombre, precio: Math.round(precio), costo: Math.round(costo),
        stock_minimo: Math.round(minimo), disponible: p.disponible !== false,
      };
    });
  }
  db.guardarNegocio(nuevo);
  return nuevo;
}

// ======================= Inventario y lotes =======================

// Suma (o resta) unidades y deja el movimiento registrado.
function mover(productoId, cantidad, tipo, detalle = {}) {
  ejecutar(`INSERT INTO stock (producto_id, cantidad) VALUES (:p, :c)
    ON CONFLICT(producto_id) DO UPDATE SET cantidad = cantidad + excluded.cantidad`, { p: productoId, c: cantidad });
  ejecutar(`INSERT INTO movimientos (id, fecha, producto_id, cantidad, tipo, lote, pedido, nota, por)
    VALUES (:id, :fecha, :producto_id, :cantidad, :tipo, :lote, :pedido, :nota, :por)`, {
    id: nuevoId("M"), fecha: detalle.fecha || new Date().toISOString(), producto_id: productoId, cantidad, tipo,
    lote: detalle.lote, pedido: detalle.pedido, nota: detalle.nota || "", por: detalle.por || "",
  });
}

const stockActual = () => Object.fromEntries(filas("SELECT producto_id, cantidad FROM stock").map((f) => [f.producto_id, f.cantidad]));

export function estadoInventario() {
  const negocio = db.leerNegocio();
  const existencias = stockActual();
  return negocio.productos.map((p) => {
    const stock = existencias[p.id] || 0;
    const minimo = p.stock_minimo || 0;
    return {
      producto_id: p.id, nombre: p.nombre, disponible: p.disponible, stock, stock_minimo: minimo,
      alerta: stock <= 0 ? "agotado" : stock <= minimo ? "bajo" : null,
    };
  });
}

export function alertasInventario() {
  return estadoInventario().filter((p) => p.disponible && p.alerta);
}

export function movimientos(limite = 100) {
  const nombres = Object.fromEntries(db.leerNegocio().productos.map((p) => [p.id, p.nombre]));
  return filas("SELECT * FROM movimientos ORDER BY fecha DESC, rowid DESC LIMIT :limite", { limite })
    .map((m) => {
      const x = { ...m, nombre: nombres[m.producto_id] || m.producto_id };
      for (const k of ["lote", "pedido"]) if (x[k] === null) delete x[k];
      return x;
    });
}

function productoExiste(id) {
  const p = db.leerNegocio().productos.find((x) => x.id === id);
  if (!p) throw new ErrorNegocio("Producto no encontrado.");
  return p;
}

export function registrarLote({ producto_id, cantidad, costo_total, nota, fecha }, quien) {
  const p = productoExiste(producto_id);
  const n = Number(cantidad);
  if (!Number.isInteger(n) || n <= 0 || n > 100000) throw new ErrorNegocio("La cantidad del lote debe ser un número entero mayor que cero.");
  const costo = costo_total === undefined || costo_total === "" ? null : Number(costo_total);
  if (costo !== null && (!Number.isFinite(costo) || costo < 0)) throw new ErrorNegocio("Costo del lote inválido.");
  return transaccion(() => {
    const numero = fila("SELECT COALESCE(MAX(numero), 0) + 1 AS n FROM lotes").n;
    const lote = {
      numero, id: "L-" + String(numero).padStart(4, "0"),
      fecha: fecha ? new Date(fecha).toISOString() : new Date().toISOString(),
      producto_id: p.id, nombre: p.nombre, cantidad: n,
      costo_total: costo === null ? null : Math.round(costo),
      nota: String(nota || "").trim(), registrado_por: quien?.nombre || "",
    };
    ejecutar(`INSERT INTO lotes (numero, id, fecha, producto_id, nombre, cantidad, costo_total, nota, registrado_por)
      VALUES (:numero, :id, :fecha, :producto_id, :nombre, :cantidad, :costo_total, :nota, :registrado_por)`, lote);
    mover(p.id, n, "lote", { lote: lote.id, nota: lote.nota, por: lote.registrado_por, fecha: lote.fecha });
    delete lote.numero;
    return lote;
  });
}

const filaLote = ({ numero, ...l }) => l;

export function listarLotes({ desde, hasta } = {}) {
  return filas(`SELECT * FROM lotes WHERE (:desde IS NULL OR fecha >= :desde) AND (:hasta IS NULL OR fecha < :hasta)
    ORDER BY numero DESC`, { desde, hasta }).map(filaLote);
}

export function ajustarInventario({ producto_id, cantidad, nota }, quien) {
  const p = productoExiste(producto_id);
  const n = Number(cantidad);
  if (!Number.isInteger(n) || n === 0) throw new ErrorNegocio("El ajuste debe ser un número entero distinto de cero.");
  if (!String(nota || "").trim()) throw new ErrorNegocio("Escribe el motivo del ajuste (merma, conteo, daño...).");
  transaccion(() => mover(p.id, n, "ajuste", { nota: String(nota).trim(), por: quien?.nombre || "" }));
  return estadoInventario().find((x) => x.producto_id === p.id);
}

// ======================= Pedidos =======================

// Crea un pedido validado. Lo usan el agente de WhatsApp y el pedido manual de la app.
// Descuenta del inventario; si se cancela, se devuelve.
export function crearPedido(entrada, { telefono, origen, quien, fecha } = {}) {
  const negocio = db.leerNegocio();
  if (!Array.isArray(entrada.items) || entrada.items.length === 0) throw new ErrorNegocio("El pedido no tiene productos.");
  const items = [];
  for (const item of entrada.items) {
    const producto = negocio.productos.find((p) => p.id === item.producto_id && p.disponible);
    if (!producto) throw new ErrorNegocio(`El producto "${item.producto_id}" no existe o no está disponible.`);
    const cantidad = Number(item.cantidad);
    if (!Number.isInteger(cantidad) || cantidad < 1 || cantidad > 500) {
      throw new ErrorNegocio(`Cantidad inválida para ${producto.nombre}.`);
    }
    const repetido = items.find((i) => i.producto_id === producto.id);
    if (repetido) {
      repetido.cantidad += cantidad;
      repetido.subtotal = repetido.precio_unitario * repetido.cantidad;
      continue;
    }
    items.push({
      producto_id: producto.id, nombre: producto.nombre, cantidad,
      precio_unitario: producto.precio, costo_unitario: producto.costo || 0,
      subtotal: producto.precio * cantidad,
    });
  }
  const direccion = String(entrada.direccion || "").trim();
  const nombre = String(entrada.nombre_cliente || "").trim();
  if (!direccion) throw new ErrorNegocio("Falta la dirección de entrega.");
  if (!nombre) throw new ErrorNegocio("Falta el nombre de quien recibe.");
  const metodo = negocio.metodos_de_pago.find((m) => m.toLowerCase() === String(entrada.metodo_pago || "").trim().toLowerCase());
  if (!metodo) throw new ErrorNegocio(`Método de pago no válido. Usa uno de: ${negocio.metodos_de_pago.join(", ")}.`);
  let tel = String(telefono || entrada.telefono || "").replace(/\D/g, "");
  if (/^3\d{9}$/.test(tel)) tel = "57" + tel; // celular colombiano sin indicativo
  if (tel.length < 10) throw new ErrorNegocio("Falta el teléfono del cliente (celular de 10 dígitos).");

  const subtotal = items.reduce((s, i) => s + i.subtotal, 0);
  if (subtotal < (negocio.pedido_minimo || 0)) {
    throw new ErrorNegocio(`El pedido mínimo es de $${negocio.pedido_minimo.toLocaleString("es-CO")}.`);
  }
  const domicilio = negocio.costo_domicilio || 0;
  const creado = fecha ? new Date(fecha).toISOString() : new Date().toISOString();
  return transaccion(() => {
    const pedido = db.crearPedido({
      creado, telefono: tel, nombre_cliente: nombre, direccion, items, subtotal, domicilio,
      total: subtotal + domicilio, metodo_pago: metodo, notas: String(entrada.notas || "").trim(),
      origen: origen || "whatsapp", registrado_por: quien?.nombre || "", inventario_descontado: true,
    });
    for (const i of items) mover(i.producto_id, -i.cantidad, "venta", { pedido: pedido.id, fecha: creado });
    return pedido;
  });
}

export function cambiarEstadoPedido(id, estado, quien, fecha) {
  if (!db.ESTADOS.includes(estado)) throw new ErrorNegocio("Estado inválido.");
  const pedido = db.buscarPedido(id);
  if (!pedido) throw new ErrorNegocio("Pedido no encontrado.", 404);
  const anterior = pedido.estado;
  if (anterior === estado) return pedido;
  const cuando = fecha ? new Date(fecha).toISOString() : new Date().toISOString();
  return transaccion(() => {
    // Cancelar devuelve el producto al inventario; reactivar lo vuelve a descontar.
    if (pedido.inventario_descontado && estado === "cancelado") {
      for (const i of pedido.items) mover(i.producto_id, i.cantidad, "cancelacion", { pedido: pedido.id, fecha: cuando });
      pedido.inventario_descontado = false;
    } else if (anterior === "cancelado" && pedido.origen && !pedido.inventario_descontado) {
      for (const i of pedido.items) mover(i.producto_id, -i.cantidad, "venta", { pedido: pedido.id, fecha: cuando });
      pedido.inventario_descontado = true;
    }
    pedido.historial = [...(pedido.historial || []), { estado, fecha: cuando, por: quien?.nombre || "" }];
    return db.actualizarPedido(pedido.id, { ...pedido, estado });
  });
}

// ======================= Gastos =======================

export const CATEGORIAS_GASTO = [
  "Insumos (bolsas, empaques)", "Nómina", "Transporte y combustible", "Servicios (agua, luz)",
  "Arriendo", "Mantenimiento", "Impuestos", "Otros",
];

export function listarGastos(mes) {
  return filas("SELECT * FROM gastos WHERE (:mes IS NULL OR substr(fecha, 1, 7) = :mes) ORDER BY fecha DESC, creado DESC",
    { mes: mes || null });
}

export function crearGasto({ fecha, categoria, descripcion, monto }, quien) {
  const valor = Number(monto);
  if (!Number.isFinite(valor) || valor <= 0) throw new ErrorNegocio("El monto debe ser mayor que cero.");
  if (!CATEGORIAS_GASTO.includes(categoria)) throw new ErrorNegocio("Categoría inválida.");
  const dia = fecha ? String(fecha).slice(0, 10) : diaBogota();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dia)) throw new ErrorNegocio("Fecha inválida (usa AAAA-MM-DD).");
  const gasto = {
    id: nuevoId("G"), fecha: dia, categoria, descripcion: String(descripcion || "").trim(),
    monto: Math.round(valor), registrado_por: quien?.nombre || "", creado: new Date().toISOString(),
  };
  ejecutar(`INSERT INTO gastos (id, fecha, categoria, descripcion, monto, registrado_por, creado)
    VALUES (:id, :fecha, :categoria, :descripcion, :monto, :registrado_por, :creado)`, gasto);
  return gasto;
}

export function borrarGasto(id) {
  if (ejecutar("DELETE FROM gastos WHERE id = :id", { id }).changes === 0) throw new ErrorNegocio("Gasto no encontrado.", 404);
}

// ======================= Reportes =======================

function costoItem(item, negocio) {
  if (item.costo_unitario !== undefined) return item.costo_unitario * item.cantidad;
  const p = negocio.productos.find((x) => x.id === item.producto_id);
  return (p?.costo || 0) * item.cantidad;
}

function mesAnterior(mes) {
  const [a, m] = mes.split("-").map(Number);
  return m === 1 ? `${a - 1}-12` : `${a}-${String(m - 1).padStart(2, "0")}`;
}

// Rango UTC de un mes en hora de Colombia (UTC-5, sin horario de verano).
function rangoMes(mes) {
  const [a, m] = mes.split("-").map(Number);
  return { desde: new Date(Date.UTC(a, m - 1, 1, 5)).toISOString(), hasta: new Date(Date.UTC(a, m, 1, 5)).toISOString() };
}

function resumenMes(mes, negocio) {
  const pedidos = db.listarPedidos(rangoMes(mes));
  const validos = pedidos.filter((p) => p.estado !== "cancelado");
  const ventas = validos.reduce((s, p) => s + p.total, 0);
  const costo = validos.reduce((s, p) => s + p.items.reduce((c, i) => c + costoItem(i, negocio), 0), 0);
  const gastos = listarGastos(mes).reduce((s, g) => s + g.monto, 0);
  return { mes, pedidos, validos, ventas, costo, gastos, ganancia_bruta: ventas - costo, utilidad: ventas - costo - gastos };
}

export function reporteMensual(mes = diaBogota().slice(0, 7)) {
  if (!/^\d{4}-\d{2}$/.test(mes)) throw new ErrorNegocio("Mes inválido (usa AAAA-MM).");
  const negocio = db.leerNegocio();
  const r = resumenMes(mes, negocio);
  const ant = resumenMes(mesAnterior(mes), negocio);

  const productos = new Map();
  for (const p of r.validos) {
    for (const i of p.items) {
      const x = productos.get(i.producto_id) || { producto_id: i.producto_id, nombre: i.nombre, unidades: 0, ventas: 0, costo: 0 };
      x.unidades += i.cantidad;
      x.ventas += i.subtotal;
      x.costo += costoItem(i, negocio);
      productos.set(i.producto_id, x);
    }
  }
  const porProducto = [...productos.values()]
    .map((x) => ({ ...x, ganancia: x.ventas - x.costo, margen: x.ventas ? Math.round(((x.ventas - x.costo) / x.ventas) * 100) : 0 }))
    .sort((a, b) => b.ventas - a.ventas);

  const [anio, m] = mes.split("-").map(Number);
  const diasMes = new Date(anio, m, 0).getDate();
  const porDia = Array.from({ length: diasMes }, (_, k) => ({ dia: k + 1, ventas: 0, pedidos: 0 }));
  for (const p of r.validos) {
    const d = Number(diaBogota(p.creado).slice(8, 10));
    porDia[d - 1].ventas += p.total;
    porDia[d - 1].pedidos += 1;
  }

  const porEstado = Object.fromEntries(db.ESTADOS.map((e) => [e, r.pedidos.filter((p) => p.estado === e).length]));
  const porPago = {};
  for (const p of r.validos) porPago[p.metodo_pago] = (porPago[p.metodo_pago] || 0) + p.total;
  const porOrigen = {};
  for (const p of r.validos) {
    const o = p.origen || "whatsapp";
    porOrigen[o] = (porOrigen[o] || 0) + 1;
  }
  const gastosPorCategoria = {};
  for (const g of listarGastos(mes)) gastosPorCategoria[g.categoria] = (gastosPorCategoria[g.categoria] || 0) + g.monto;

  const clientes = new Map();
  for (const p of r.validos) {
    const c = clientes.get(p.telefono) || { telefono: p.telefono, nombre: p.nombre_cliente, pedidos: 0, total: 0 };
    c.pedidos += 1;
    c.total += p.total;
    clientes.set(p.telefono, c);
  }

  const producidas = listarLotes(rangoMes(mes));
  const variacion = (a, b) => (b ? Math.round(((a - b) / b) * 100) : null);

  return {
    mes,
    pedidos: r.validos.length,
    cancelados: porEstado.cancelado,
    ventas: r.ventas,
    costo_ventas: r.costo,
    ganancia_bruta: r.ganancia_bruta,
    gastos: r.gastos,
    utilidad: r.utilidad,
    ticket_promedio: r.validos.length ? Math.round(r.ventas / r.validos.length) : 0,
    margen: r.ventas ? Math.round((r.ganancia_bruta / r.ventas) * 100) : 0,
    comparacion: {
      mes: ant.mes,
      ventas: ant.ventas,
      utilidad: ant.utilidad,
      pedidos: ant.validos.length,
      variacion_ventas: variacion(r.ventas, ant.ventas),
      variacion_utilidad: variacion(r.utilidad, ant.utilidad),
    },
    por_producto: porProducto,
    por_dia: porDia,
    por_estado: porEstado,
    por_pago: porPago,
    por_origen: porOrigen,
    gastos_por_categoria: gastosPorCategoria,
    mejores_clientes: [...clientes.values()].sort((a, b) => b.total - a.total).slice(0, 5),
    produccion: producidas.reduce((acc, l) => {
      acc[l.nombre] = (acc[l.nombre] || 0) + l.cantidad;
      return acc;
    }, {}),
    productos_sin_costo: negocio.productos.filter((p) => p.disponible && !p.costo).map((p) => p.nombre),
  };
}

// Tendencia de los últimos meses (para la gráfica).
export function tendencia(meses = 6) {
  const negocio = db.leerNegocio();
  let mes = diaBogota().slice(0, 7);
  const lista = [];
  for (let k = 0; k < meses; k++) {
    const r = resumenMes(mes, negocio);
    lista.unshift({ mes, ventas: r.ventas, utilidad: r.utilidad, pedidos: r.validos.length, gastos: r.gastos });
    mes = mesAnterior(mes);
  }
  return lista;
}

// Resumen para la pantalla de inicio.
export function resumenInicio() {
  const hoy = diaBogota();
  const deHoy = db.listarPedidos(rangoMes(hoy.slice(0, 7))).filter((p) => diaBogota(p.creado) === hoy && p.estado !== "cancelado");
  const mes = resumenMes(hoy.slice(0, 7), db.leerNegocio());
  return {
    hoy,
    pedidos_hoy: deHoy.length,
    ventas_hoy: deHoy.reduce((s, p) => s + p.total, 0),
    ventas_mes: mes.ventas,
    pedidos_mes: mes.validos.length,
    pendientes: Object.fromEntries(["nuevo", "confirmado", "en_camino"].map((e) => [e, fila("SELECT COUNT(*) AS n FROM pedidos WHERE estado = :e", { e }).n])),
    alertas_inventario: alertasInventario(),
    asesor: db.conversacionesConAsesor(),
  };
}
