package co.lanuestra.despacho

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.YearMonth

// ======================= Menú =======================

@Composable
fun PantallaMas(vm: AppViewModel, nav: Navegador) {
    val u = vm.usuario ?: return
    var confirmarSalir by remember { mutableStateOf(false) }
    PantallaPestana("Más") { relleno ->
        LazyColumn(Modifier.padding(relleno), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Tarjeta {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.nombre)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(u.nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("@${u.usuario} · ${ROLES[u.rol] ?: u.rol}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (u.puede("contabilidad")) item { OpcionMenu(Icons.Filled.AccountBalanceWallet, "Contabilidad", "Gastos del mes y balance") { nav.abrir(Destino.Contabilidad) } }
            if (u.puede("configuracion")) {
                item { OpcionMenu(Icons.Filled.Category, "Productos y precios", "Presentaciones, precio, costo y stock mínimo") { nav.abrir(Destino.Productos) } }
                item { OpcionMenu(Icons.Filled.Storefront, "Configuración del negocio", "Horarios, zonas, domicilio y pagos") { nav.abrir(Destino.Configuracion) } }
            }
            if (u.puede("usuarios")) item { OpcionMenu(Icons.Filled.Group, "Usuarios", "Quién entra a la app y qué puede hacer") { nav.abrir(Destino.Usuarios) } }
            if (u.puede("inventario")) item { OpcionMenu(Icons.Filled.History, "Movimientos de inventario", "Producción, ventas y ajustes") { nav.abrir(Destino.Movimientos) } }
            item { OpcionMenu(Icons.Filled.Dns, "Servidor", vm.url.removePrefix("https://")) {} }
            item { OpcionMenu(Icons.AutoMirrored.Filled.Logout, "Cerrar sesión", null) { confirmarSalir = true } }
            item {
                Text("La Nuestra Despacho · versión 2.1", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(8.dp))
            }
        }
    }
    if (confirmarSalir) {
        AlertDialog(
            onDismissRequest = { confirmarSalir = false },
            title = { Text("¿Cerrar sesión?") },
            confirmButton = { TextButton(onClick = { confirmarSalir = false; vm.cerrarSesion() }) { Text("Cerrar sesión") } },
            dismissButton = { TextButton(onClick = { confirmarSalir = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun OpcionMenu(icono: ImageVector, titulo: String, detalle: String?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        ListItem(
            headlineContent = { Text(titulo, fontWeight = FontWeight.SemiBold) },
            supportingContent = detalle?.let { { Text(it, maxLines = 1) } },
            leadingContent = { Icon(icono, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}

// ======================= Contabilidad =======================

@Composable
fun PantallaContabilidad(vm: AppViewModel, onVolver: () -> Unit) {
    var mes by remember { mutableStateOf(mesActual()) }
    val carga = rememberCarga(vm, mes) { api -> api.gastos(mes) to api.reporte(mes) }
    var nuevo by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf<Gasto?>(null) }

    PantallaSecundaria(
        "Contabilidad", onVolver,
        flotante = { ExtendedFloatingActionButton(onClick = { nuevo = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Registrar gasto") }) },
    ) { relleno ->
        Column(Modifier.padding(relleno)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { mes = YearMonth.parse(mes).minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior") }
                Text(nombreMes(mes), Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { mes = YearMonth.parse(mes).plusMonths(1).toString() }, enabled = mes < mesActual()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Siguiente")
                }
            }
            ConCarga(carga) { (datos, r) ->
                val (gastos, categorias) = datos
                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Kpi("Ingresos", pesosCortos(r.ventas), Modifier.weight(1f), Icons.Filled.ArrowDownward, colorDetalle = VerdeOk, detalle = "Ventas")
                            Kpi("Egresos", pesosCortos(r.gastos + r.costoVentas), Modifier.weight(1f), Icons.Filled.ArrowUpward,
                                detalle = "Gastos + costo", colorDetalle = RojoAlerta)
                        }
                    }
                    item {
                        Kpi(
                            "Balance del mes", pesos(r.utilidad), Modifier.fillMaxWidth(), Icons.Filled.Balance, destacado = true,
                            detalle = if (r.utilidad >= 0) "Ganancia" else "Pérdida",
                        )
                    }
                    item { TituloSeccion("Gastos (${gastos.size})") }
                    if (gastos.isEmpty()) item { EstadoVacio(Icons.Filled.ReceiptLong, "No hay gastos registrados este mes.") }
                    items(gastos, key = { it.id }) { g ->
                        Tarjeta {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.categoria, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(fechaCorta(g.fecha), g.descripcion).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(pesos(g.monto), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { borrar = g }) { Icon(Icons.Filled.DeleteOutline, "Borrar") }
                            }
                        }
                    }
                }
                if (nuevo) {
                    DialogoGasto(categorias, onCerrar = { nuevo = false }) { fecha, cat, desc, monto ->
                        vm.ejecutar("Gasto registrado", { ok -> if (ok) { nuevo = false; carga.recargar() } }) { it.crearGasto(fecha, cat, desc, monto) }
                    }
                }
            }
        }
    }
    borrar?.let { g ->
        AlertDialog(
            onDismissRequest = { borrar = null },
            title = { Text("¿Borrar este gasto?") },
            text = { Text("${g.categoria}: ${pesos(g.monto)}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.ejecutar("Gasto borrado", { ok -> borrar = null; if (ok) carga.recargar() }) { it.borrarGasto(g.id) }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { borrar = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DialogoGasto(categorias: List<String>, onCerrar: () -> Unit, onGuardar: (String, String, String, Long) -> Unit) {
    var categoria by remember { mutableStateOf(categorias.firstOrNull() ?: "") }
    var descripcion by remember { mutableStateOf("") }
    var monto by remember { mutableStateOf("") }
    var fecha by remember { mutableStateOf(hoy()) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Registrar gasto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Selector("Categoría", categorias.map { it to it }, categoria) { categoria = it }
                CampoDinero(monto, { monto = it }, "Monto")
                CampoTexto(descripcion, { descripcion = it }, "Descripción")
                CampoTexto(fecha, { fecha = it.take(10) }, "Fecha (AAAA-MM-DD)")
            }
        },
        confirmButton = {
            TextButton(onClick = { onGuardar(fecha, categoria, descripcion, monto.toLong()) }, enabled = (monto.toLongOrNull() ?: 0) > 0) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

// ======================= Configuración del negocio =======================

@Composable
fun PantallaConfiguracion(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "negocio") { it.negocio() }
    PantallaSecundaria("Configuración del negocio", onVolver) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { n ->
                var nombre by remember { mutableStateOf(n.nombre) }
                var agente by remember { mutableStateOf(n.nombreAgente) }
                var horario by remember { mutableStateOf(n.horario) }
                var tiempo by remember { mutableStateOf(n.tiempoEntrega) }
                var zonas by remember { mutableStateOf(n.zonas) }
                var domicilio by remember { mutableStateOf(n.costoDomicilio.toString()) }
                var minimo by remember { mutableStateOf(n.pedidoMinimo.toString()) }
                var transferencia by remember { mutableStateOf(n.datosTransferencia) }
                var pagos by remember { mutableStateOf(n.metodosPago.joinToString(", ")) }
                var guardando by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Banner(
                        "Estos datos los usa el agente de WhatsApp para responder. Los cambios aplican desde el siguiente mensaje.",
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TituloSeccion("Negocio")
                    CampoTexto(nombre, { nombre = it }, "Nombre del negocio")
                    CampoTexto(agente, { agente = it }, "Nombre del asistente en WhatsApp")
                    TituloSeccion("Horarios y entregas")
                    CampoTexto(horario, { horario = it }, "Horario de atención y entregas", lineas = 2, ayuda = "Ej.: Lunes a sábado de 7:00 a.m. a 6:00 p.m.")
                    CampoTexto(tiempo, { tiempo = it }, "Tiempo de entrega", lineas = 2, ayuda = "Ej.: el mismo día si piden antes de las 3:00 p.m.")
                    CampoTexto(zonas, { zonas = it }, "Zonas o barrios de entrega", lineas = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CampoDinero(domicilio, { domicilio = it }, "Domicilio", Modifier.weight(1f), ayuda = "0 = gratis")
                        CampoDinero(minimo, { minimo = it }, "Pedido mínimo", Modifier.weight(1f), ayuda = "0 = sin mínimo")
                    }
                    TituloSeccion("Pagos")
                    CampoTexto(pagos, { pagos = it }, "Métodos de pago", ayuda = "Separados por coma: efectivo, transferencia, nequi")
                    CampoTexto(transferencia, { transferencia = it }, "Datos para transferencia", lineas = 2)
                    Button(
                        onClick = {
                            guardando = true
                            val nuevo = n.copy(
                                nombre = nombre, nombreAgente = agente, horario = horario, tiempoEntrega = tiempo, zonas = zonas,
                                costoDomicilio = domicilio.toLongOrNull() ?: 0, pedidoMinimo = minimo.toLongOrNull() ?: 0,
                                datosTransferencia = transferencia,
                                metodosPago = pagos.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                            )
                            vm.ejecutar("Configuración guardada", { ok -> guardando = false; if (ok) onVolver() }) { it.guardarNegocio(nuevo) }
                        },
                        enabled = !guardando && nombre.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    ) { Text("Guardar cambios") }
                }
            }
        }
    }
}

// ======================= Productos =======================

@Composable
fun PantallaProductos(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "negocio") { it.negocio() }
    var editando by remember { mutableStateOf<Pair<Int, Producto>?>(null) }

    PantallaSecundaria(
        "Productos y precios", onVolver,
        flotante = {
            if (carga.datos != null) {
                ExtendedFloatingActionButton(
                    onClick = { editando = -1 to Producto("", "", 0, 0, 20, true) },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nueva presentación") },
                )
            }
        },
    ) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { n ->
                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            "El precio lo ve el cliente; el costo es lo que te cuesta producir una unidad y sirve para calcular la ganancia.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(n.productos.size) { i ->
                        val p = n.productos[i]
                        val margen = if (p.precio > 0) ((p.precio - p.costo) * 100 / p.precio) else 0
                        Card(
                            Modifier.fillMaxWidth().clickable { editando = i to p }, shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.nombre, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    if (p.disponible) Etiqueta("A la venta", colorEstado("entregado").first, colorEstado("entregado").second)
                                    else Etiqueta("Oculto", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Dato("Precio", pesos(p.precio))
                                    Dato("Costo", if (p.costo > 0) pesos(p.costo) else "Sin definir")
                                    Dato("Margen", if (p.costo > 0) "$margen%" else "—")
                                    Dato("Mínimo", "${p.stockMinimo}")
                                }
                            }
                        }
                    }
                }
                editando?.let { (indice, producto) ->
                    DialogoProducto(producto, nuevo = indice < 0, onCerrar = { editando = null }) { editado ->
                        val lista = n.productos.toMutableList()
                        if (indice < 0) lista.add(editado) else lista[indice] = editado
                        vm.ejecutar("Producto guardado", { ok -> if (ok) { editando = null; carga.recargar() } }) {
                            it.guardarNegocio(n.copy(productos = lista))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dato(titulo: String, valor: String) {
    Column {
        Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun DialogoProducto(p: Producto, nuevo: Boolean, onCerrar: () -> Unit, onGuardar: (Producto) -> Unit) {
    var nombre by remember { mutableStateOf(p.nombre) }
    var precio by remember { mutableStateOf(if (p.precio > 0) p.precio.toString() else "") }
    var costo by remember { mutableStateOf(if (p.costo > 0) p.costo.toString() else "") }
    var minimo by remember { mutableStateOf(p.stockMinimo.toString()) }
    var disponible by remember { mutableStateOf(p.disponible) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (nuevo) "Nueva presentación" else "Editar presentación") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoTexto(nombre, { nombre = it }, "Nombre", ayuda = "Ej.: Paca de bolsa 350 ml x 30")
                CampoDinero(precio, { precio = it }, "Precio de venta")
                CampoDinero(costo, { costo = it }, "Costo por unidad")
                CampoTexto(minimo, { minimo = it.filter(Char::isDigit).take(6) }, "Avisar cuando queden menos de", teclado = KeyboardType.Number)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A la venta", Modifier.weight(1f))
                    Switch(checked = disponible, onCheckedChange = { disponible = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onGuardar(p.copy(nombre = nombre.trim(), precio = precio.toLongOrNull() ?: 0, costo = costo.toLongOrNull() ?: 0,
                        stockMinimo = minimo.toIntOrNull() ?: 0, disponible = disponible))
                },
                enabled = nombre.isNotBlank() && (precio.toLongOrNull() ?: 0) > 0,
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

// ======================= Usuarios =======================

@Composable
fun PantallaUsuarios(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "usuarios") { it.usuarios() }
    var nuevo by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<Usuario?>(null) }

    PantallaSecundaria(
        "Usuarios", onVolver,
        flotante = { ExtendedFloatingActionButton(onClick = { nuevo = true }, icon = { Icon(Icons.Filled.PersonAdd, null) }, text = { Text("Agregar") }) },
    ) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { lista ->
                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Tarjeta {
                            Text("Roles", fontWeight = FontWeight.Bold)
                            Text("• Administrador: todo, incluidos reportes, contabilidad, configuración y usuarios.", style = MaterialTheme.typography.bodySmall)
                            Text("• Despacho: pedidos, pedidos manuales, inventario y lotes.", style = MaterialTheme.typography.bodySmall)
                            Text("• Repartidor: ver pedidos y cambiar su estado.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(lista, key = { it.id }) { u ->
                        Card(
                            Modifier.fillMaxWidth().clickable { editando = u }, shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(u.nombre)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.nombre, fontWeight = FontWeight.SemiBold)
                                    Text("@${u.usuario}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (u.activo) Etiqueta(ROLES[u.rol] ?: u.rol, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                                else Etiqueta("Inactivo", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }
        }
    }

    if (nuevo) {
        DialogoUsuario(null, onCerrar = { nuevo = false }) { nombre, usuario, clave, rol, _ ->
            vm.ejecutar("Usuario creado", { ok -> if (ok) { nuevo = false; carga.recargar() } }) { it.crearUsuario(nombre, usuario, clave, rol) }
        }
    }
    editando?.let { u ->
        DialogoUsuario(u, onCerrar = { editando = null }) { nombre, _, clave, rol, activo ->
            val cambios = JSONObject().put("nombre", nombre).put("rol", rol).put("activo", activo)
            if (clave.isNotEmpty()) cambios.put("clave", clave)
            vm.ejecutar("Usuario actualizado", { ok -> if (ok) { editando = null; carga.recargar() } }) { it.actualizarUsuario(u.id, cambios) }
        }
    }
}

@Composable
private fun DialogoUsuario(u: Usuario?, onCerrar: () -> Unit, onGuardar: (String, String, String, String, Boolean) -> Unit) {
    var nombre by remember { mutableStateOf(u?.nombre ?: "") }
    var usuario by remember { mutableStateOf(u?.usuario ?: "") }
    var clave by remember { mutableStateOf("") }
    var rol by remember { mutableStateOf(u?.rol ?: "despacho") }
    var activo by remember { mutableStateOf(u?.activo ?: true) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (u == null) "Nuevo usuario" else "Editar ${u.nombre}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoTexto(nombre, { nombre = it }, "Nombre completo")
                if (u == null) CampoTexto(usuario, { usuario = it.trim().lowercase() }, "Usuario", ayuda = "Sin espacios, ej.: pedro.r")
                OutlinedTextField(
                    value = clave, onValueChange = { clave = it },
                    label = { Text(if (u == null) "Contraseña" else "Nueva contraseña (opcional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    supportingText = { Text("Mínimo 6 caracteres") },
                )
                Selector("Rol", ROLES.toList(), rol) { rol = it }
                if (u != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activo", Modifier.weight(1f))
                        Switch(checked = activo, onCheckedChange = { activo = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGuardar(nombre, usuario, clave, rol, activo) },
                enabled = nombre.isNotBlank() && (u != null || (usuario.length >= 3 && clave.length >= 6)) && (clave.isEmpty() || clave.length >= 6),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
