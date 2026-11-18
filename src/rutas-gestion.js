// Rutas de la API de gestión (app móvil y panel). Todas devuelven JSON.
import crypto from "node:crypto";
import express from "express";
import * as db from "./db.js";
import * as gestion from "./gestion.js";

const { ErrorNegocio } = gestion;

function iguales(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

// Identifica a quien hace la petición:
// - Bearer <token> de /api/login (app móvil)
// - Basic usuario:contraseña de un usuario del sistema (panel web en el navegador)
// - Basic admin:PANEL_CLAVE (acceso de emergencia del dueño)
// Si todavía no hay usuarios ni PANEL_CLAVE, el panel queda abierto como antes.
export function autenticar(req, res, next) {
  const [tipo, credencial = ""] = (req.get("authorization") || "").split(" ");
  if (tipo === "Bearer") {
    req.usuario = gestion.usuarioDeToken(credencial);
    if (req.usuario) return next();
    return res.status(401).json({ error: "Sesión vencida. Vuelve a iniciar sesión." });
  }
  if (tipo === "Basic") {
    const texto = Buffer.from(credencial, "base64").toString();
    const i = texto.indexOf(":");
    const usuario = texto.slice(0, i), clave = texto.slice(i + 1);
    req.usuario = gestion.usuarioDeCredenciales(usuario, clave);
    if (!req.usuario && process.env.PANEL_CLAVE && usuario === "admin" && iguales(clave, process.env.PANEL_CLAVE)) {
      req.usuario = { id: "panel", nombre: "Administrador", usuario: "admin", rol: "admin", permisos: gestion.PERMISOS.admin };
    }
    if (req.usuario) return next();
  }
  if (!process.env.PANEL_CLAVE && !gestion.hayUsuarios()) {
    req.usuario = { id: "abierto", nombre: "Sin usuario", usuario: "", rol: "admin", permisos: gestion.PERMISOS.admin };
    return next();
  }
  res.set("WWW-Authenticate", 'Basic realm="Panel de pedidos"').status(401).json({ error: "Inicia sesión." });
}

export const requiere = (permiso) => (req, res, next) =>
  req.usuario?.permisos?.includes(permiso)
    ? next()
    : res.status(403).json({ error: "Tu usuario no tiene permiso para esto." });

// Rutas que no necesitan sesión (inicio de sesión y primer administrador).
export const publicas = express.Router();

publicas.get("/api/inicio", (_req, res) => {
  res.json({ negocio: db.leerNegocio().nombre, necesita_admin: !gestion.hayUsuarios() });
});

publicas.post("/api/primer-admin", (req, res) => {
  if (gestion.hayUsuarios()) return res.status(409).json({ error: "Ya existe un administrador." });
  const { nombre, usuario, clave } = req.body || {};
  gestion.crearUsuario({ nombre, usuario, clave, rol: "admin" });
  res.json(gestion.iniciarSesion(usuario, clave));
});

publicas.post("/api/login", (req, res) => {
  res.json(gestion.iniciarSesion(req.body?.usuario, req.body?.clave));
});

// Rutas con sesión.
export const privadas = express.Router();

privadas.get("/api/yo", (req, res) => res.json(req.usuario));

privadas.post("/api/logout", (req, res) => {
  const [tipo, token] = (req.get("authorization") || "").split(" ");
  if (tipo === "Bearer") gestion.cerrarSesion(token);
  res.json({ ok: true });
});

privadas.get("/api/resumen", requiere("pedidos"), (_req, res) => res.json(gestion.resumenInicio()));

// ---- Pedidos ----
privadas.post("/api/pedidos", requiere("pedido_manual"), (req, res) => {
  const pedido = gestion.crearPedido(req.body || {}, { origen: "manual", quien: req.usuario });
  res.status(201).json(pedido);
});

privadas.patch("/api/pedidos/:id", requiere("pedidos"), (req, res) => {
  res.json(gestion.cambiarEstadoPedido(req.params.id, req.body?.estado, req.usuario));
});

privadas.post("/api/conversaciones/:telefono/atendida", requiere("pedidos"), (req, res) => {
  if (!db.marcarAtendida(req.params.telefono)) return res.status(404).json({ error: "Conversación no encontrada." });
  res.json({ ok: true });
});

// ---- Configuración del negocio ----
privadas.get("/api/negocio", (_req, res) => res.json(gestion.leerNegocio()));
privadas.put("/api/negocio", requiere("configuracion"), (req, res) => res.json(gestion.guardarNegocio(req.body || {})));

// ---- Inventario y lotes ----
privadas.get("/api/inventario", requiere("inventario"), (_req, res) => {
  res.json({ productos: gestion.estadoInventario(), movimientos: gestion.movimientos(60) });
});
privadas.post("/api/inventario/ajuste", requiere("inventario"), (req, res) => {
  res.json(gestion.ajustarInventario(req.body || {}, req.usuario));
});
privadas.get("/api/lotes", requiere("lotes"), (_req, res) => res.json(gestion.listarLotes()));
privadas.post("/api/lotes", requiere("lotes"), (req, res) => {
  res.status(201).json(gestion.registrarLote(req.body || {}, req.usuario));
});

// ---- Contabilidad ----
privadas.get("/api/gastos", requiere("contabilidad"), (req, res) => {
  res.json({ gastos: gestion.listarGastos(req.query.mes), categorias: gestion.CATEGORIAS_GASTO });
});
privadas.post("/api/gastos", requiere("contabilidad"), (req, res) => {
  res.status(201).json(gestion.crearGasto(req.body || {}, req.usuario));
});
privadas.delete("/api/gastos/:id", requiere("contabilidad"), (req, res) => {
  gestion.borrarGasto(req.params.id);
  res.json({ ok: true });
});

// ---- Reportes ----
privadas.get("/api/reportes/mensual", requiere("reportes"), (req, res) => {
  res.json(gestion.reporteMensual(req.query.mes || undefined));
});
privadas.get("/api/reportes/tendencia", requiere("reportes"), (_req, res) => res.json(gestion.tendencia(6)));

// ---- Usuarios ----
privadas.get("/api/usuarios", requiere("usuarios"), (_req, res) => {
  res.json({ usuarios: gestion.listarUsuarios(), roles: gestion.ROLES });
});
privadas.post("/api/usuarios", requiere("usuarios"), (req, res) => {
  res.status(201).json(gestion.crearUsuario(req.body || {}));
});
privadas.patch("/api/usuarios/:id", requiere("usuarios"), (req, res) => {
  res.json(gestion.actualizarUsuario(req.params.id, req.body || {}, req.usuario));
});

// Convierte los errores de validación en respuestas claras.
export function manejarErrores(error, _req, res, next) {
  if (res.headersSent) return next(error);
  if (error instanceof ErrorNegocio) return res.status(error.status).json({ error: error.message });
  if (error?.type === "entity.parse.failed") return res.status(400).json({ error: "JSON inválido." });
  console.error("Error en la API:", error);
  res.status(500).json({ error: "Error interno del servidor." });
}
