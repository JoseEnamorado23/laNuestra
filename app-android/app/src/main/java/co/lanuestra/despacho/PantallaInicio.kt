package co.lanuestra.despacho

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun PantallaInicio(vm: AppViewModel, nav: Navegador) {
    val usuario = vm.usuario ?: return
    val r = vm.resumen
    val hora = LocalTime.now(ZoneId.of("America/Bogota")).hour
    val saludo = when { hora < 12 -> "Buenos días"; hora < 19 -> "Buenas tardes"; else -> "Buenas noches" }

    PantallaPestana(
        titulo = "$saludo, ${usuario.nombre.substringBefore(" ")}",
        subtitulo = ROLES[usuario.rol],
        acciones = {
            if (vm.actualizando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = vm::refrescar) { Icon(Icons.Filled.Refresh, "Actualizar") }
        },
    ) { relleno ->
        LazyColumn(
            Modifier.padding(relleno).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.errorConexion?.let { item { Banner(it) } }

            // Indicadores del día.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Kpi("Ventas hoy", pesos(r?.ventasHoy ?: 0), Modifier.weight(1f), Icons.Filled.Payments, destacado = true,
                        detalle = "${r?.pedidosHoy ?: 0} pedidos")
                    Kpi("Este mes", pesosCortos(r?.ventasMes ?: 0), Modifier.weight(1f), Icons.AutoMirrored.Filled.TrendingUp,
                        detalle = "${r?.pedidosMes ?: 0} pedidos")
                }
            }

            // Pedidos por atender.
            item { TituloSeccion("Por atender") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("nuevo" to Icons.Filled.FiberNew, "confirmado" to Icons.Filled.Inventory, "en_camino" to Icons.Filled.LocalShipping)
                        .forEach { (estado, icono) ->
                            val (fondo, texto) = colorEstado(estado)
                            Card(
                                Modifier.weight(1f).clickable { nav.irA(Pestana.Pedidos) },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = fondo, contentColor = texto),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Icon(icono, null)
                                    Text("${r?.pendientes?.get(estado) ?: 0}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                    Text(ESTADOS[estado] ?: estado, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                }
            }

            // Alertas.
            val alertas = r?.alertas.orEmpty()
            if (vm.avisos.isNotEmpty() || alertas.isNotEmpty()) item { TituloSeccion("Alertas") }
            items(vm.avisos, key = { "a" + it.telefono }) { aviso ->
                FilaAlerta(Icons.Filled.SupportAgent, "${aviso.nombre.ifEmpty { aviso.telefono }} pide un asesor", aviso.motivo, Ambar) {
                    nav.irA(Pestana.Pedidos)
                }
            }
            items(alertas, key = { "s" + it.productoId }) { s ->
                val agotado = s.alerta == "agotado"
                FilaAlerta(
                    if (agotado) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber,
                    if (agotado) "Agotado: ${s.nombre}" else "Inventario bajo: ${s.nombre}",
                    "Quedan ${s.stock} (mínimo ${s.stockMinimo}). Registra un lote de producción.",
                    if (agotado) RojoAlerta else Ambar,
                ) { if (usuario.puede("inventario")) nav.irA(Pestana.Inventario) }
            }

            // Accesos rápidos.
            if (usuario.puede("pedido_manual") || usuario.puede("lotes")) {
                item { TituloSeccion("Accesos rápidos") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (usuario.puede("pedido_manual")) {
                            Acceso("Nuevo pedido", Icons.Filled.AddShoppingCart, Modifier.weight(1f)) { nav.abrir(Destino.NuevoPedido) }
                        }
                        if (usuario.puede("lotes")) {
                            Acceso("Registrar lote", Icons.Filled.Factory, Modifier.weight(1f)) { nav.abrir(Destino.NuevoLote) }
                        }
                        if (usuario.puede("contabilidad")) {
                            Acceso("Gasto", Icons.Filled.RequestQuote, Modifier.weight(1f)) { nav.abrir(Destino.Contabilidad) }
                        }
                    }
                }
            }

            // Últimos pedidos.
            item {
                TituloSeccion("Últimos pedidos") {
                    TextButton(onClick = { nav.irA(Pestana.Pedidos) }) { Text("Ver todos") }
                }
            }
            val ultimos = vm.pedidos.take(5)
            if (ultimos.isEmpty()) item { EstadoVacio(texto = "Todavía no hay pedidos.") }
            items(ultimos, key = { it.id }) { p -> FilaPedido(p) { nav.abrir(Destino.DetallePedido(p.id)) } }
        }
    }
}

@Composable
private fun FilaAlerta(icono: ImageVector, titulo: String, detalle: String, color: Color, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(titulo, fontWeight = FontWeight.SemiBold)
                Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
        }
    }
}

@Composable
private fun Acceso(texto: String, icono: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icono, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(texto, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
        }
    }
}
