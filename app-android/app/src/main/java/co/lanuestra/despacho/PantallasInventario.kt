package co.lanuestra.despacho

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PantallaInventario(vm: AppViewModel, nav: Navegador) {
    val usuario = vm.usuario ?: return
    // Se recarga cuando cambian los pedidos (una venta descuenta inventario).
    val carga = rememberCarga(vm, vm.pedidos.size, vm.pedidos.count { it.estado == "cancelado" }) { api ->
        api.inventario() to (if (usuario.puede("lotes")) api.lotes() else emptyList())
    }
    var ajustando by remember { mutableStateOf<StockItem?>(null) }

    PantallaPestana(
        titulo = "Inventario",
        subtitulo = "Existencias y producción",
        acciones = {
            IconButton(onClick = { nav.abrir(Destino.Movimientos) }) { Icon(Icons.AutoMirrored.Filled.List, "Movimientos") }
            IconButton(onClick = carga.recargar) { Icon(Icons.Filled.Refresh, "Actualizar") }
        },
        flotante = {
            if (usuario.puede("lotes")) {
                ExtendedFloatingActionButton(
                    onClick = { nav.abrir(Destino.NuevoLote) },
                    icon = { Icon(Icons.Filled.Factory, null) }, text = { Text("Registrar lote") },
                )
            }
        },
    ) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { (inv, lotes) ->
                val (productos, _) = inv
                val activos = productos.filter { it.disponible }
                val alertas = activos.count { it.alerta != null }
                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Kpi("Unidades en bodega", "${activos.sumOf { it.stock.coerceAtLeast(0) }}", Modifier.weight(1f), Icons.Filled.Inventory2, destacado = true)
                            Kpi(
                                "Alertas", "$alertas", Modifier.weight(1f), Icons.Filled.WarningAmber,
                                detalle = if (alertas == 0) "Todo en orden" else "Por reponer",
                                colorDetalle = if (alertas == 0) VerdeOk else Ambar,
                            )
                        }
                    }
                    item { TituloSeccion("Productos") }
                    items(activos, key = { it.productoId }) { s -> TarjetaStock(s, puedeAjustar = usuario.puede("inventario")) { ajustando = s } }
                    val inactivos = productos.filter { !it.disponible && it.stock != 0 }
                    if (inactivos.isNotEmpty()) {
                        item { TituloSeccion("No disponibles a la venta") }
                        items(inactivos, key = { "x" + it.productoId }) { s -> TarjetaStock(s, puedeAjustar = usuario.puede("inventario")) { ajustando = s } }
                    }
                    if (lotes.isNotEmpty()) {
                        item { TituloSeccion("Últimos lotes de producción") }
                        items(lotes.take(8), key = { it.id }) { l ->
                            Tarjeta {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Factory, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("+${l.cantidad} · ${l.nombre}", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOfNotNull(l.id, fechaCorta(l.fecha), l.costoTotal?.let { "costo ${pesos(it)}" }, l.por.ifEmpty { null }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (l.nota.isNotBlank()) Text(l.nota, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ajustando?.let { s ->
        DialogoAjuste(s, onCerrar = { ajustando = null }) { cantidad, nota ->
            vm.ejecutar("Inventario ajustado", { ok -> if (ok) { ajustando = null; carga.recargar() } }) {
                it.ajustar(s.productoId, cantidad, nota)
            }
        }
    }
}

@Composable
private fun TarjetaStock(s: StockItem, puedeAjustar: Boolean, onAjustar: () -> Unit) {
    val color = when (s.alerta) {
        "agotado" -> RojoAlerta
        "bajo" -> Ambar
        else -> VerdeOk
    }
    // La barra se llena comparando con el triple del mínimo (nivel "cómodo").
    val referencia = (s.stockMinimo * 3).coerceAtLeast(1)
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.nombre, fontWeight = FontWeight.SemiBold)
                Text(
                    when (s.alerta) {
                        "agotado" -> "Agotado"
                        "bajo" -> "Bajo · mínimo ${s.stockMinimo}"
                        else -> "Mínimo ${s.stockMinimo}"
                    },
                    style = MaterialTheme.typography.labelMedium, color = color,
                )
            }
            Text("${s.stock}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (s.alerta != null) color else MaterialTheme.colorScheme.onSurface)
        }
        LinearProgressIndicator(
            progress = { (s.stock.toFloat() / referencia).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh, drawStopIndicator = {},
        )
        if (puedeAjustar) {
            TextButton(onClick = onAjustar, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.Tune, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ajustar (conteo, merma, daño)")
            }
        }
    }
}

@Composable
private fun DialogoAjuste(s: StockItem, onCerrar: () -> Unit, onGuardar: (Int, String) -> Unit) {
    var cantidad by remember { mutableStateOf("") }
    var sumar by remember { mutableStateOf(false) }
    var nota by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Ajustar ${s.nombre}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Hay ${s.stock}. Usa esto para corregir el conteo o registrar merma.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !sumar, onClick = { sumar = false }, label = { Text("Restar") })
                    FilterChip(selected = sumar, onClick = { sumar = true }, label = { Text("Sumar") })
                }
                CampoTexto(cantidad, { cantidad = it.filter(Char::isDigit).take(6) }, "Cantidad", teclado = KeyboardType.Number)
                CampoTexto(nota, { nota = it }, "Motivo", ayuda = "Ej.: conteo físico, bolsas rotas")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { cantidad.toIntOrNull()?.let { onGuardar(if (sumar) it else -it, nota) } },
                enabled = (cantidad.toIntOrNull() ?: 0) > 0 && nota.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

// ======================= Registrar lote =======================

@Composable
fun PantallaNuevoLote(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "negocio") { it.negocio() }
    var producto by remember { mutableStateOf("") }
    var cantidad by remember { mutableStateOf("") }
    var costo by remember { mutableStateOf("") }
    var nota by remember { mutableStateOf("") }
    var guardando by remember { mutableStateOf(false) }

    PantallaSecundaria("Registrar lote de producción", onVolver) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { negocio ->
                val productos = negocio.productos.filter { it.disponible }.ifEmpty { negocio.productos }
                if (producto.isEmpty()) producto = productos.firstOrNull()?.id ?: ""
                val elegido = productos.find { it.id == producto }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Banner(
                        "Registra aquí lo que se produjo o empacó (por ejemplo, \"hicimos 200 pacas\"). Se suma al inventario.",
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Selector("Producto", productos.map { it.id to it.nombre }, producto) { producto = it }
                    CampoTexto(cantidad, { cantidad = it.filter(Char::isDigit).take(6) }, "Cantidad producida", teclado = KeyboardType.Number)
                    CampoDinero(
                        costo, { costo = it }, "Costo total del lote (opcional)",
                        ayuda = cantidad.toIntOrNull()?.takeIf { it > 0 && costo.isNotEmpty() }?.let { "≈ ${pesos(costo.toLong() / it)} por unidad" }
                            ?: elegido?.let { "Costo configurado por unidad: ${pesos(it.costo)}" },
                    )
                    CampoTexto(nota, { nota = it }, "Nota (opcional)", ayuda = "Turno, responsable, observaciones")
                    Button(
                        onClick = {
                            guardando = true
                            vm.ejecutar("Lote registrado", { ok -> guardando = false; if (ok) onVolver() }) {
                                it.registrarLote(producto, cantidad.toInt(), costo.toLongOrNull(), nota)
                            }
                        },
                        enabled = !guardando && producto.isNotEmpty() && (cantidad.toIntOrNull() ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    ) { Text("Sumar al inventario") }
                }
            }
        }
    }
}

// ======================= Movimientos =======================

@Composable
fun PantallaMovimientos(vm: AppViewModel, onVolver: () -> Unit) {
    val carga = rememberCarga(vm, "movimientos") { it.inventario().second }
    PantallaSecundaria("Movimientos de inventario", onVolver) { relleno ->
        Box(Modifier.padding(relleno)) {
            ConCarga(carga) { movs ->
                if (movs.isEmpty()) EstadoVacio(texto = "Todavía no hay movimientos.")
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(movs) { m ->
                        val (icono, color, tipo) = when (m.tipo) {
                            "lote" -> Triple(Icons.Filled.Factory, VerdeOk, "Producción")
                            "venta" -> Triple(Icons.Filled.ShoppingCart, MaterialTheme.colorScheme.primary, "Venta")
                            "cancelacion" -> Triple(Icons.Filled.Undo, Turquesa, "Devolución por cancelación")
                            else -> Triple(Icons.Filled.Tune, Ambar, "Ajuste")
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icono, null, tint = color)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$tipo · ${m.nombre}", fontWeight = FontWeight.Medium)
                                Text(
                                    listOf(fechaCorta(m.fecha), m.pedido, m.nota, m.por).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (m.cantidad > 0) "+" else "") + m.cantidad, fontWeight = FontWeight.Bold,
                                color = if (m.cantidad > 0) VerdeOk else Color.Unspecified,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
