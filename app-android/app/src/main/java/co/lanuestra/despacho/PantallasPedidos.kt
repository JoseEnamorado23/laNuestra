package co.lanuestra.despacho

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ======================= Lista =======================

@Composable
fun PantallaPedidos(vm: AppViewModel, nav: Navegador) {
    val usuario = vm.usuario ?: return
    var filtro by remember { mutableStateOf("activos") }
    var busqueda by remember { mutableStateOf("") }
    val visibles = vm.pedidos.filter {
        val porEstado = when (filtro) {
            "activos" -> it.estado !in listOf("entregado", "cancelado")
            "todos" -> true
            else -> it.estado == filtro
        }
        val texto = busqueda.trim().lowercase()
        porEstado && (texto.isEmpty() || listOf(it.id, it.nombreCliente, it.direccion, it.telefono).any { c -> c.lowercase().contains(texto) })
    }

    PantallaPestana(
        titulo = "Pedidos",
        subtitulo = "${vm.pedidos.count { it.estado !in listOf("entregado", "cancelado") }} activos",
        acciones = {
            if (vm.actualizando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = vm::refrescar) { Icon(Icons.Filled.Refresh, "Actualizar") }
        },
        flotante = {
            if (usuario.puede("pedido_manual")) {
                ExtendedFloatingActionButton(
                    onClick = { nav.abrir(Destino.NuevoPedido) },
                    icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Nuevo pedido") },
                )
            }
        },
    ) { relleno ->
        Column(Modifier.padding(relleno).fillMaxSize()) {
            OutlinedTextField(
                value = busqueda, onValueChange = { busqueda = it },
                placeholder = { Text("Buscar por cliente, dirección o número") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true, shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (linkedMapOf("activos" to "Activos", "todos" to "Todos") + ESTADOS).forEach { (clave, nombre) ->
                    val cuenta = when (clave) {
                        "activos" -> vm.pedidos.count { it.estado !in listOf("entregado", "cancelado") }
                        "todos" -> vm.pedidos.size
                        else -> vm.pedidos.count { it.estado == clave }
                    }
                    FilterChip(selected = filtro == clave, onClick = { filtro = clave }, label = { Text("$nombre ($cuenta)") })
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                vm.errorConexion?.let { item { Banner(it) } }
                items(vm.avisos, key = { "aviso" + it.telefono }) { aviso -> TarjetaAviso(aviso, vm) }
                if (visibles.isEmpty()) item { EstadoVacio(texto = if (vm.pedidos.isEmpty()) "Todavía no hay pedidos." else "No hay pedidos con este filtro.") }
                items(visibles, key = { it.id }) { p -> FilaPedido(p) { nav.abrir(Destino.DetallePedido(p.id)) } }
            }
        }
    }
}

@Composable
fun TarjetaAviso(aviso: Aviso, vm: AppViewModel) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Ambar.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SupportAgent, null, tint = Ambar)
                Spacer(Modifier.width(10.dp))
                Text("${aviso.nombre.ifEmpty { aviso.telefono }} pide un asesor", fontWeight = FontWeight.Bold)
            }
            Text(aviso.motivo, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { abrirWhatsapp(context, aviso.telefono) }) { Text("Escribirle") }
                TextButton(onClick = { vm.atenderAviso(aviso) }) { Text("Marcar atendido") }
            }
        }
    }
}

@Composable
fun FilaPedido(p: Pedido, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Avatar(p.nombreCliente)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.nombreCliente, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pesos(p.total), fontWeight = FontWeight.Bold)
                }
                Text(p.direccion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.items.joinToString(" · ") { "${it.cantidad} × ${it.nombre}" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipEstado(p.estado)
                    Text("${p.id} · ${fechaCorta(p.creado)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.origen == "manual") Icon(Icons.Filled.EditNote, "Manual", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ======================= Detalle =======================

private val SIGUIENTE = mapOf(
    "nuevo" to ("confirmado" to "Confirmar pedido"),
    "confirmado" to ("en_camino" to "Salió a entrega"),
    "en_camino" to ("entregado" to "Marcar entregado"),
)

@Composable
fun PantallaDetallePedido(vm: AppViewModel, id: String, onVolver: () -> Unit) {
    val context = LocalContext.current
    val p = vm.pedidos.find { it.id == id }
    var confirmarCancelar by remember { mutableStateOf(false) }

    PantallaSecundaria("Pedido $id", onVolver) { relleno ->
        if (p == null) {
            EstadoVacio(texto = "No se encontró el pedido.")
            return@PantallaSecundaria
        }
        Column(
            Modifier.padding(relleno).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipEstado(p.estado)
                Text(fechaCorta(p.creado), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Etiqueta(
                    if (p.origen == "manual") "Manual" else "WhatsApp",
                    MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Paso siguiente, grande y claro.
            SIGUIENTE[p.estado]?.let { (nuevo, texto) ->
                Button(
                    onClick = { vm.cambiarEstado(p, nuevo) },
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text(texto, fontSize = 16.sp)
                }
            }

            Tarjeta {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.nombreCliente)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.nombreCliente, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("+${p.telefono}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Place, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(p.direccion)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonAccion("Mapa", Icons.Filled.Map, Modifier.weight(1f)) { abrirMapa(context, p.direccion) }
                    BotonAccion("Llamar", Icons.Filled.Call, Modifier.weight(1f)) { llamar(context, p.telefono) }
                    BotonAccion("Chat", Icons.AutoMirrored.Filled.Chat, Modifier.weight(1f)) { abrirWhatsapp(context, p.telefono) }
                }
            }

            Tarjeta {
                Text("Productos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                p.items.forEach {
                    Row {
                        Text("${it.cantidad} × ${it.nombre}", Modifier.weight(1f))
                        Text(pesos(it.subtotal))
                    }
                }
                HorizontalDivider()
                Row {
                    Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(pesos(p.total), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text("Pago: ${p.metodoPago.replaceFirstChar { it.uppercase() }}")
                if (p.notas.isNotBlank()) Text("Notas: ${p.notas}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.registradoPor.isNotBlank()) Text("Registrado por ${p.registradoPor}", style = MaterialTheme.typography.labelSmall)
            }

            TituloSeccion("Cambiar estado")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ESTADOS.filterKeys { it != "cancelado" }.forEach { (clave, nombre) ->
                    FilterChip(
                        selected = p.estado == clave,
                        onClick = { if (p.estado != clave) vm.cambiarEstado(p, clave) },
                        label = { Text(nombre) },
                    )
                }
            }
            if (p.estado != "cancelado") {
                OutlinedButton(
                    onClick = { confirmarCancelar = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Cancelar pedido") }
            }
        }
    }

    if (confirmarCancelar && p != null) {
        AlertDialog(
            onDismissRequest = { confirmarCancelar = false },
            title = { Text("¿Cancelar ${p.id}?") },
            text = { Text("El producto vuelve al inventario. Avísale al cliente.") },
            confirmButton = { TextButton(onClick = { vm.cambiarEstado(p, "cancelado"); confirmarCancelar = false }) { Text("Sí, cancelar") } },
            dismissButton = { TextButton(onClick = { confirmarCancelar = false }) { Text("No") } },
        )
    }
}

@Composable
fun BotonAccion(texto: String, icono: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Icon(icono, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(texto, maxLines = 1)
    }
}

// ======================= Nuevo pedido manual =======================

@Composable
fun PantallaNuevoPedido(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "negocio") { it.negocio() }
    var telefono by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var direccion by remember { mutableStateOf("") }
    var notas by remember { mutableStateOf("") }
    var metodo by remember { mutableStateOf("") }
    val cantidades = remember { mutableStateMapOf<String, Int>() }
    var guardando by remember { mutableStateOf(false) }

    PantallaSecundaria("Nuevo pedido", onVolver) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { negocio ->
                if (metodo.isEmpty()) metodo = negocio.metodosPago.firstOrNull() ?: ""
                val disponibles = negocio.productos.filter { it.disponible }
                val subtotal = disponibles.sumOf { (cantidades[it.id] ?: 0) * it.precio }
                val total = subtotal + if (subtotal > 0) negocio.costoDomicilio else 0
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TituloSeccion("Cliente")
                        CampoTexto(telefono, { telefono = it.filter { c -> c.isDigit() }.take(13) }, "Celular", teclado = KeyboardType.Phone, ayuda = "10 dígitos, ej.: 3001234567")
                        CampoTexto(nombre, { nombre = it }, "Nombre de quien recibe")
                        CampoTexto(direccion, { direccion = it }, "Dirección completa, barrio y referencia", lineas = 2)

                        TituloSeccion("Productos")
                        disponibles.forEach { prod ->
                            val n = cantidades[prod.id] ?: 0
                            Tarjeta {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(prod.nombre, fontWeight = FontWeight.SemiBold)
                                        Text(pesos(prod.precio), color = MaterialTheme.colorScheme.primary)
                                    }
                                    FilledTonalIconButton(onClick = { cantidades[prod.id] = (n - 1).coerceAtLeast(0) }, enabled = n > 0) {
                                        Icon(Icons.Filled.Remove, "Menos")
                                    }
                                    Text("$n", Modifier.widthIn(min = 36.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    FilledIconButton(onClick = { cantidades[prod.id] = n + 1 }) { Icon(Icons.Filled.Add, "Más") }
                                }
                            }
                        }

                        TituloSeccion("Pago")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            negocio.metodosPago.forEach {
                                FilterChip(selected = metodo == it, onClick = { metodo = it }, label = { Text(it.replaceFirstChar { c -> c.uppercase() }) })
                            }
                        }
                        CampoTexto(notas, { notas = it }, "Notas (opcional)", ayuda = "Hora preferida, con cuánto paga, etc.")
                    }
                    // Resumen fijo abajo.
                    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Total", style = MaterialTheme.typography.labelMedium)
                                Text(pesos(total), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (negocio.costoDomicilio > 0) Text("Incluye domicilio ${pesos(negocio.costoDomicilio)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = {
                                    guardando = true
                                    vm.ejecutar("Pedido registrado", { ok -> guardando = false; if (ok) onVolver() }) {
                                        it.crearPedido(telefono, nombre, direccion, cantidades.toMap(), metodo, notas)
                                    }
                                },
                                enabled = !guardando && subtotal > 0 && telefono.length >= 10 && nombre.isNotBlank() && direccion.isNotBlank(),
                                shape = RoundedCornerShape(16.dp), modifier = Modifier.height(52.dp),
                            ) { Text("Registrar pedido") }
                        }
                    }
                }
            }
        }
    }
}
