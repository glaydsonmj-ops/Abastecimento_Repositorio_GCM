package br.com.gcmservicos.abastecimento

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

private val AZUL = Color(0xFF1F4F8C)
private val VERMELHO = Color(0xFFB3261E)
private val VERDE = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {
    private val pedido = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lembretes.criarCanal(this)
        pedido.value = intent.getStringExtra(Lembretes.EXTRA_PERIODO)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = AZUL)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(pedido)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pedido.value = intent.getStringExtra(Lembretes.EXTRA_PERIODO)
    }
}

private sealed interface Tela {
    data object Config : Tela
    data object Inicio : Tela
    data class Leitura(val periodo: String) : Tela
    data object Abastecer : Tela
}

/** Resultado do processamento de uma foto. */
private class Foto(val bitmap: Bitmap, val nitida: Boolean, val texto: com.google.mlkit.vision.text.Text?)

@Composable
private fun App(pedido: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tela by remember { mutableStateOf<Tela>(if (prefs.configurado) Tela.Inicio else Tela.Config) }
    var aviso by remember { mutableStateOf<String?>(null) }

    val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permissao.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.configurado) Lembretes.agendar(ctx)
    }
    LaunchedEffect(pedido.value) {
        val p = pedido.value
        if (p != null && prefs.configurado) tela = Tela.Leitura(p)
        pedido.value = null
    }
    BackHandler(enabled = tela is Tela.Leitura || tela is Tela.Abastecer || (tela is Tela.Config && prefs.configurado)) {
        tela = Tela.Inicio
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = AZUL, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Abastecimento GCM", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.statusBarsPadding().padding(16.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val t = tela) {
                Tela.Config -> TelaConfig(prefs) { tela = Tela.Inicio }
                Tela.Inicio -> TelaInicio(
                    prefs, aviso,
                    aoLer = { aviso = null; tela = Tela.Leitura(periodoSugerido()) },
                    aoAbastecer = { aviso = null; tela = Tela.Abastecer },
                    aoConfigurar = { tela = Tela.Config }
                )
                is Tela.Leitura -> TelaLeitura(prefs, t.periodo) { msg -> aviso = msg; tela = Tela.Inicio }
                Tela.Abastecer -> TelaAbastecer(prefs) { msg -> aviso = msg; tela = Tela.Inicio }
            }
        }
    }
}

private fun periodoSugerido(): String =
    if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) Lembretes.INICIO else Lembretes.FIM

private fun Modifier.tela() = this.fillMaxSize().padding(16.dp)

// ---------------------------------------------------------------- Configuração

@Composable
private fun TelaConfig(prefs: Prefs, aoConcluir: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.url) }
    var chave by remember { mutableStateOf(prefs.chave) }
    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var tecnicos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var placas by remember { mutableStateOf<List<String>>(emptyList()) }
    var tecnico by remember { mutableStateOf(prefs.tecnico) }
    var placa by remember { mutableStateOf(prefs.placa) }
    var config by remember { mutableStateOf<JSONObject?>(null) }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Configuração", style = MaterialTheme.typography.headlineSmall)
        Text("Informe o endereço do sistema e a chave de acesso fornecidos pelo responsável.")
        OutlinedTextField(url, { url = it }, label = { Text("Endereço do sistema (URL)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(chave, { chave = it }, label = { Text("Chave de acesso") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !carregando && url.isNotBlank() && chave.isNotBlank(),
            onClick = {
                carregando = true; erro = null
                escopo.launch {
                    try {
                        val r = Api.consultar(url.trim(), chave.trim())
                        val ts = r.getJSONArray("tecnicos")
                        tecnicos = (0 until ts.length()).map { ts.getJSONObject(it) }
                        val vs = r.getJSONArray("veiculos")
                        placas = (0 until vs.length()).map { vs.getJSONObject(it).optString("placa") }
                        config = r.optJSONObject("config")
                        if (tecnicos.isEmpty()) erro = "Nenhum técnico ativo cadastrado na planilha."
                    } catch (e: Exception) {
                        erro = e.message ?: "Não foi possível conectar."
                    } finally {
                        carregando = false
                    }
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (carregando) "Conectando..." else "Conectar e carregar técnicos") }

        erro?.let { Text(it, color = VERMELHO) }

        if (tecnicos.isNotEmpty()) {
            Text("Quem é você?", fontWeight = FontWeight.Bold)
            tecnicos.forEach { t ->
                val nome = t.optString("nome")
                LinhaOpcao(nome, tecnico == nome) {
                    tecnico = nome
                    val p = t.optString("placa")
                    if (p.isNotBlank()) placa = p
                }
            }
            Text("Veículo que você utiliza", fontWeight = FontWeight.Bold)
            if (placas.isEmpty()) Text("Nenhum veículo cadastrado na planilha.", color = VERMELHO)
            placas.forEach { p -> LinhaOpcao(p, placa == p) { placa = p } }

            Button(
                enabled = tecnico.isNotBlank() && placa.isNotBlank(),
                onClick = {
                    prefs.url = url; prefs.chave = chave; prefs.tecnico = tecnico; prefs.placa = placa
                    config?.let { c ->
                        prefs.consumoKmL = c.optDouble("consumoKmL", 10.0)
                        prefs.valorPadrao = c.optDouble("valorPadrao", 180.0)
                        prefs.lembreteInicio = c.optString("lembreteInicio", "08:00")
                        prefs.lembreteFim = c.optString("lembreteFim", "17:00")
                    }
                    Lembretes.agendar(ctx)
                    aoConcluir()
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("Salvar") }
        }
    }
}

@Composable
private fun LinhaOpcao(texto: String, selecionado: Boolean, aoClicar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selecionado, onClick = aoClicar).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selecionado, onClick = aoClicar)
        Text(texto)
    }
}

// ---------------------------------------------------------------- Início

@Composable
private fun TelaInicio(
    prefs: Prefs, aviso: String?,
    aoLer: () -> Unit, aoAbastecer: () -> Unit, aoConfigurar: () -> Unit
) {
    var veiculo by remember { mutableStateOf<JSONObject?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var atualizar by remember { mutableIntStateOf(0) }

    LaunchedEffect(atualizar) {
        erro = null
        try {
            val r = Api.consultar(prefs.url, prefs.chave)
            val vs = r.getJSONArray("veiculos")
            veiculo = (0 until vs.length()).map { vs.getJSONObject(it) }.firstOrNull { it.optString("placa") == prefs.placa }
            r.optJSONObject("config")?.let { c ->
                prefs.consumoKmL = c.optDouble("consumoKmL", prefs.consumoKmL)
                prefs.valorPadrao = c.optDouble("valorPadrao", prefs.valorPadrao)
            }
        } catch (e: Exception) {
            erro = "Sem conexão com o sistema: ${e.message}"
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Olá, ${prefs.tecnico}", style = MaterialTheme.typography.headlineSmall)
        Text("Veículo: ${prefs.placa}")

        aviso?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FA))) {
                Text(it, Modifier.padding(12.dp))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val v = veiculo
                when {
                    erro != null -> Text(erro!!, color = VERMELHO)
                    v == null -> Text("Carregando situação do veículo...")
                    else -> {
                        val situacao = v.optString("situacao")
                        Text(
                            situacao.ifBlank { "Sem dados" }, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = if (situacao == "Abastecer") VERMELHO else if (situacao == "OK") VERDE else Color.Unspecified
                        )
                        Text("Km atual: ${km(v.opt("kmAtual"))}")
                        Text("Km restantes estimados: ${km(v.opt("kmRestantes"))}")
                    }
                }
            }
        }

        Button(onClick = aoLer, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Registrar km do painel") }
        Button(onClick = aoAbastecer, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Registrar abastecimento") }
        OutlinedButton(onClick = { atualizar++ }, modifier = Modifier.fillMaxWidth()) { Text("Atualizar situação") }
        TextButton(onClick = aoConfigurar) { Text("Alterar configuração") }
        Text(
            "Lembretes diários: ${prefs.lembreteInicio} (início) e ${prefs.lembreteFim} (fim).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun km(v: Any?): String {
    val n = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return "-"
    return NumberFormat.getIntegerInstance(Locale("pt", "BR")).format(n) + " km"
}

// ---------------------------------------------------------------- Foto

@Composable
private fun rememberCamera(aoTirar: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var destino by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = destino
        if (ok && u != null) aoTirar(u)
    }
    return {
        val u = Imagem.novoArquivo(ctx)
        destino = u
        launcher.launch(u)
    }
}

private suspend fun processar(ctx: Context, uri: Uri): Foto {
    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Imagem.carregar(ctx, uri) }
    val nitida = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        Imagem.nitidez(bmp) >= Imagem.NITIDEZ_MINIMA
    }
    val texto = try { Ocr.ler(bmp) } catch (e: Exception) { null }
    return Foto(bmp, nitida, texto)
}

@Composable
private fun Miniatura(foto: Foto?) {
    foto ?: return
    Image(
        foto.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
    )
    if (!foto.nitida) Text("A foto parece desfocada. Se possível, tire outra foto com o celular firme e boa iluminação.", color = VERMELHO)
}

private fun campoNumero(s: String) = s.filter { it.isDigit() || it == ',' || it == '.' }

// ---------------------------------------------------------------- Leitura do painel

@Composable
private fun TelaLeitura(prefs: Prefs, periodoInicial: String, aoConcluir: (String) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var periodo by remember { mutableStateOf(periodoInicial) }
    var foto by remember { mutableStateOf<Foto?>(null) }
    var kmTexto by remember { mutableStateOf("") }
    var kmLido by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var kmReferencia by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        try {
            val vs = Api.consultar(prefs.url, prefs.chave).getJSONArray("veiculos")
            kmReferencia = (0 until vs.length()).map { vs.getJSONObject(it) }
                .firstOrNull { it.optString("placa") == prefs.placa }?.optLong("kmAtual")
        } catch (_: Exception) { }
    }

    val camera = rememberCamera { uri ->
        ocupado = true; erro = null
        escopo.launch {
            try {
                val f = processar(ctx, uri)
                foto = f
                val km = f.texto?.let { Ocr.extrairKm(it, kmReferencia) }
                kmLido = km?.toString()
                kmTexto = km?.toString() ?: ""
                if (km == null) erro = "Não consegui ler o km. Digite o valor que aparece no painel."
            } catch (e: Exception) {
                erro = "Erro ao processar a foto: ${e.message}"
            } finally { ocupado = false }
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Km do painel", style = MaterialTheme.typography.headlineSmall)
        LinhaOpcao("Início do dia", periodo == Lembretes.INICIO) { periodo = Lembretes.INICIO }
        LinhaOpcao("Fim do dia", periodo == Lembretes.FIM) { periodo = Lembretes.FIM }

        Button(onClick = camera, enabled = !ocupado, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (foto == null) "Fotografar o painel" else "Tirar outra foto")
        }
        Miniatura(foto)
        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (foto != null) {
            OutlinedTextField(
                kmTexto, { kmTexto = it.filter(Char::isDigit) },
                label = { Text("Km do painel (confira)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            kmReferencia?.takeIf { it > 0 }?.let {
                val digitado = kmTexto.toLongOrNull()
                if (digitado != null && digitado < it) Text("Atenção: o valor é menor que o último km registrado (${km(it)}).", color = VERMELHO)
            }
        }
        erro?.let { Text(it, color = VERMELHO) }

        Button(
            enabled = !ocupado && foto != null && kmTexto.isNotBlank(),
            onClick = {
                val f = foto ?: return@Button
                ocupado = true; erro = null
                escopo.launch {
                    try {
                        val corpo = JSONObject()
                            .put("chave", prefs.chave).put("tipo", "leitura")
                            .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                            .put("km", kmTexto.toLong()).put("periodo", periodo)
                            .put("origem", if (kmTexto == kmLido) "Leitura automática" else "Digitado")
                            .put("fotoBase64", Imagem.paraBase64(f.bitmap))
                        val r = Api.enviar(prefs.url, corpo)
                        aoConcluir(mensagemRetorno(r, periodo))
                    } catch (e: Exception) {
                        erro = "Não foi possível enviar: ${e.message}"
                    } finally { ocupado = false }
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (ocupado) "Enviando..." else "Enviar") }
    }
}

private fun mensagemRetorno(r: JSONObject, periodo: String?): String {
    val partes = mutableListOf("Registro enviado com sucesso.")
    if (periodo == Lembretes.FIM) partes += "Km rodados hoje: ${km(r.opt("kmDoDia"))}."
    val s = r.optJSONObject("situacao")
    if (s != null && s.optString("situacao") == "Abastecer") {
        partes += "Atenção: o veículo precisa ser abastecido (restam ${km(s.opt("kmRestantes"))}). O responsável foi avisado."
    }
    return partes.joinToString("\n")
}

// ---------------------------------------------------------------- Abastecimento

@Composable
private fun TelaAbastecer(prefs: Prefs, aoConcluir: (String) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val br = remember { NumberFormat.getNumberInstance(Locale("pt", "BR")).apply { maximumFractionDigits = 2; minimumFractionDigits = 2 } }
    var nota by remember { mutableStateOf<Foto?>(null) }
    var valor by remember { mutableStateOf(br.format(prefs.valorPadrao)) }
    var litros by remember { mutableStateOf("") }
    var kmCoube by remember { mutableStateOf("") }
    var kmCoubeEditado by remember { mutableStateOf(false) }
    var kmPainel by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var confirmaDesfocada by remember { mutableStateOf(false) }
    var kmReferencia by remember { mutableStateOf<Long?>(null) }

    fun atualizarKmCoube() {
        if (kmCoubeEditado) return
        val l = Ocr.numero(litros) ?: return
        kmCoube = Math.round(l * prefs.consumoKmL).toString()
    }

    LaunchedEffect(Unit) {
        try {
            val vs = Api.consultar(prefs.url, prefs.chave).getJSONArray("veiculos")
            kmReferencia = (0 until vs.length()).map { vs.getJSONObject(it) }
                .firstOrNull { it.optString("placa") == prefs.placa }?.optLong("kmAtual")
            if (kmPainel.isBlank()) kmReferencia?.takeIf { it > 0 }?.let { kmPainel = it.toString() }
        } catch (_: Exception) { }
    }

    val cameraNota = rememberCamera { uri ->
        ocupado = true; erro = null; confirmaDesfocada = false
        escopo.launch {
            try {
                val f = processar(ctx, uri)
                nota = f
                f.texto?.let { Ocr.extrairNota(it) }?.let { d ->
                    d.valor?.let { valor = br.format(it) }
                    d.litros?.let { litros = br.format(it); atualizarKmCoube() }
                }
                val poucoTexto = (f.texto?.text?.length ?: 0) < 40
                if (poucoTexto) erro = "Não consegui ler a nota. Tire outra foto mais nítida e de perto."
            } catch (e: Exception) {
                erro = "Erro ao processar a foto: ${e.message}"
            } finally { ocupado = false }
        }
    }
    val cameraPainel = rememberCamera { uri ->
        ocupado = true; erro = null
        escopo.launch {
            try {
                val f = processar(ctx, uri)
                f.texto?.let { Ocr.extrairKm(it, kmReferencia) }?.let { kmPainel = it.toString() }
                    ?: run { erro = "Não consegui ler o km do painel. Digite o valor." }
            } catch (e: Exception) {
                erro = "Erro ao processar a foto: ${e.message}"
            } finally { ocupado = false }
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Abastecimento", style = MaterialTheme.typography.headlineSmall)
        Text("1. Fotografe a nota do posto. Ela deve estar nítida e inteira na foto.")
        Button(onClick = cameraNota, enabled = !ocupado, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (nota == null) "Fotografar a nota" else "Tirar outra foto da nota")
        }
        Miniatura(nota)
        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (nota != null) {
            Text("2. Confira os dados.")
            OutlinedTextField(
                valor, { valor = campoNumero(it) }, label = { Text("Valor (R$)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                litros, { litros = campoNumero(it); atualizarKmCoube() }, label = { Text("Litros") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                kmCoube, { kmCoube = it.filter(Char::isDigit); kmCoubeEditado = true },
                label = { Text("Km que coube no tanque") },
                supportingText = { Text("Calculado como litros x ${prefs.consumoKmL.toInt()} km/l. Ajuste se necessário.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("3. Km do painel no momento do abastecimento.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    kmPainel, { kmPainel = it.filter(Char::isDigit) }, label = { Text("Km do painel") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = cameraPainel, enabled = !ocupado) { Text("Foto") }
            }
            if (!nota!!.nitida) {
                LinhaOpcao("A nota está legível; enviar mesmo assim", confirmaDesfocada) { confirmaDesfocada = !confirmaDesfocada }
            }
        }
        erro?.let { Text(it, color = VERMELHO) }

        val pronto = nota != null && kmCoube.isNotBlank() && kmPainel.isNotBlank() && (nota!!.nitida || confirmaDesfocada)
        Button(
            enabled = !ocupado && pronto,
            onClick = {
                val f = nota ?: return@Button
                ocupado = true; erro = null
                escopo.launch {
                    try {
                        val corpo = JSONObject()
                            .put("chave", prefs.chave).put("tipo", "abastecimento")
                            .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                            .put("km", kmPainel.toLong())
                            .put("valor", Ocr.numero(valor) ?: 0.0)
                            .put("litros", Ocr.numero(litros) ?: 0.0)
                            .put("kmCoube", kmCoube.toLong())
                            .put("fotoBase64", Imagem.paraBase64(f.bitmap, 1600))
                        val r = Api.enviar(prefs.url, corpo)
                        aoConcluir(mensagemRetorno(r, null))
                    } catch (e: Exception) {
                        erro = "Não foi possível enviar: ${e.message}"
                    } finally { ocupado = false }
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (ocupado) "Enviando..." else "Enviar abastecimento") }
    }
}
