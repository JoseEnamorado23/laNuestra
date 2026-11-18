package co.lanuestra.despacho

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

// ======================= Modelos (mismos campos que el servidor) =======================

data class Usuario(
    val id: String, val usuario: String, val nombre: String, val rol: String,
    val activo: Boolean, val permisos: List<String>,
) {
    fun puede(permiso: String) = permiso in permisos
}

data class Item(val productoId: String, val nombre: String, val cantidad: Int, val precio: Long, val subtotal: Long)

data class Pedido(
    val id: String, val estado: String, val creado: String, val telefono: String,
    val nombreCliente: String, val direccion: String, val items: List<Item>, val total: Long,
    val metodoPago: String, val notas: String, val origen: String, val registradoPor: String,
)

data class Aviso(val telefono: String, val nombre: String, val motivo: String)

data class Producto(
    val id: String, val nombre: String, val precio: Long, val costo: Long,
    val stockMinimo: Int, val disponible: Boolean,
)

data class Negocio(
    val nombre: String, val nombreAgente: String, val horario: String, val zonas: String,
    val tiempoEntrega: String, val datosTransferencia: String, val costoDomicilio: Long,
    val pedidoMinimo: Long, val metodosPago: List<String>, val productos: List<Producto>,
)

data class StockItem(
    val productoId: String, val nombre: String, val disponible: Boolean,
    val stock: Int, val stockMinimo: Int, val alerta: String?,
)

data class Movimiento(
    val fecha: String, val nombre: String, val cantidad: Int, val tipo: String,
    val nota: String, val pedido: String, val por: String,
)

data class Lote(
    val id: String, val fecha: String, val nombre: String, val cantidad: Int,
    val costoTotal: Long?, val nota: String, val por: String,
)

data class Gasto(val id: String, val fecha: String, val categoria: String, val descripcion: String, val monto: Long)

data class Resumen(
    val pedidosHoy: Int, val ventasHoy: Long, val ventasMes: Long, val pedidosMes: Int,
    val pendientes: Map<String, Int>, val alertas: List<StockItem>, val asesor: Int,
)

data class VentaProducto(
    val nombre: String, val unidades: Int, val ventas: Long, val costo: Long, val ganancia: Long, val margen: Int,
)

data class Cliente(val nombre: String, val telefono: String, val pedidos: Int, val total: Long)

data class Reporte(
    val mes: String, val pedidos: Int, val cancelados: Int, val ventas: Long, val costoVentas: Long,
    val gananciaBruta: Long, val gastos: Long, val utilidad: Long, val ticketPromedio: Long, val margen: Int,
    val variacionVentas: Int?, val variacionUtilidad: Int?, val mesAnterior: String,
    val porProducto: List<VentaProducto>, val porDia: List<Long>, val porPago: Map<String, Long>,
    val porOrigen: Map<String, Long>, val gastosPorCategoria: Map<String, Long>,
    val mejoresClientes: List<Cliente>, val produccion: Map<String, Long>, val productosSinCosto: List<String>,
)

data class MesTendencia(val mes: String, val ventas: Long, val utilidad: Long, val pedidos: Int)

data class Sesion(val token: String, val usuario: Usuario)

val ESTADOS = linkedMapOf(
    "nuevo" to "Nuevo",
    "confirmado" to "Confirmado",
    "en_camino" to "En camino",
    "entregado" to "Entregado",
    "cancelado" to "Cancelado",
)

val ROLES = linkedMapOf("admin" to "Administrador", "despacho" to "Despacho", "repartidor" to "Repartidor")

// ======================= Formatos =======================

private val localeCo: Locale = Locale.forLanguageTag("es-CO")
private val zonaCo: ZoneId = ZoneId.of("America/Bogota")

fun pesos(valor: Long): String = (if (valor < 0) "-$" else "$") + NumberFormat.getIntegerInstance(localeCo).format(kotlin.math.abs(valor))

fun pesosCortos(valor: Long): String {
    val abs = kotlin.math.abs(valor)
    val signo = if (valor < 0) "-" else ""
    return when {
        abs >= 1_000_000 -> signo + "$" + String.format(localeCo, "%.1f", abs / 1_000_000.0) + " M"
        abs >= 1_000 -> signo + "$" + (abs / 1_000) + " mil"
        else -> pesos(valor)
    }
}

fun fechaCorta(iso: String): String = try {
    DateTimeFormatter.ofPattern("d MMM, h:mm a", localeCo).format(Instant.parse(iso).atZone(zonaCo))
} catch (e: Exception) {
    try { DateTimeFormatter.ofPattern("d MMM yyyy", localeCo).format(LocalDate.parse(iso)) } catch (e2: Exception) { iso }
}

fun nombreMes(mes: String, corto: Boolean = false): String = try {
    val ym = YearMonth.parse(mes)
    val nombre = ym.month.getDisplayName(if (corto) TextStyle.SHORT else TextStyle.FULL, localeCo)
    nombre.replaceFirstChar { it.uppercase() } + if (corto) "" else " ${ym.year}"
} catch (e: Exception) {
    mes
}

fun mesActual(): String = YearMonth.now(zonaCo).toString()
fun hoy(): String = LocalDate.now(zonaCo).toString()

// ======================= Ajustes guardados =======================

class Ajustes(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v).apply()
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()
    var usuarioJson: String
        get() = prefs.getString("usuario", "") ?: ""
        set(v) = prefs.edit().putString("usuario", v).apply()
}

class ErrorApi(mensaje: String, val codigo: Int = 0) : Exception(mensaje)

// ======================= Cliente HTTP =======================

private fun JSONArray.objetos(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray?.textos(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
private fun JSONObject?.mapaLong(): Map<String, Long> =
    if (this == null) emptyMap() else keys().asSequence().associateWith { optLong(it) }
private fun JSONObject.textoONulo(campo: String): String = if (isNull(campo)) "" else optString(campo)

fun parseUsuario(j: JSONObject) = Usuario(
    id = j.optString("id"), usuario = j.optString("usuario"), nombre = j.optString("nombre"),
    rol = j.optString("rol"), activo = j.optBoolean("activo", true), permisos = j.optJSONArray("permisos").textos(),
)

fun usuarioAJson(u: Usuario): String = JSONObject()
    .put("id", u.id).put("usuario", u.usuario).put("nombre", u.nombre).put("rol", u.rol)
    .put("activo", u.activo).put("permisos", JSONArray(u.permisos)).toString()

private fun parseStock(j: JSONObject) = StockItem(
    productoId = j.optString("producto_id"), nombre = j.optString("nombre"), disponible = j.optBoolean("disponible", true),
    stock = j.optInt("stock"), stockMinimo = j.optInt("stock_minimo"),
    alerta = if (j.isNull("alerta")) null else j.optString("alerta"),
)

private fun parsePedido(p: JSONObject) = Pedido(
    id = p.optString("id"), estado = p.optString("estado"), creado = p.optString("creado"),
    telefono = p.optString("telefono"), nombreCliente = p.optString("nombre_cliente"),
    direccion = p.optString("direccion"),
    items = (p.optJSONArray("items") ?: JSONArray()).objetos().map {
        Item(it.optString("producto_id"), it.optString("nombre"), it.optInt("cantidad"), it.optLong("precio_unitario"), it.optLong("subtotal"))
    },
    total = p.optLong("total"), metodoPago = p.optString("metodo_pago"), notas = p.optString("notas"),
    origen = p.optString("origen", "whatsapp").ifEmpty { "whatsapp" }, registradoPor = p.optString("registrado_por"),
)

class Api(private val base: String, private val token: String) {

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun pedir(metodo: String, ruta: String, cuerpo: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val solicitud = Request.Builder()
                .url(base + ruta)
                // ngrok gratis muestra una página de aviso si no se manda este encabezado.
                .header("ngrok-skip-browser-warning", "true")
                .header("Accept", "application/json")
                .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                .method(metodo, cuerpo?.toString()?.toRequestBody("application/json".toMediaType()))
                .build()
            val respuesta = try {
                http.newCall(solicitud).execute()
            } catch (e: Exception) {
                throw ErrorApi("No se pudo conectar con el servidor. Revisa tu internet y que el servidor y ngrok estén abiertos.")
            }
            respuesta.use { r ->
                val texto = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val mensaje = try { JSONObject(texto).optString("error") } catch (e: Exception) { "" }
                    throw ErrorApi(mensaje.ifEmpty { "El servidor respondió ${r.code}." }, r.code)
                }
                texto
            }
        }

    private suspend fun obj(metodo: String, ruta: String, cuerpo: JSONObject? = null) = JSONObject(pedir(metodo, ruta, cuerpo))
    private suspend fun arr(ruta: String) = JSONArray(pedir("GET", ruta))

    // ---- Sesión ----
    suspend fun inicio(): Pair<String, Boolean> = obj("GET", "/api/inicio").let { it.optString("negocio") to it.optBoolean("necesita_admin") }

    private fun parseSesion(j: JSONObject) = Sesion(j.getString("token"), parseUsuario(j.getJSONObject("usuario")))

    suspend fun login(usuario: String, clave: String) =
        parseSesion(obj("POST", "/api/login", JSONObject().put("usuario", usuario).put("clave", clave)))

    suspend fun primerAdmin(nombre: String, usuario: String, clave: String) =
        parseSesion(obj("POST", "/api/primer-admin", JSONObject().put("nombre", nombre).put("usuario", usuario).put("clave", clave)))

    suspend fun yo() = parseUsuario(obj("GET", "/api/yo"))
    suspend fun logout() { pedir("POST", "/api/logout", JSONObject()) }

    // ---- Inicio ----
    suspend fun resumen(): Resumen {
        val j = obj("GET", "/api/resumen")
        return Resumen(
            pedidosHoy = j.optInt("pedidos_hoy"), ventasHoy = j.optLong("ventas_hoy"),
            ventasMes = j.optLong("ventas_mes"), pedidosMes = j.optInt("pedidos_mes"),
            pendientes = j.optJSONObject("pendientes").mapaLong().mapValues { it.value.toInt() },
            alertas = (j.optJSONArray("alertas_inventario") ?: JSONArray()).objetos().map(::parseStock),
            asesor = j.optInt("asesor"),
        )
    }

    // ---- Pedidos ----
    suspend fun pedidos(): List<Pedido> = arr("/api/pedidos").objetos().map(::parsePedido)

    suspend fun avisos(): List<Aviso> = arr("/api/conversaciones").objetos().mapNotNull { c ->
        val humano = c.optJSONObject("requiere_humano") ?: return@mapNotNull null
        Aviso(c.optString("telefono"), c.optString("nombre"), humano.optString("motivo"))
    }

    suspend fun atenderAviso(telefono: String) { pedir("POST", "/api/conversaciones/$telefono/atendida", JSONObject()) }

    suspend fun cambiarEstado(id: String, estado: String) {
        pedir("PATCH", "/api/pedidos/$id", JSONObject().put("estado", estado))
    }

    suspend fun crearPedido(
        telefono: String, nombre: String, direccion: String, items: Map<String, Int>, metodoPago: String, notas: String,
    ): Pedido {
        val lista = JSONArray()
        items.filterValues { it > 0 }.forEach { (id, n) -> lista.put(JSONObject().put("producto_id", id).put("cantidad", n)) }
        return parsePedido(
            obj(
                "POST", "/api/pedidos",
                JSONObject().put("telefono", telefono).put("nombre_cliente", nombre).put("direccion", direccion)
                    .put("items", lista).put("metodo_pago", metodoPago).put("notas", notas),
            ),
        )
    }

    // ---- Negocio ----
    suspend fun negocio(): Negocio {
        val j = obj("GET", "/api/negocio")
        return Negocio(
            nombre = j.optString("nombre"), nombreAgente = j.optString("nombre_agente"), horario = j.optString("horario"),
            zonas = j.optString("zonas_de_entrega"), tiempoEntrega = j.optString("tiempo_de_entrega"),
            datosTransferencia = j.optString("datos_transferencia"), costoDomicilio = j.optLong("costo_domicilio"),
            pedidoMinimo = j.optLong("pedido_minimo"), metodosPago = j.optJSONArray("metodos_de_pago").textos(),
            productos = (j.optJSONArray("productos") ?: JSONArray()).objetos().map {
                Producto(
                    it.optString("id"), it.optString("nombre"), it.optLong("precio"), it.optLong("costo"),
                    it.optInt("stock_minimo"), it.optBoolean("disponible", true),
                )
            },
        )
    }

    suspend fun guardarNegocio(n: Negocio) {
        val productos = JSONArray()
        n.productos.forEach {
            productos.put(
                JSONObject().put("id", it.id).put("nombre", it.nombre).put("precio", it.precio).put("costo", it.costo)
                    .put("stock_minimo", it.stockMinimo).put("disponible", it.disponible),
            )
        }
        pedir(
            "PUT", "/api/negocio",
            JSONObject().put("nombre", n.nombre).put("nombre_agente", n.nombreAgente).put("horario", n.horario)
                .put("zonas_de_entrega", n.zonas).put("tiempo_de_entrega", n.tiempoEntrega)
                .put("datos_transferencia", n.datosTransferencia).put("costo_domicilio", n.costoDomicilio)
                .put("pedido_minimo", n.pedidoMinimo).put("metodos_de_pago", JSONArray(n.metodosPago))
                .put("productos", productos),
        )
    }

    // ---- Inventario ----
    suspend fun inventario(): Pair<List<StockItem>, List<Movimiento>> {
        val j = obj("GET", "/api/inventario")
        val productos = j.getJSONArray("productos").objetos().map(::parseStock)
        val movimientos = j.getJSONArray("movimientos").objetos().map {
            Movimiento(
                it.optString("fecha"), it.optString("nombre"), it.optInt("cantidad"), it.optString("tipo"),
                it.textoONulo("nota"), it.textoONulo("pedido").ifEmpty { it.textoONulo("lote") }, it.textoONulo("por"),
            )
        }
        return productos to movimientos
    }

    suspend fun lotes(): List<Lote> = arr("/api/lotes").objetos().map {
        Lote(
            it.optString("id"), it.optString("fecha"), it.optString("nombre"), it.optInt("cantidad"),
            if (it.isNull("costo_total")) null else it.optLong("costo_total"), it.optString("nota"), it.optString("registrado_por"),
        )
    }

    suspend fun registrarLote(productoId: String, cantidad: Int, costoTotal: Long?, nota: String) {
        pedir(
            "POST", "/api/lotes",
            JSONObject().put("producto_id", productoId).put("cantidad", cantidad).put("nota", nota)
                .apply { if (costoTotal != null) put("costo_total", costoTotal) },
        )
    }

    suspend fun ajustar(productoId: String, cantidad: Int, nota: String) {
        pedir("POST", "/api/inventario/ajuste", JSONObject().put("producto_id", productoId).put("cantidad", cantidad).put("nota", nota))
    }

    // ---- Contabilidad ----
    suspend fun gastos(mes: String): Pair<List<Gasto>, List<String>> {
        val j = obj("GET", "/api/gastos?mes=$mes")
        return j.getJSONArray("gastos").objetos().map {
            Gasto(it.optString("id"), it.optString("fecha"), it.optString("categoria"), it.optString("descripcion"), it.optLong("monto"))
        } to j.optJSONArray("categorias").textos()
    }

    suspend fun crearGasto(fecha: String, categoria: String, descripcion: String, monto: Long) {
        pedir(
            "POST", "/api/gastos",
            JSONObject().put("fecha", fecha).put("categoria", categoria).put("descripcion", descripcion).put("monto", monto),
        )
    }

    suspend fun borrarGasto(id: String) { pedir("DELETE", "/api/gastos/$id") }

    // ---- Reportes ----
    suspend fun reporte(mes: String): Reporte {
        val j = obj("GET", "/api/reportes/mensual?mes=$mes")
        val comp = j.optJSONObject("comparacion") ?: JSONObject()
        return Reporte(
            mes = j.optString("mes"), pedidos = j.optInt("pedidos"), cancelados = j.optInt("cancelados"),
            ventas = j.optLong("ventas"), costoVentas = j.optLong("costo_ventas"), gananciaBruta = j.optLong("ganancia_bruta"),
            gastos = j.optLong("gastos"), utilidad = j.optLong("utilidad"), ticketPromedio = j.optLong("ticket_promedio"),
            margen = j.optInt("margen"),
            variacionVentas = if (comp.isNull("variacion_ventas")) null else comp.optInt("variacion_ventas"),
            variacionUtilidad = if (comp.isNull("variacion_utilidad")) null else comp.optInt("variacion_utilidad"),
            mesAnterior = comp.optString("mes"),
            porProducto = (j.optJSONArray("por_producto") ?: JSONArray()).objetos().map {
                VentaProducto(it.optString("nombre"), it.optInt("unidades"), it.optLong("ventas"), it.optLong("costo"), it.optLong("ganancia"), it.optInt("margen"))
            },
            porDia = (j.optJSONArray("por_dia") ?: JSONArray()).objetos().map { it.optLong("ventas") },
            porPago = j.optJSONObject("por_pago").mapaLong(),
            porOrigen = j.optJSONObject("por_origen").mapaLong(),
            gastosPorCategoria = j.optJSONObject("gastos_por_categoria").mapaLong(),
            mejoresClientes = (j.optJSONArray("mejores_clientes") ?: JSONArray()).objetos().map {
                Cliente(it.optString("nombre"), it.optString("telefono"), it.optInt("pedidos"), it.optLong("total"))
            },
            produccion = j.optJSONObject("produccion").mapaLong(),
            productosSinCosto = j.optJSONArray("productos_sin_costo").textos(),
        )
    }

    suspend fun tendencia(): List<MesTendencia> = arr("/api/reportes/tendencia").objetos().map {
        MesTendencia(it.optString("mes"), it.optLong("ventas"), it.optLong("utilidad"), it.optInt("pedidos"))
    }

    // ---- Usuarios ----
    suspend fun usuarios(): List<Usuario> = obj("GET", "/api/usuarios").getJSONArray("usuarios").objetos().map(::parseUsuario)

    suspend fun crearUsuario(nombre: String, usuario: String, clave: String, rol: String) {
        pedir("POST", "/api/usuarios", JSONObject().put("nombre", nombre).put("usuario", usuario).put("clave", clave).put("rol", rol))
    }

    suspend fun actualizarUsuario(id: String, cambios: JSONObject) { pedir("PATCH", "/api/usuarios/$id", cambios) }
}
