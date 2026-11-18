package co.lanuestra.despacho

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth

@Composable
fun PantallaReportes(vm: AppViewModel) {
    var mes by remember { mutableStateOf(mesActual()) }
    val carga = rememberCarga(vm, mes) { api -> api.reporte(mes) to api.tendencia() }

    PantallaPestana(
        titulo = "Reportes",
        subtitulo = "Ventas, ganancias y gastos",
        acciones = { IconButton(onClick = carga.recargar) { Icon(Icons.Filled.Refresh, "Actualizar") } },
    ) { relleno ->
        Column(Modifier.padding(relleno).fillMaxSize()) {
            // Selector de mes.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { mes = YearMonth.parse(mes).minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior")
                }
                Text(nombreMes(mes), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { mes = YearMonth.parse(mes).plusMonths(1).toString() }, enabled = mes < mesActual()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente")
                }
            }
            ConCarga(carga) { (r, tendencia) -> ContenidoReporte(r, tendencia) }
        }
    }
}

@Composable
private fun ContenidoReporte(r: Reporte, tendencia: List<MesTendencia>) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ----- Indicadores principales -----
        item {
            val (txt, color) = textoVariacion(r.variacionVentas)
            Kpi("Ventas del mes", pesos(r.ventas), Modifier.fillMaxWidth(), Icons.Filled.Payments, detalle = txt, destacado = true)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (txtU, colorU) = textoVariacion(r.variacionUtilidad)
                Kpi("Utilidad neta", pesosCortos(r.utilidad), Modifier.weight(1f), Icons.Filled.Savings,
                    detalle = txtU.replace(" vs. mes anterior", ""), colorDetalle = colorU)
                Kpi("Margen bruto", "${r.margen}%", Modifier.weight(1f), Icons.Filled.Percent, detalle = "Ganancia ${pesosCortos(r.gananciaBruta)}")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Kpi("Pedidos", "${r.pedidos}", Modifier.weight(1f), Icons.Filled.ShoppingBag,
                    detalle = if (r.cancelados > 0) "${r.cancelados} cancelados" else "Sin cancelaciones")
                Kpi("Ticket promedio", pesosCortos(r.ticketPromedio), Modifier.weight(1f), Icons.Filled.Receipt)
            }
        }
        if (r.productosSinCosto.isNotEmpty()) {
            item {
                Banner(
                    "Falta el costo de: ${r.productosSinCosto.joinToString(", ")}. Configúralo en Más → Productos para calcular bien la ganancia.",
                    Ambar.copy(alpha = 0.15f), MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // ----- Estado de resultados -----
        item {
            Tarjeta {
                Text("Estado de resultados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FilaResultado("Ventas", r.ventas)
                FilaResultado("Costo de lo vendido", -r.costoVentas)
                HorizontalDivider()
                FilaResultado("Ganancia bruta", r.gananciaBruta, negrita = true)
                FilaResultado("Gastos del mes", -r.gastos)
                HorizontalDivider()
                FilaResultado("Utilidad neta", r.utilidad, negrita = true, grande = true)
            }
        }

        // ----- Ventas por día -----
        item {
            Tarjeta {
                Text("Ventas por día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val mejorDia = r.porDia.withIndex().maxByOrNull { it.value }
                if (mejorDia != null && mejorDia.value > 0) {
                    Text("Mejor día: ${mejorDia.index + 1} (${pesos(mejorDia.value)})", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val etiquetas = r.porDia.indices.map { if ((it + 1) % 5 == 0 || it == 0) "${it + 1}" else "" }
                GraficaBarras(r.porDia, etiquetas, resaltar = mejorDia?.index)
            }
        }

        // ----- Productos -----
        if (r.porProducto.isNotEmpty()) {
            item {
                Tarjeta {
                    Text("Lo más vendido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val max = r.porProducto.maxOf { it.ventas }.coerceAtLeast(1)
                    r.porProducto.forEachIndexed { i, p ->
                        BarraHorizontal(
                            "${i + 1}. ${p.nombre}", pesosCortos(p.ventas), p.ventas.toFloat() / max,
                            detalle = "${p.unidades} unidades",
                        )
                    }
                }
            }
            item {
                Tarjeta {
                    Text("Lo que más ganancia deja", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val ordenados = r.porProducto.sortedByDescending { it.ganancia }
                    val max = ordenados.maxOf { it.ganancia }.coerceAtLeast(1)
                    ordenados.forEachIndexed { i, p ->
                        BarraHorizontal(
                            "${i + 1}. ${p.nombre}", pesosCortos(p.ganancia), p.ganancia.toFloat() / max, VerdeOk,
                            detalle = "Margen ${p.margen}% · ganancia por unidad ${pesos(if (p.unidades > 0) p.ganancia / p.unidades else 0)}",
                        )
                    }
                }
            }
        }

        // ----- Tendencia -----
        item {
            Tarjeta {
                Text("Últimos 6 meses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                GraficaBarras(
                    tendencia.map { it.ventas }, tendencia.map { nombreMes(it.mes, corto = true) },
                    resaltar = tendencia.indexOfFirst { it.mes == r.mes }.takeIf { it >= 0 },
                )
                tendencia.lastOrNull()?.let {
                    Text("Ventas en barras; el mes seleccionado va resaltado.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ----- Canales y pagos -----
        if (r.porOrigen.isNotEmpty() || r.porPago.isNotEmpty()) {
            item {
                Tarjeta {
                    Text("Canales y pagos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val totalOrigen = r.porOrigen.values.sum().coerceAtLeast(1)
                    r.porOrigen.forEach { (o, n) ->
                        BarraHorizontal(
                            if (o == "manual") "Pedidos manuales" else "Pedidos por WhatsApp (agente)",
                            "$n", n.toFloat() / totalOrigen, Turquesa,
                        )
                    }
                    val totalPago = r.porPago.values.sum().coerceAtLeast(1)
                    r.porPago.forEach { (m, v) ->
                        BarraHorizontal(m.replaceFirstChar { it.uppercase() }, pesosCortos(v), v.toFloat() / totalPago)
                    }
                }
            }
        }

        // ----- Gastos -----
        if (r.gastosPorCategoria.isNotEmpty()) {
            item {
                Tarjeta {
                    Text("Gastos por categoría", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val max = r.gastosPorCategoria.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                    r.gastosPorCategoria.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                        BarraHorizontal(c, pesosCortos(v), v.toFloat() / max, RojoAlerta.copy(alpha = 0.8f))
                    }
                }
            }
        }

        // ----- Producción -----
        if (r.produccion.isNotEmpty()) {
            item {
                Tarjeta {
                    Text("Producción del mes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    r.produccion.forEach { (nombre, n) ->
                        Row { Text(nombre, Modifier.weight(1f)); Text("$n und.", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }

        // ----- Clientes -----
        if (r.mejoresClientes.isNotEmpty()) {
            item {
                Tarjeta {
                    Text("Mejores clientes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    r.mejoresClientes.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.nombre)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.nombre, fontWeight = FontWeight.SemiBold)
                                Text("${c.pedidos} pedidos", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(pesos(c.total), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (r.pedidos == 0) item { EstadoVacio(Icons.Filled.Insights, "No hay ventas registradas en este mes.") }
    }
}

@Composable
private fun FilaResultado(texto: String, valor: Long, negrita: Boolean = false, grande: Boolean = false) {
    Row {
        Text(texto, Modifier.weight(1f), fontWeight = if (negrita) FontWeight.Bold else FontWeight.Normal)
        Text(
            pesos(valor), fontWeight = if (negrita) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (grande) 18.sp else 14.sp,
            color = if (grande) (if (valor >= 0) VerdeOk else RojoAlerta) else MaterialTheme.colorScheme.onSurface,
        )
    }
}
