package co.lanuestra.despacho

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ======================= Tema =======================

val AzulMarca = Color(0xFF0A5FA8)
val Turquesa = Color(0xFF0E9AA7)
val VerdeOk = Color(0xFF1F8A4C)
val Ambar = Color(0xFFB86E00)
val RojoAlerta = Color(0xFFC62828)

@Composable
fun TemaLaNuestra(content: @Composable () -> Unit) {
    val oscuro = isSystemInDarkTheme()
    val colores = if (oscuro) {
        darkColorScheme(
            primary = Color(0xFF8CC8FF), onPrimary = Color(0xFF00325A),
            primaryContainer = Color(0xFF004880), onPrimaryContainer = Color(0xFFD2E6FF),
            secondary = Color(0xFF6FD6E0), secondaryContainer = Color(0xFF004F56),
            background = Color(0xFF0F1418), surface = Color(0xFF0F1418),
            surfaceContainer = Color(0xFF1A2026), surfaceContainerHigh = Color(0xFF232A31),
        )
    } else {
        lightColorScheme(
            primary = AzulMarca, onPrimary = Color.White,
            primaryContainer = Color(0xFFD5E8FB), onPrimaryContainer = Color(0xFF00335E),
            secondary = Turquesa, secondaryContainer = Color(0xFFCDEFF2), onSecondaryContainer = Color(0xFF00363B),
            background = Color(0xFFF5F8FB), surface = Color(0xFFF5F8FB),
            surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDF2F7),
            surfaceContainerLow = Color(0xFFFFFFFF),
        )
    }
    MaterialTheme(colorScheme = colores, content = content)
}

// ======================= Carga de datos =======================

class Carga<T>(val datos: T?, val error: String?, val cargando: Boolean, val recargar: () -> Unit)

/** Carga datos de la API cuando cambian las [claves] o al llamar recargar(). */
@Composable
fun <T> rememberCarga(vm: AppViewModel, vararg claves: Any?, cargar: suspend (Api) -> T): Carga<T> {
    var version by remember { mutableIntStateOf(0) }
    var datos by remember(*claves) { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cargando by remember { mutableStateOf(true) }
    LaunchedEffect(*claves, version) {
        cargando = true
        try {
            datos = cargar(vm.api())
            error = null
        } catch (e: Exception) {
            error = vm.manejarError(e)
        } finally {
            cargando = false
        }
    }
    return Carga(datos, error, cargando) { version++ }
}

/** Muestra el contenido cuando hay datos; si no, cargando o error. */
@Composable
fun <T> ConCarga(carga: Carga<T>, contenido: @Composable (T) -> Unit) {
    val datos = carga.datos
    when {
        datos != null -> contenido(datos)
        carga.error != null -> EstadoVacio(Icons.Filled.CloudOff, carga.error, "Reintentar", carga.recargar)
        else -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

// ======================= Piezas visuales =======================

@Composable
fun EstadoVacio(icono: ImageVector = Icons.Filled.Inbox, texto: String, accion: String? = null, onAccion: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icono, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Text(texto, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        if (accion != null) OutlinedButton(onClick = onAccion) { Text(accion) }
    }
}

@Composable
fun Tarjeta(modifier: Modifier = Modifier, contenido: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = contenido)
    }
}

@Composable
fun TituloSeccion(texto: String, modifier: Modifier = Modifier, accion: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(texto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        accion?.invoke()
    }
}

/** Indicador grande (ventas, pedidos, utilidad...). */
@Composable
fun Kpi(
    titulo: String, valor: String, modifier: Modifier = Modifier, icono: ImageVector? = null,
    detalle: String? = null, colorDetalle: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    destacado: Boolean = false,
) {
    val fondo = if (destacado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val texto = if (destacado) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = texto),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icono != null) {
                    Icon(icono, null, Modifier.size(18.dp), tint = texto.copy(alpha = 0.8f))
                    Spacer(Modifier.width(6.dp))
                }
                Text(titulo, style = MaterialTheme.typography.labelLarge, color = texto.copy(alpha = 0.8f), maxLines = 1)
            }
            Text(valor, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detalle != null) {
                Text(detalle, style = MaterialTheme.typography.labelMedium, color = if (destacado) texto.copy(alpha = 0.85f) else colorDetalle)
            }
        }
    }
}

fun colorEstado(estado: String): Pair<Color, Color> = when (estado) {
    "nuevo" -> Color(0xFFDCEBFF) to Color(0xFF0B4F8A)
    "confirmado" -> Color(0xFFDDF3F5) to Color(0xFF006A73)
    "en_camino" -> Color(0xFFFFEFD0) to Color(0xFF7A4B00)
    "entregado" -> Color(0xFFE0F2E6) to Color(0xFF1B6B3A)
    else -> Color(0xFFFDE2E2) to Color(0xFF8A1C1C)
}

@Composable
fun Etiqueta(texto: String, fondo: Color, color: Color) {
    Text(
        texto, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fondo).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun ChipEstado(estado: String) {
    val (fondo, texto) = colorEstado(estado)
    Etiqueta(ESTADOS[estado] ?: estado, fondo, texto)
}

@Composable
fun Avatar(nombre: String, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    val iniciales = nombre.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(Modifier.size(42.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(iniciales.ifEmpty { "?" }, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun Banner(texto: String, fondo: Color = MaterialTheme.colorScheme.errorContainer, color: Color = MaterialTheme.colorScheme.onErrorContainer) {
    Text(
        texto, color = color, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fondo).padding(12.dp),
    )
}

/** Variación porcentual con flecha y color. */
fun textoVariacion(v: Int?): Pair<String, Color> = when {
    v == null -> "Sin datos del mes anterior" to Color.Gray
    v > 0 -> "▲ $v% vs. mes anterior" to VerdeOk
    v < 0 -> "▼ ${-v}% vs. mes anterior" to RojoAlerta
    else -> "= igual al mes anterior" to Color.Gray
}

// ======================= Gráficas =======================

/** Barras verticales simples (ventas por día o por mes). */
@Composable
fun GraficaBarras(
    valores: List<Long>, etiquetas: List<String>, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary, resaltar: Int? = null,
) {
    val maximo = (valores.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val vacio = MaterialTheme.colorScheme.surfaceContainerHigh
    val resaltado = MaterialTheme.colorScheme.secondary
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = valores.size.coerceAtLeast(1)
            val hueco = size.width / n
            val ancho = (hueco * 0.62f).coerceAtMost(48.dp.toPx())
            valores.forEachIndexed { i, v ->
                val alto = if (v <= 0) 3.dp.toPx() else (v.toFloat() / maximo) * size.height
                drawRoundRect(
                    color = when {
                        v <= 0 -> vacio
                        i == resaltar -> resaltado
                        else -> color
                    },
                    topLeft = Offset(i * hueco + (hueco - ancho) / 2, size.height - alto),
                    size = Size(ancho, alto),
                    cornerRadius = CornerRadius(ancho / 3, ancho / 3),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            etiquetas.forEachIndexed { i, texto ->
                // Con muchas barras, la etiqueta puede salirse de su columna sin cortarse.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (texto.isNotEmpty()) {
                        Text(
                            texto, style = MaterialTheme.typography.labelSmall, softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

/** Fila con barra horizontal proporcional (ranking de productos, gastos...). */
@Composable
fun BarraHorizontal(etiqueta: String, valor: String, fraccion: Float, color: Color = MaterialTheme.colorScheme.primary, detalle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(etiqueta, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(valor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.fillMaxWidth(fraccion.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
        if (detalle != null) Text(detalle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= Campos =======================

@Composable
fun CampoTexto(
    valor: String, onCambio: (String) -> Unit, etiqueta: String, modifier: Modifier = Modifier,
    teclado: KeyboardType = KeyboardType.Text, lineas: Int = 1, ayuda: String? = null,
) {
    OutlinedTextField(
        value = valor, onValueChange = onCambio, label = { Text(etiqueta) },
        modifier = modifier.fillMaxWidth(), singleLine = lineas == 1, minLines = lineas,
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        shape = RoundedCornerShape(14.dp),
        supportingText = ayuda?.let { { Text(it) } },
    )
}

@Composable
fun CampoDinero(valor: String, onCambio: (String) -> Unit, etiqueta: String, modifier: Modifier = Modifier, ayuda: String? = null) {
    CampoTexto(valor, { onCambio(it.filter(Char::isDigit).take(12)) }, etiqueta, modifier, KeyboardType.Number, ayuda = ayuda)
}

/** Selector desplegable sencillo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Selector(etiqueta: String, opciones: List<Pair<String, String>>, seleccion: String, onSeleccion: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = abierto, onExpandedChange = { abierto = it }) {
        OutlinedTextField(
            value = opciones.find { it.first == seleccion }?.second ?: "",
            onValueChange = {}, readOnly = true, label = { Text(etiqueta) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(abierto) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            opciones.forEach { (clave, texto) ->
                DropdownMenuItem(text = { Text(texto) }, onClick = { onSeleccion(clave); abierto = false })
            }
        }
    }
}
