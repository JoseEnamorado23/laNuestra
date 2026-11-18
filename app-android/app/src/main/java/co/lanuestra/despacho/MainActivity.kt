package co.lanuestra.despacho

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaLaNuestra {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (vm.usuario == null) PantallaLogin(vm) else AppPrincipal(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.iniciar()
    }

    override fun onStop() {
        super.onStop()
        vm.detener()
    }
}

// ======================= Navegación =======================

enum class Pestana(val titulo: String, val icono: ImageVector, val iconoActivo: ImageVector, val permiso: String) {
    Inicio("Inicio", Icons.Outlined.Home, Icons.Filled.Home, "pedidos"),
    Pedidos("Pedidos", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Filled.ReceiptLong, "pedidos"),
    Inventario("Inventario", Icons.Outlined.Inventory2, Icons.Filled.Inventory2, "inventario"),
    Reportes("Reportes", Icons.Outlined.Insights, Icons.Filled.Insights, "reportes"),
    Mas("Más", Icons.Outlined.GridView, Icons.Filled.GridView, ""),
}

/** Pantallas que se abren encima de las pestañas. */
sealed interface Destino {
    data class DetallePedido(val id: String) : Destino
    data object NuevoPedido : Destino
    data object NuevoLote : Destino
    data object Movimientos : Destino
    data object Contabilidad : Destino
    data object Configuracion : Destino
    data object Productos : Destino
    data object Usuarios : Destino
}

class Navegador {
    var pestana by mutableStateOf(Pestana.Inicio)
    val pila = mutableStateListOf<Destino>()
    fun abrir(d: Destino) { pila.add(d) }
    fun volver() { if (pila.isNotEmpty()) pila.removeAt(pila.lastIndex) }
    fun irA(p: Pestana) { pila.clear(); pestana = p }
}

@Composable
fun AppPrincipal(vm: AppViewModel) {
    val usuario = vm.usuario ?: return
    val nav = remember { Navegador() }
    val pestanas = Pestana.entries.filter { it.permiso.isEmpty() || usuario.puede(it.permiso) }
    if (nav.pestana !in pestanas) nav.pestana = pestanas.first()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let { snackbar.showSnackbar(it); vm.mensaje = null }
    }

    val destino = nav.pila.lastOrNull()
    BackHandler(enabled = destino != null || nav.pestana != pestanas.first()) {
        if (destino != null) nav.volver() else nav.irA(pestanas.first())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (destino == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    pestanas.forEach { p ->
                        val activa = nav.pestana == p
                        NavigationBarItem(
                            selected = activa,
                            onClick = { nav.irA(p) },
                            icon = {
                                val nuevos = if (p == Pestana.Pedidos) vm.pedidos.count { it.estado == "nuevo" } else 0
                                BadgedBox(badge = { if (nuevos > 0) Badge { Text("$nuevos") } }) {
                                    Icon(if (activa) p.iconoActivo else p.icono, p.titulo)
                                }
                            },
                            label = { Text(p.titulo) },
                        )
                    }
                }
            }
        },
    ) { relleno ->
        Box(Modifier.padding(bottom = relleno.calculateBottomPadding())) {
            AnimatedContent(destino ?: nav.pestana, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pantalla") { actual ->
                when (actual) {
                    Pestana.Inicio -> PantallaInicio(vm, nav)
                    Pestana.Pedidos -> PantallaPedidos(vm, nav)
                    Pestana.Inventario -> PantallaInventario(vm, nav)
                    Pestana.Reportes -> PantallaReportes(vm)
                    Pestana.Mas -> PantallaMas(vm, nav)
                    is Destino.DetallePedido -> PantallaDetallePedido(vm, actual.id, nav::volver)
                    Destino.NuevoPedido -> PantallaNuevoPedido(vm, nav::volver)
                    Destino.NuevoLote -> PantallaNuevoLote(vm, nav::volver)
                    Destino.Movimientos -> PantallaMovimientos(vm, nav::volver)
                    Destino.Contabilidad -> PantallaContabilidad(vm, nav::volver)
                    Destino.Configuracion -> PantallaConfiguracion(vm, nav::volver)
                    Destino.Productos -> PantallaProductos(vm, nav::volver)
                    Destino.Usuarios -> PantallaUsuarios(vm, nav::volver)
                    else -> {}
                }
            }
        }
    }
}

/** Barra superior estándar de las pantallas secundarias. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaSecundaria(
    titulo: String, onVolver: () -> Unit, acciones: @Composable RowScope.() -> Unit = {},
    flotante: @Composable () -> Unit = {}, contenido: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titulo) },
                navigationIcon = {
                    IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = acciones,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = flotante,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        content = contenido,
    )
}

/** Barra superior de las pestañas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPestana(
    titulo: String, subtitulo: String? = null, acciones: @Composable RowScope.() -> Unit = {},
    flotante: @Composable () -> Unit = {}, contenido: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titulo, style = MaterialTheme.typography.headlineSmall)
                        if (subtitulo != null) {
                            Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = acciones,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = flotante,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        content = contenido,
    )
}

// ======================= Acciones del teléfono =======================

private fun abrir(context: Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: Exception) { }
}

fun abrirMapa(context: Context, direccion: String) =
    abrir(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(direccion))))

fun abrirWhatsapp(context: Context, telefono: String) =
    abrir(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$telefono")))

fun llamar(context: Context, telefono: String) =
    abrir(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$telefono")))
