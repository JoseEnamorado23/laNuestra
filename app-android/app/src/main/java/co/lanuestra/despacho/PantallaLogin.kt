package co.lanuestra.despacho

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun PantallaLogin(vm: AppViewModel) {
    val alcance = rememberCoroutineScope()
    var url by remember { mutableStateOf(vm.url) }
    var editarServidor by remember { mutableStateOf(false) }
    var necesitaAdmin by remember { mutableStateOf<Boolean?>(null) }
    var negocio by remember { mutableStateOf("La Nuestra") }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }

    var nombre by remember { mutableStateOf("") }
    var usuario by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }

    // Pregunta al servidor si ya hay usuarios (si no, se crea el administrador).
    LaunchedEffect(vm.url) {
        necesitaAdmin = null
        try {
            val (n, falta) = vm.api().inicio()
            negocio = n
            necesitaAdmin = falta
            error = null
        } catch (e: Exception) {
            error = e.message
            editarServidor = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Cabecera con degradado de marca.
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(AzulMarca, Turquesa)))
                .statusBarsPadding()
                .padding(horizontal = 28.dp, vertical = 40.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WaterDrop, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(negocio, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Gestión de pedidos, inventario y ventas", color = Color.White.copy(alpha = 0.85f))
            }
        }

        Column(Modifier.padding(24.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (necesitaAdmin) {
                true -> {
                    Text("Primer ingreso", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Crea la cuenta del administrador. Con ella después agregas a los demás usuarios.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CampoTexto(nombre, { nombre = it }, "Tu nombre")
                }
                false -> {
                    Text("Iniciar sesión", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (necesitaAdmin != null) {
                CampoTexto(usuario, { usuario = it.trim().lowercase() }, "Usuario")
                OutlinedTextField(
                    value = clave, onValueChange = { clave = it }, label = { Text("Contraseña") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (necesitaAdmin == true) ({ Text("Mínimo 6 caracteres") }) else null,
                )
            }

            error?.let { Banner(it) }

            if (necesitaAdmin != null) {
                Button(
                    onClick = {
                        alcance.launch {
                            trabajando = true
                            error = null
                            try {
                                val api = vm.api()
                                val sesion = if (necesitaAdmin == true) api.primerAdmin(nombre, usuario, clave) else api.login(usuario, clave)
                                vm.abrirSesion(sesion)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                trabajando = false
                            }
                        }
                    },
                    enabled = !trabajando && usuario.isNotBlank() && clave.isNotBlank() && (necesitaAdmin == false || nombre.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (trabajando) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(if (necesitaAdmin == true) "Crear administrador" else "Entrar", fontSize = 16.sp)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (editarServidor) {
                Text("Servidor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                CampoTexto(url, { url = it }, "URL del servidor", teclado = KeyboardType.Uri, ayuda = "La URL de ngrok o la del servidor fijo")
                OutlinedButton(onClick = { vm.guardarUrl(url) }, modifier = Modifier.fillMaxWidth()) { Text("Probar conexión") }
            } else {
                TextButton(onClick = { editarServidor = true }) {
                    Icon(Icons.Filled.Dns, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Servidor: " + vm.url.removePrefix("https://").removePrefix("http://"), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, softWrap = false)
                }
            }
        }
    }
}
