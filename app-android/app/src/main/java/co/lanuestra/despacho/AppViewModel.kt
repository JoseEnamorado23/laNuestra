package co.lanuestra.despacho

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

const val URL_POR_DEFECTO = "https://envy-handstand-customary.ngrok-free.dev"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ajustes = Ajustes(app)

    var url by mutableStateOf(ajustes.url.ifEmpty { URL_POR_DEFECTO })
        private set
    var usuario by mutableStateOf(
        ajustes.usuarioJson.takeIf { it.isNotEmpty() && ajustes.token.isNotEmpty() }
            ?.let { runCatching { parseUsuario(JSONObject(it)) }.getOrNull() },
    )
        private set

    // Datos que se actualizan solos mientras la app está abierta.
    var pedidos by mutableStateOf<List<Pedido>>(emptyList())
        private set
    var avisos by mutableStateOf<List<Aviso>>(emptyList())
        private set
    var resumen by mutableStateOf<Resumen?>(null)
        private set
    var errorConexion by mutableStateOf<String?>(null)
        private set
    var actualizando by mutableStateOf(false)
        private set

    /** Mensaje corto para mostrar abajo (snackbar). */
    var mensaje by mutableStateOf<String?>(null)

    private var sondeo: Job? = null

    fun api() = Api(url, ajustes.token)

    // ---------------- Sesión ----------------

    fun guardarUrl(nueva: String) {
        val limpia = nueva.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
        url = limpia
        ajustes.url = limpia
    }

    fun abrirSesion(sesion: Sesion) {
        ajustes.token = sesion.token
        ajustes.usuarioJson = usuarioAJson(sesion.usuario)
        usuario = sesion.usuario
        iniciar()
    }

    fun cerrarSesion() {
        val api = api()
        viewModelScope.launch { runCatching { api.logout() } }
        sondeo?.cancel()
        ajustes.token = ""
        ajustes.usuarioJson = ""
        usuario = null
        pedidos = emptyList()
        avisos = emptyList()
        resumen = null
    }

    /** Si el servidor dice que la sesión venció, vuelve al inicio de sesión. */
    fun manejarError(e: Throwable): String {
        if (e is ErrorApi && e.codigo == 401) {
            cerrarSesion()
            return "Tu sesión venció. Inicia sesión de nuevo."
        }
        return e.message ?: "Ocurrió un error."
    }

    // ---------------- Actualización periódica ----------------

    fun iniciar() {
        if (usuario == null || sondeo?.isActive == true) return
        sondeo = viewModelScope.launch {
            // Refresca los permisos por si un administrador cambió el rol.
            runCatching { api().yo() }.onSuccess {
                usuario = it
                ajustes.usuarioJson = usuarioAJson(it)
            }.onFailure { manejarError(it) }
            while (isActive && usuario != null) {
                actualizarAhora()
                delay(8_000)
            }
        }
    }

    fun detener() {
        sondeo?.cancel()
    }

    suspend fun actualizarAhora() {
        val u = usuario ?: return
        actualizando = true
        try {
            val api = api()
            pedidos = api.pedidos()
            avisos = api.avisos()
            if (u.puede("pedidos")) resumen = api.resumen()
            errorConexion = null
        } catch (e: Exception) {
            errorConexion = manejarError(e)
        } finally {
            actualizando = false
        }
    }

    fun refrescar() {
        viewModelScope.launch { actualizarAhora() }
    }

    // ---------------- Acciones ----------------

    fun cambiarEstado(pedido: Pedido, nuevo: String) {
        viewModelScope.launch {
            try {
                api().cambiarEstado(pedido.id, nuevo)
                pedidos = pedidos.map { if (it.id == pedido.id) it.copy(estado = nuevo) else it }
                mensaje = "${pedido.id}: ${ESTADOS[nuevo]}"
                actualizarAhora()
            } catch (e: Exception) {
                mensaje = manejarError(e)
            }
        }
    }

    fun atenderAviso(aviso: Aviso) {
        viewModelScope.launch {
            try {
                api().atenderAviso(aviso.telefono)
                avisos = avisos.filter { it.telefono != aviso.telefono }
                mensaje = "Aviso marcado como atendido"
            } catch (e: Exception) {
                mensaje = manejarError(e)
            }
        }
    }

    /** Ejecuta una acción de la API y muestra el error si falla. Devuelve true si salió bien. */
    fun ejecutar(exito: String? = null, alTerminar: (Boolean) -> Unit = {}, accion: suspend (Api) -> Unit) {
        viewModelScope.launch {
            val ok = try {
                accion(api())
                if (exito != null) mensaje = exito
                true
            } catch (e: Exception) {
                mensaje = manejarError(e)
                false
            }
            alTerminar(ok)
            if (ok) actualizarAhora()
        }
    }
}
