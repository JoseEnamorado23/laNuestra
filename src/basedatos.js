// Base de datos SQLite (módulo node:sqlite incluido en Node 24, sin dependencias nativas).
// Un solo archivo: data/lanuestra.db (o DB_ARCHIVO). Para respaldar basta copiarlo.
import fs from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

export const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const DATA = process.env.DATA_DIR || path.join(raiz, "data");
fs.mkdirSync(DATA, { recursive: true });
export const ARCHIVO_DB = process.env.DB_ARCHIVO || path.join(DATA, "lanuestra.db");

export const base = new DatabaseSync(ARCHIVO_DB);
base.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");

base.exec(`
CREATE TABLE IF NOT EXISTS ajustes (
  clave TEXT PRIMARY KEY,
  valor TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS usuarios (
  id TEXT PRIMARY KEY,
  usuario TEXT NOT NULL UNIQUE,
  nombre TEXT NOT NULL,
  rol TEXT NOT NULL,
  activo INTEGER NOT NULL DEFAULT 1,
  creado TEXT NOT NULL,
  sal TEXT NOT NULL,
  hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sesiones (
  token TEXT PRIMARY KEY,
  usuario_id TEXT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
  expira INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pedidos (
  numero INTEGER PRIMARY KEY,
  id TEXT NOT NULL UNIQUE,
  estado TEXT NOT NULL,
  creado TEXT NOT NULL,
  actualizado TEXT,
  telefono TEXT NOT NULL,
  nombre_cliente TEXT NOT NULL DEFAULT '',
  direccion TEXT NOT NULL DEFAULT '',
  subtotal INTEGER NOT NULL DEFAULT 0,
  domicilio INTEGER NOT NULL DEFAULT 0,
  total INTEGER NOT NULL DEFAULT 0,
  metodo_pago TEXT NOT NULL DEFAULT '',
  notas TEXT NOT NULL DEFAULT '',
  origen TEXT,
  registrado_por TEXT NOT NULL DEFAULT '',
  inventario_descontado INTEGER NOT NULL DEFAULT 0,
  historial TEXT NOT NULL DEFAULT '[]',
  extra TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS pedidos_telefono ON pedidos(telefono);
CREATE INDEX IF NOT EXISTS pedidos_creado ON pedidos(creado);
CREATE INDEX IF NOT EXISTS pedidos_estado ON pedidos(estado);
CREATE TABLE IF NOT EXISTS pedido_items (
  pedido_numero INTEGER NOT NULL REFERENCES pedidos(numero) ON DELETE CASCADE,
  posicion INTEGER NOT NULL,
  producto_id TEXT NOT NULL,
  nombre TEXT NOT NULL,
  cantidad INTEGER NOT NULL,
  precio_unitario INTEGER NOT NULL,
  costo_unitario INTEGER,
  subtotal INTEGER NOT NULL,
  PRIMARY KEY (pedido_numero, posicion)
);
CREATE INDEX IF NOT EXISTS items_producto ON pedido_items(producto_id);
CREATE TABLE IF NOT EXISTS conversaciones (
  telefono TEXT PRIMARY KEY,
  nombre TEXT NOT NULL DEFAULT '',
  requiere_humano TEXT,
  mensajes TEXT NOT NULL DEFAULT '[]',
  actualizado TEXT,
  extra TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS mensajes_vistos (
  id TEXT PRIMARY KEY,
  fecha TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS stock (
  producto_id TEXT PRIMARY KEY,
  cantidad INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS movimientos (
  id TEXT PRIMARY KEY,
  fecha TEXT NOT NULL,
  producto_id TEXT NOT NULL,
  cantidad INTEGER NOT NULL,
  tipo TEXT NOT NULL,
  lote TEXT,
  pedido TEXT,
  nota TEXT NOT NULL DEFAULT '',
  por TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS movimientos_fecha ON movimientos(fecha);
CREATE TABLE IF NOT EXISTS lotes (
  numero INTEGER PRIMARY KEY,
  id TEXT NOT NULL UNIQUE,
  fecha TEXT NOT NULL,
  producto_id TEXT NOT NULL,
  nombre TEXT NOT NULL,
  cantidad INTEGER NOT NULL,
  costo_total INTEGER,
  nota TEXT NOT NULL DEFAULT '',
  registrado_por TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS lotes_fecha ON lotes(fecha);
CREATE TABLE IF NOT EXISTS gastos (
  id TEXT PRIMARY KEY,
  fecha TEXT NOT NULL,
  categoria TEXT NOT NULL,
  descripcion TEXT NOT NULL DEFAULT '',
  monto INTEGER NOT NULL,
  registrado_por TEXT NOT NULL DEFAULT '',
  creado TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS gastos_fecha ON gastos(fecha);
`);

// Atajos. node:sqlite no acepta undefined, booleanos ni claves que la consulta no use,
// así que se pasan solo los parámetros nombrados en el SQL.
const cache = new Map();
function preparar(sql) {
  let s = cache.get(sql);
  if (!s) {
    s = { stmt: base.prepare(sql), nombres: [...new Set([...sql.matchAll(/[:$@]([A-Za-z_]\w*)/g)].map((m) => m[1]))] };
    cache.set(sql, s);
  }
  return s;
}
function parametros(nombres, params = {}) {
  const salida = {};
  for (const k of nombres) {
    const v = params[k];
    salida[k] = v === undefined ? null : typeof v === "boolean" ? (v ? 1 : 0) : v;
  }
  return salida;
}
const usar = (sql, params) => {
  const { stmt, nombres } = preparar(sql);
  return [stmt, parametros(nombres, params)];
};
export const ejecutar = (sql, params) => { const [s, p] = usar(sql, params); return s.run(p); };
export const fila = (sql, params) => { const [s, p] = usar(sql, params); return s.get(p); };
export const filas = (sql, params) => { const [s, p] = usar(sql, params); return s.all(p); };

// Transacción anidable: si algo falla, no queda nada a medias.
let profundidad = 0;
export function transaccion(fn) {
  if (profundidad > 0) return fn();
  profundidad++;
  base.exec("BEGIN IMMEDIATE");
  try {
    const r = fn();
    base.exec("COMMIT");
    return r;
  } catch (e) {
    base.exec("ROLLBACK");
    throw e;
  } finally {
    profundidad--;
  }
}

export const json = (texto, porDefecto) => {
  if (texto === null || texto === undefined) return porDefecto;
  try {
    return JSON.parse(texto);
  } catch {
    return porDefecto;
  }
};

export function leerAjuste(clave) {
  const f = fila("SELECT valor FROM ajustes WHERE clave = :clave", { clave });
  return f ? json(f.valor, null) : null;
}

export function guardarAjuste(clave, valor) {
  ejecutar("INSERT INTO ajustes (clave, valor) VALUES (:clave, :valor) ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor",
    { clave, valor: JSON.stringify(valor) });
}

// ---------- Importación única de los datos antiguos en JSON ----------
function importarJSON() {
  if (leerAjuste("importado_json")) return;
  const leer = (nombre) => {
    try {
      return JSON.parse(fs.readFileSync(path.join(DATA, nombre), "utf8"));
    } catch {
      return null;
    }
  };
  const archivos = ["pedidos.json", "conversaciones.json", "mensajes-vistos.json", "usuarios.json", "sesiones.json",
    "inventario.json", "lotes.json", "gastos.json"];
  const presentes = archivos.filter((a) => fs.existsSync(path.join(DATA, a)));
  transaccion(() => {
    for (const u of leer("usuarios.json") || []) {
      ejecutar(`INSERT OR IGNORE INTO usuarios (id, usuario, nombre, rol, activo, creado, sal, hash)
        VALUES (:id, :usuario, :nombre, :rol, :activo, :creado, :sal, :hash)`, u);
    }
    for (const [token, s] of Object.entries(leer("sesiones.json") || {})) {
      ejecutar("INSERT OR IGNORE INTO sesiones (token, usuario_id, expira) SELECT :token, :u, :expira WHERE EXISTS (SELECT 1 FROM usuarios WHERE id = :u)",
        { token, u: s.usuario, expira: s.expira });
    }
    for (const p of leer("pedidos.json") || []) insertarPedido(p, Number(String(p.id).replace(/\D/g, "")) || null);
    for (const [telefono, c] of Object.entries(leer("conversaciones.json") || {})) guardarConversacionFila(telefono, c);
    for (const id of leer("mensajes-vistos.json") || []) {
      ejecutar("INSERT OR IGNORE INTO mensajes_vistos (id, fecha) VALUES (:id, :fecha)", { id, fecha: new Date().toISOString() });
    }
    const inv = leer("inventario.json");
    if (inv) {
      for (const [producto_id, cantidad] of Object.entries(inv.stock || {})) {
        ejecutar("INSERT OR REPLACE INTO stock (producto_id, cantidad) VALUES (:producto_id, :cantidad)", { producto_id, cantidad });
      }
      for (const m of inv.movimientos || []) {
        ejecutar(`INSERT OR IGNORE INTO movimientos (id, fecha, producto_id, cantidad, tipo, lote, pedido, nota, por)
          VALUES (:id, :fecha, :producto_id, :cantidad, :tipo, :lote, :pedido, :nota, :por)`,
        { lote: null, pedido: null, nota: "", por: "", ...m });
      }
    }
    for (const l of leer("lotes.json") || []) {
      ejecutar(`INSERT OR IGNORE INTO lotes (numero, id, fecha, producto_id, nombre, cantidad, costo_total, nota, registrado_por)
        VALUES (:numero, :id, :fecha, :producto_id, :nombre, :cantidad, :costo_total, :nota, :registrado_por)`,
      { numero: Number(String(l.id).replace(/\D/g, "")) || null, costo_total: null, nota: "", registrado_por: "", ...l });
    }
    for (const g of leer("gastos.json") || []) {
      ejecutar(`INSERT OR IGNORE INTO gastos (id, fecha, categoria, descripcion, monto, registrado_por, creado)
        VALUES (:id, :fecha, :categoria, :descripcion, :monto, :registrado_por, :creado)`,
      { descripcion: "", registrado_por: "", creado: new Date().toISOString(), ...g });
    }
    guardarAjuste("importado_json", { fecha: new Date().toISOString(), archivos: presentes });
  });
  // Los JSON viejos quedan como respaldo, fuera del camino.
  if (presentes.length) {
    const respaldo = path.join(DATA, "respaldo-json");
    fs.mkdirSync(respaldo, { recursive: true });
    for (const a of presentes) fs.renameSync(path.join(DATA, a), path.join(respaldo, a));
    console.log(`Datos JSON importados a SQLite (${presentes.join(", ")}). Copia en ${respaldo}`);
  }
}

// ---------- Pedidos (filas <-> objetos) ----------
const COLUMNAS_PEDIDO = ["id", "estado", "creado", "actualizado", "telefono", "nombre_cliente", "direccion", "subtotal",
  "domicilio", "total", "metodo_pago", "notas", "origen", "registrado_por", "inventario_descontado", "historial", "items", "numero"];

export function insertarPedido(p, numero = null) {
  const extra = Object.fromEntries(Object.entries(p).filter(([k]) => !COLUMNAS_PEDIDO.includes(k)));
  const r = ejecutar(`INSERT INTO pedidos (numero, id, estado, creado, actualizado, telefono, nombre_cliente, direccion,
      subtotal, domicilio, total, metodo_pago, notas, origen, registrado_por, inventario_descontado, historial, extra)
    VALUES (:numero, :id, :estado, :creado, :actualizado, :telefono, :nombre_cliente, :direccion, :subtotal, :domicilio,
      :total, :metodo_pago, :notas, :origen, :registrado_por, :inventario_descontado, :historial, :extra)`, {
    numero, id: p.id, estado: p.estado || "nuevo", creado: p.creado || new Date().toISOString(), actualizado: p.actualizado,
    telefono: p.telefono || "", nombre_cliente: p.nombre_cliente || "", direccion: p.direccion || "",
    subtotal: p.subtotal || 0, domicilio: p.domicilio || 0, total: p.total || 0, metodo_pago: p.metodo_pago || "",
    notas: p.notas || "", origen: p.origen, registrado_por: p.registrado_por || "",
    inventario_descontado: Boolean(p.inventario_descontado), historial: JSON.stringify(p.historial || []),
    extra: JSON.stringify(extra),
  });
  const n = Number(r.lastInsertRowid);
  (p.items || []).forEach((i, posicion) => {
    ejecutar(`INSERT INTO pedido_items (pedido_numero, posicion, producto_id, nombre, cantidad, precio_unitario, costo_unitario, subtotal)
      VALUES (:n, :posicion, :producto_id, :nombre, :cantidad, :precio_unitario, :costo_unitario, :subtotal)`,
    { n, posicion, producto_id: i.producto_id, nombre: i.nombre, cantidad: i.cantidad, precio_unitario: i.precio_unitario,
      costo_unitario: i.costo_unitario, subtotal: i.subtotal });
  });
  return n;
}

// Convierte filas de pedidos en objetos con sus items (misma forma que la API siempre devolvió).
export function armarPedidos(lista) {
  if (!lista.length) return [];
  const numeros = lista.map((p) => p.numero);
  const items = base.prepare(`SELECT * FROM pedido_items WHERE pedido_numero IN (${numeros.map(() => "?").join(",")})
    ORDER BY pedido_numero, posicion`).all(...numeros);
  const porPedido = new Map();
  for (const i of items) {
    const item = {
      producto_id: i.producto_id, nombre: i.nombre, cantidad: i.cantidad,
      precio_unitario: i.precio_unitario, subtotal: i.subtotal,
    };
    if (i.costo_unitario !== null) item.costo_unitario = i.costo_unitario;
    if (!porPedido.has(i.pedido_numero)) porPedido.set(i.pedido_numero, []);
    porPedido.get(i.pedido_numero).push(item);
  }
  return lista.map((f) => {
    const p = {
      ...json(f.extra, {}),
      id: f.id, estado: f.estado, creado: f.creado, telefono: f.telefono, nombre_cliente: f.nombre_cliente,
      direccion: f.direccion, items: porPedido.get(f.numero) || [], subtotal: f.subtotal, domicilio: f.domicilio,
      total: f.total, metodo_pago: f.metodo_pago, notas: f.notas,
    };
    if (f.origen) p.origen = f.origen;
    if (f.registrado_por) p.registrado_por = f.registrado_por;
    p.inventario_descontado = Boolean(f.inventario_descontado);
    const historial = json(f.historial, []);
    if (historial.length) p.historial = historial;
    if (f.actualizado) p.actualizado = f.actualizado;
    return p;
  });
}

// ---------- Conversaciones ----------
export function guardarConversacionFila(telefono, c) {
  const { nombre, requiere_humano, mensajes, actualizado, ...extra } = c;
  ejecutar(`INSERT INTO conversaciones (telefono, nombre, requiere_humano, mensajes, actualizado, extra)
    VALUES (:telefono, :nombre, :requiere_humano, :mensajes, :actualizado, :extra)
    ON CONFLICT(telefono) DO UPDATE SET nombre = excluded.nombre, requiere_humano = excluded.requiere_humano,
      mensajes = excluded.mensajes, actualizado = excluded.actualizado, extra = excluded.extra`, {
    telefono, nombre: nombre || "", requiere_humano: requiere_humano ? JSON.stringify(requiere_humano) : null,
    mensajes: JSON.stringify(mensajes || []), actualizado, extra: JSON.stringify(extra),
  });
}

export function filaAConversacion(f) {
  return {
    ...json(f.extra, {}),
    nombre: f.nombre, mensajes: json(f.mensajes, []), requiere_humano: json(f.requiere_humano, null),
    ...(f.actualizado ? { actualizado: f.actualizado } : {}),
  };
}

// ---------- Configuración del negocio ----------
// La primera vez se toma de negocio.json; después vive en la base y se edita desde la app.
export const ARCHIVO_NEGOCIO = process.env.NEGOCIO_ARCHIVO || path.join(raiz, "negocio.json");
if (!leerAjuste("negocio")) {
  guardarAjuste("negocio", JSON.parse(fs.readFileSync(ARCHIVO_NEGOCIO, "utf8")));
}

importarJSON();
