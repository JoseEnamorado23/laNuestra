// Llena la base con datos de muestra para mostrar la app: usuarios, clientes,
// ~5 meses de pedidos, lotes de producción y gastos.
//
//   npm run demo              -> agrega los datos a la base actual (solo una vez)
//   npm run demo -- --reiniciar -> borra la base y empieza de cero con datos de muestra
import fs from "node:fs";

const reiniciar = process.argv.includes("--reiniciar");
if (reiniciar) {
  // Se resuelve la ruta igual que basedatos.js, pero antes de abrir la base.
  const path = await import("node:path");
  const { fileURLToPath } = await import("node:url");
  const raiz = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const archivo = process.env.DB_ARCHIVO || path.join(process.env.DATA_DIR || path.join(raiz, "data"), "lanuestra.db");
  for (const f of [archivo, archivo + "-wal", archivo + "-shm"]) fs.rmSync(f, { force: true });
  console.log("Base anterior borrada:", archivo);
}

const { transaccion, leerAjuste, guardarAjuste } = await import("../src/basedatos.js");
const gestion = await import("../src/gestion.js");

if (leerAjuste("demo_sembrado") && !reiniciar) {
  console.log("Los datos de muestra ya están cargados. Usa  npm run demo -- --reiniciar  para empezar de cero.");
  process.exit(0);
}

// Números pseudoaleatorios repetibles (siempre salen los mismos datos).
let semilla = 20261118;
const azar = () => ((semilla = (semilla * 1103515245 + 12345) % 2147483648) / 2147483648);
const entre = (a, b) => a + Math.floor(azar() * (b - a + 1));
const elegir = (lista) => lista[Math.floor(azar() * lista.length)];

// Fecha en hora de Colombia (UTC-5).
const fechaBogota = (dia, hora, minuto = 0) => new Date(Date.UTC(dia.getUTCFullYear(), dia.getUTCMonth(), dia.getUTCDate(), hora + 5, minuto));

const CLIENTES = [
  ["Tienda Don Pepe", "3001112233", "Cra 5 # 3-20, barrio Centro"],
  ["Panadería La Espiga", "3014445566", "Calle 12 # 8-40, barrio Lourdes"],
  ["Ana Pérez", "3157778899", "Manzana 4 lote 9, El Recreo"],
  ["Colegio San José", "3109990011", "Av. 3 # 15-60, La Granja"],
  ["Miscelánea Mary", "3126543210", "Calle 20 # 4-15, Villa Paz"],
  ["Restaurante El Fogón", "3205556677", "Cra 8 # 22-10, Centro"],
  ["Luis Martínez", "3008889900", "Manzana 12 lote 3, El Níspero"],
  ["Droguería Salud Total", "3112223344", "Calle 7 # 10-02, San Martín"],
  ["Carolina Gómez", "3184441122", "Cra 15 # 30-45, Los Almendros"],
  ["Granero El Progreso", "3046667788", "Calle 3 # 2-50, El Prado"],
  ["Gimnasio Fuerza", "3165559911", "Av. Principal # 40-12"],
  ["Pedro Ramírez", "3023334455", "Manzana 2 lote 14, Villa Rosa"],
];

const hoy = new Date();
const inicio = new Date(Date.UTC(hoy.getUTCFullYear(), hoy.getUTCMonth() - 5, 1));

transaccion(() => {
  // ---- Configuración: costos y algo de información para el agente ----
  const negocio = gestion.leerNegocio();
  const costos = { "paca-bolsa-350ml": 2200, "paca-bolsa-6l": 3800 };
  gestion.guardarNegocio({
    productos: negocio.productos.map((p) => ({
      ...p, costo: p.costo || costos[p.id] || 0,
      stock_minimo: p.id === "paca-bolsa-6l" ? 60 : p.stock_minimo, // para que se vea una alerta
    })),
  });

  // ---- Usuarios (no se tocan los que ya existan) ----
  const existentes = new Set(gestion.listarUsuarios().map((u) => u.usuario));
  const usuarios = [
    { nombre: "Administrador Demo", usuario: "admin", clave: "demo1234", rol: "admin" },
    { nombre: "Carmen Díaz", usuario: "carmen", clave: "demo1234", rol: "despacho" },
    { nombre: "Pedro Ruiz", usuario: "pedro", clave: "demo1234", rol: "repartidor" },
  ];
  for (const u of usuarios) if (!existentes.has(u.usuario)) gestion.crearUsuario(u);
  const carmen = { nombre: "Carmen Díaz" }, pedro = { nombre: "Pedro Ruiz" }, admin = { nombre: "Administrador Demo" };

  // ---- Día por día: producción, pedidos y gastos ----
  let pedidos = 0, lotes = 0, gastos = 0;
  const vendido = { p350: 0, p6l: 0 };
  for (let dia = new Date(inicio); dia <= hoy; dia.setUTCDate(dia.getUTCDate() + 1)) {
    const esHoy = dia.toISOString().slice(0, 10) === hoy.toISOString().slice(0, 10);
    const domingo = dia.getUTCDay() === 0;
    const mesesDesdeInicio = (dia.getUTCFullYear() - inicio.getUTCFullYear()) * 12 + dia.getUTCMonth() - inicio.getUTCMonth();

    // Producción cada 3 días: se repone lo vendido (la paca grande queda algo corta para ver la alerta).
    if (dia.getUTCDate() % 3 === 1 || dia.getTime() === inicio.getTime()) {
      const c350 = dia.getTime() === inicio.getTime() ? 180 : Math.max(30, Math.round(vendido.p350 * 0.99) + entre(0, 10));
      const c6l = dia.getTime() === inicio.getTime() ? 45 : Math.max(5, Math.round(vendido.p6l * 0.95));
      gestion.registrarLote({ producto_id: "paca-bolsa-350ml", cantidad: c350, costo_total: c350 * 2200, nota: elegir(["Turno mañana", "Turno tarde"]), fecha: fechaBogota(dia, 6) }, carmen);
      gestion.registrarLote({ producto_id: "paca-bolsa-6l", cantidad: c6l, costo_total: c6l * 3800, nota: "Turno mañana", fecha: fechaBogota(dia, 6, 30) }, carmen);
      vendido.p350 = 0;
      vendido.p6l = 0;
      lotes += 2;
    }

    // Pedidos: el negocio va creciendo mes a mes.
    if (!domingo) {
      const cantidadPedidos = entre(3, 6) + Math.floor(mesesDesdeInicio * 0.7);
      for (let k = 0; k < cantidadPedidos; k++) {
        const hora = entre(7, 17);
        const fecha = fechaBogota(dia, hora, entre(0, 59));
        if (fecha > hoy) continue;
        const [nombre, telefono, direccion] = elegir(CLIENTES);
        const items = [{ producto_id: "paca-bolsa-350ml", cantidad: elegir([3, 5, 6, 8, 10, 12, 15, 20]) }];
        if (azar() < 0.45) items.push({ producto_id: "paca-bolsa-6l", cantidad: entre(1, 3) });
        vendido.p350 += items[0].cantidad;
        vendido.p6l += items[1]?.cantidad || 0;
        const manual = azar() < 0.3;
        const p = gestion.crearPedido(
          { nombre_cliente: nombre, direccion, items, metodo_pago: azar() < 0.65 ? "efectivo" : "transferencia", notas: "" },
          { telefono, origen: manual ? "manual" : "whatsapp", quien: manual ? carmen : null, fecha },
        );
        pedidos++;
        const despues = (h) => new Date(fecha.getTime() + h * 3600e3);
        if (esHoy && hoy - fecha < 3 * 3600e3) {
          // Los de las últimas horas quedan pendientes, para ver la operación del día.
          const estado = elegir(["nuevo", "nuevo", "confirmado", "en_camino"]);
          if (estado !== "nuevo") gestion.cambiarEstadoPedido(p.id, "confirmado", carmen, despues(0.2));
          if (estado === "en_camino") gestion.cambiarEstadoPedido(p.id, "en_camino", pedro, despues(0.5));
        } else if (azar() < 0.04) {
          gestion.cambiarEstadoPedido(p.id, "cancelado", carmen, despues(0.3));
        } else {
          gestion.cambiarEstadoPedido(p.id, "confirmado", carmen, despues(0.2));
          gestion.cambiarEstadoPedido(p.id, "en_camino", pedro, despues(0.6));
          gestion.cambiarEstadoPedido(p.id, "entregado", pedro, despues(1.4));
        }
      }
    }

    // Gastos fijos y variables.
    const d = dia.getUTCDate();
    const fechaGasto = dia.toISOString().slice(0, 10);
    const gasto = (categoria, descripcion, monto) => { gestion.crearGasto({ fecha: fechaGasto, categoria, descripcion, monto }, admin); gastos++; };
    if (d === 1) gasto("Arriendo", "Arriendo de la planta", 600000);
    if (d === 15 || d === 30) gasto("Nómina", "Pago quincena (operario y repartidor)", 750000);
    if (d === 5) gasto("Servicios (agua, luz)", "Recibo de energía y acueducto", entre(160, 220) * 1000);
    if (d === 8 || d === 22) gasto("Insumos (bolsas, empaques)", "Rollos de bolsa y empaques", entre(90, 130) * 1000);
    if (dia.getUTCDay() === 1) gasto("Transporte y combustible", "Gasolina moto de reparto", entre(35, 55) * 1000);
    if (d === 18 && azar() < 0.5) gasto("Mantenimiento", "Mantenimiento selladora", entre(80, 150) * 1000);
  }

  guardarAjuste("demo_sembrado", { fecha: new Date().toISOString() });
  console.log(`Listo: ${pedidos} pedidos, ${lotes} lotes y ${gastos} gastos desde ${inicio.toISOString().slice(0, 10)}.`);
  console.log("Usuarios de muestra (contraseña demo1234): admin (administrador), carmen (despacho), pedro (repartidor).");
});
