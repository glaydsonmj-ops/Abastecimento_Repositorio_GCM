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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val AZUL = Color(0xFF1F4F8C)
private val VERMELHO = Color(0xFFB3261E)
private val VERDE = Color(0xFF2E7D32)
private val BR = Locale("pt", "BR")

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

/** As três etapas do dia. */
private enum class Etapa(val titulo: String, val botao: String) {
    INICIO("Início do turno", "OK"),
    ABASTECIMENTO("Abastecimento", "Enviar abastecimento"),
    FIM("Fim do turno", "Encerrar turno"),
}

private sealed interface Tela {
    data object Escolher : Tela
    data object Inicio : Tela
    data class Registro(val etapa: Etapa) : Tela
}

/** Foto já processada: imagem com data e hora gravadas, nitidez e km lido. */
private class Foto(val bitmap: Bitmap, val nitida: Boolean, val kmLido: Long?)

@Composable
private fun App(pedido: MutableState<String?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tela by remember { mutableStateOf<Tela>(if (prefs.configurado) Tela.Inicio else Tela.Escolher) }
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
        if (p != null && prefs.configurado) {
            tela = Tela.Registro(if (p == Lembretes.FIM) Etapa.FIM else Etapa.INICIO)
        }
        pedido.value = null
    }
    BackHandler(enabled = tela is Tela.Registro || (tela is Tela.Escolher && prefs.configurado)) {
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
                Tela.Escolher -> TelaEscolher(prefs) { Lembretes.agendar(ctx); tela = Tela.Inicio }
                Tela.Inicio -> TelaInicio(prefs, aviso, { aviso = null; tela = Tela.Registro(it) }, { tela = Tela.Escolher })
                is Tela.Registro -> TelaRegistro(prefs, t.etapa) { msg -> aviso = msg; tela = Tela.Inicio }
            }
        }
    }
}

private fun Modifier.tela() = this.fillMaxSize().padding(16.dp)

private fun km(v: Any?): String {
    val n = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return "-"
    return NumberFormat.getIntegerInstance(BR).format(n) + " km"
}

private fun reais(v: Double): String = NumberFormat.getCurrencyInstance(BR).format(v)

private fun dec(v: Double, casas: Int = 2): String =
    NumberFormat.getNumberInstance(BR).apply { minimumFractionDigits = casas; maximumFractionDigits = casas }.format(v)

// ---------------------------------------------------------------- Escolher técnico

@Composable
private fun TelaEscolher(prefs: Prefs, aoConcluir: () -> Unit) {
    var carregando by remember { mutableStateOf(true) }
    var erro by remember { mutableStateOf<String?>(null) }
    var tecnicos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var placas by remember { mutableStateOf<List<String>>(emptyList()) }
    var tecnico by remember { mutableStateOf(prefs.tecnico) }
    var placa by remember { mutableStateOf(prefs.placa) }
    var tentativa by remember { mutableIntStateOf(0) }
    var temChave by remember { mutableStateOf(prefs.chave.isNotBlank()) }
    var chaveTexto by remember { mutableStateOf("") }

    if (!temChave) {
        Column(Modifier.tela(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chave de acesso", style = MaterialTheme.typography.headlineSmall)
            Text("Digite a chave fornecida pelo responsável. Isso é pedido só uma vez.")
            OutlinedTextField(chaveTexto, { chaveTexto = it.trim() }, label = { Text("Chave") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = chaveTexto.isNotBlank(),
                onClick = { prefs.chave = chaveTexto; temChave = true },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Continuar") }
        }
        return
    }

    LaunchedEffect(tentativa) {
        carregando = true; erro = null
        try {
            val r = Api.consultar(prefs.url, prefs.chave)
            val ts = r.getJSONArray("tecnicos")
            tecnicos = (0 until ts.length()).map { ts.getJSONObject(it) }
            val vs = r.getJSONArray("veiculos")
            placas = (0 until vs.length()).map { vs.getJSONObject(it).optString("placa") }
            r.optJSONObject("config")?.let { c ->
                prefs.consumoKmL = c.optDouble("consumoKmL", 10.0)
                prefs.valorPadrao = c.optDouble("valorPadrao", 180.0)
                prefs.lembreteInicio = c.optString("lembreteInicio", "08:00")
                prefs.lembreteFim = c.optString("lembreteFim", "17:00")
            }
            if (placa.isBlank() && placas.size == 1) placa = placas.first()
            if (tecnicos.isEmpty()) erro = "Nenhum técnico cadastrado. Peça ao responsável para cadastrar você na planilha."
        } catch (e: Exception) {
            erro = if (e.message?.contains("Chave", ignoreCase = true) == true) "Chave de acesso inválida." else "Sem conexão com o sistema. Verifique a internet e tente de novo."
        } finally {
            carregando = false
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quem é você?", style = MaterialTheme.typography.headlineSmall)
        if (carregando) LinearProgressIndicator(Modifier.fillMaxWidth())
        erro?.let {
            Text(it, color = VERMELHO)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tentativa++ }) { Text("Tentar de novo") }
                TextButton(onClick = { prefs.chave = ""; temChave = false }) { Text("Trocar chave") }
            }
        }
        tecnicos.forEach { t ->
            val nome = t.optString("nome")
            LinhaOpcao(nome, tecnico == nome) {
                tecnico = nome
                t.optString("placa").takeIf { it.isNotBlank() }?.let { placa = it }
            }
        }
        if (tecnicos.isNotEmpty() && placas.size > 1) {
            Text("Veículo", fontWeight = FontWeight.Bold)
            placas.forEach { p -> LinhaOpcao(p, placa == p) { placa = p } }
        }
        if (tecnicos.isNotEmpty()) {
            Button(
                enabled = tecnico.isNotBlank() && placa.isNotBlank(),
                onClick = { prefs.tecnico = tecnico; prefs.placa = placa; aoConcluir() },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Continuar") }
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
        Text(texto, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------- Tela principal

@Composable
private fun TelaInicio(prefs: Prefs, aviso: String?, aoAbrir: (Etapa) -> Unit, aoTrocar: () -> Unit) {
    var hoje by remember { mutableStateOf<JSONObject?>(null) }
    var veiculo by remember { mutableStateOf<JSONObject?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var atualizar by remember { mutableIntStateOf(0) }

    LaunchedEffect(atualizar) {
        erro = null
        try {
            val url = prefs.url + "?tecnico=" + Uri.encode(prefs.tecnico) + "&placa=" + Uri.encode(prefs.placa)
            val r = Api.consultar(url, prefs.chave)
            hoje = r.optJSONObject("hoje")
            val vs = r.getJSONArray("veiculos")
            veiculo = (0 until vs.length()).map { vs.getJSONObject(it) }.firstOrNull { it.optString("placa") == prefs.placa }
            r.optJSONObject("config")?.let { c ->
                prefs.consumoKmL = c.optDouble("consumoKmL", prefs.consumoKmL)
                prefs.valorPadrao = c.optDouble("valorPadrao", prefs.valorPadrao)
            }
        } catch (e: Exception) {
            erro = "Sem conexão com o sistema. Toque em Atualizar."
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Olá, ${prefs.tecnico}", style = MaterialTheme.typography.headlineSmall)
        Text("Veículo: ${prefs.placa}  ·  ${SimpleDateFormat("dd/MM/yyyy", BR).format(Date())}")

        aviso?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FA))) { Text(it, Modifier.padding(12.dp)) }
        }

        // Resumo do dia: sempre visível
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5EC))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hoje", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val h = hoje
                if (h == null) {
                    Text(erro ?: "Carregando...", color = if (erro != null) VERMELHO else Color.Unspecified)
                } else {
                    val kmDia = h.optDouble("kmRodados", 0.0)
                    val litros = h.optDouble("litros", 0.0)
                    val preco = h.optDouble("precoLitro", 0.0)
                    Text("Km rodados: ${km(kmDia)}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Combustível gasto: ${reais(h.optDouble("gasto", 0.0))}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (preco > 0) "${km(kmDia)} ÷ ${prefs.consumoKmL.toInt()} km/l = ${dec(litros)} litros × ${reais(preco)} por litro"
                        else "O gasto aparece depois do primeiro abastecimento registrado.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        veiculo?.let { v ->
            val situacao = v.optString("situacao")
            if (situacao == "Abastecer" || situacao == "OK") {
                Text(
                    if (situacao == "Abastecer") "Atenção: abasteça o veículo. Restam ${km(v.opt("kmRestantes"))}."
                    else "Combustível: restam cerca de ${km(v.opt("kmRestantes"))}.",
                    color = if (situacao == "Abastecer") VERMELHO else VERDE, fontWeight = FontWeight.Bold
                )
            }
        }

        val h = hoje
        BotaoEtapa("1. Início do turno (8h)", h?.optBoolean("inicioFeito") == true) { aoAbrir(Etapa.INICIO) }
        BotaoEtapa("2. Abastecimento", h?.optBoolean("abastecimentoFeito") == true) { aoAbrir(Etapa.ABASTECIMENTO) }
        BotaoEtapa("3. Fim do turno (17h)", h?.optBoolean("fimFeito") == true) { aoAbrir(Etapa.FIM) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { atualizar++ }) { Text("Atualizar") }
            TextButton(onClick = aoTrocar) { Text("Trocar técnico") }
        }
    }
}

@Composable
private fun BotaoEtapa(texto: String, feito: Boolean, aoClicar: () -> Unit) {
    Button(
        onClick = aoClicar,
        colors = if (feito) ButtonDefaults.buttonColors(containerColor = VERDE) else ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) { Text(if (feito) "$texto  ✓" else texto, fontSize = 18.sp) }
}

// ---------------------------------------------------------------- Fotos

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

private suspend fun processar(ctx: Context, uri: Uri, prefs: Prefs, lerKm: Boolean): Foto {
    val original = withContext(Dispatchers.Default) { Imagem.carregar(ctx, uri) }
    val nitida = withContext(Dispatchers.Default) { Imagem.nitidez(original) >= Imagem.NITIDEZ_MINIMA }
    val kmLido = if (lerKm) try { Ocr.extrairKm(Ocr.ler(original), null) } catch (e: Exception) { null } else null
    val carimbo = SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(Date()) + "  " + prefs.tecnico + "  " + prefs.placa
    val final = withContext(Dispatchers.Default) { Imagem.carimbar(original, carimbo) }
    return Foto(final, nitida, kmLido)
}

/** Bloco de foto: botão, miniatura e aviso de foto ilegível. */
@Composable
private fun BlocoFoto(rotulo: String, foto: Foto?, ocupado: Boolean, aoFotografar: () -> Unit) {
    Button(onClick = aoFotografar, enabled = !ocupado, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (foto == null) rotulo else "Tirar outra foto")
    }
    if (foto != null) {
        Image(
            foto.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
        )
        if (!foto.nitida) Text("A foto não está legível. Tire outra foto com o celular firme e boa iluminação.", color = VERMELHO)
    }
}

// ---------------------------------------------------------------- Registro (início, abastecimento, fim)

@Composable
private fun TelaRegistro(prefs: Prefs, etapa: Etapa, aoConcluir: (String) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var fotoNota by remember { mutableStateOf<Foto?>(null) }
    var fotoPainel by remember { mutableStateOf<Foto?>(null) }
    var tentativasNota by remember { mutableIntStateOf(0) }
    var tentativasPainel by remember { mutableIntStateOf(0) }
    var valor by remember { mutableStateOf(dec(prefs.valorPadrao)) }
    var litros by remember { mutableStateOf("") }
    var kmTexto by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    val cameraNota = rememberCamera { uri ->
        ocupado = true; erro = null
        escopo.launch {
            try { fotoNota = processar(ctx, uri, prefs, lerKm = false); tentativasNota++ }
            catch (e: Exception) { erro = "Erro ao processar a foto. Tente novamente." }
            finally { ocupado = false }
        }
    }
    val cameraPainel = rememberCamera { uri ->
        ocupado = true; erro = null
        escopo.launch {
            try { fotoPainel = processar(ctx, uri, prefs, lerKm = true); tentativasPainel++ }
            catch (e: Exception) { erro = "Erro ao processar a foto. Tente novamente." }
            finally { ocupado = false }
        }
    }

    // Uma foto desfocada só é aceita depois de duas tentativas, para não travar o técnico.
    fun aceita(f: Foto?, tentativas: Int) = f != null && (f.nitida || tentativas >= 2)

    val kmDigitado = kmTexto.toLongOrNull()
    val litrosNum = Ocr.numero(litros)
    val valorNum = Ocr.numero(valor)
    val pronto = aceita(fotoPainel, tentativasPainel) && kmDigitado != null && kmDigitado > 0 &&
        (etapa != Etapa.ABASTECIMENTO ||
            (aceita(fotoNota, tentativasNota) && litrosNum != null && litrosNum > 0 && valorNum != null && valorNum > 0))

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(etapa.titulo, style = MaterialTheme.typography.headlineSmall)

        if (etapa == Etapa.ABASTECIMENTO) {
            Text("Nota fiscal", fontWeight = FontWeight.Bold)
            BlocoFoto("Fotografar a nota fiscal", fotoNota, ocupado, cameraNota)
            if (fotoNota != null) {
                OutlinedTextField(
                    valor, { valor = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                    label = { Text("Valor colocado (R$)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    litros, { litros = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                    label = { Text("Litros que couberam no tanque") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (litrosNum != null && litrosNum > 0) {
                    val preco = if (valorNum != null && valorNum > 0) " · ${reais(valorNum / litrosNum)} por litro" else ""
                    Text("Rende cerca de ${km(litrosNum * prefs.consumoKmL)}$preco", color = AZUL)
                }
            }
            Text("Km do painel", fontWeight = FontWeight.Bold)
        }

        BlocoFoto("Fotografar o painel (km)", fotoPainel, ocupado, cameraPainel)
        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (fotoPainel != null) {
            OutlinedTextField(
                kmTexto, { kmTexto = it.filter(Char::isDigit) },
                label = { Text("Digite o km que aparece no painel") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            val lido = fotoPainel?.kmLido
            if (kmDigitado != null && lido != null && kmTexto.length >= 3 && abs(lido - kmDigitado) > 2) {
                Text("Confira: na foto parece estar ${km(lido)}.", color = VERMELHO)
            }
        }
        erro?.let { Text(it, color = VERMELHO) }

        Button(
            enabled = !ocupado && pronto,
            onClick = {
                val painel = fotoPainel ?: return@Button
                ocupado = true; erro = null
                escopo.launch {
                    try {
                        val corpo = JSONObject()
                            .put("chave", prefs.chave)
                            .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                            .put("km", kmDigitado)
                            .put("origem", if (painel.kmLido == kmDigitado) "Digitado (confere com a foto)" else "Digitado")
                        if (etapa == Etapa.ABASTECIMENTO) {
                            val l = litrosNum ?: 0.0
                            corpo.put("tipo", "abastecimento")
                                .put("valor", valorNum ?: 0.0)
                                .put("litros", l)
                                .put("kmCoube", Math.round(l * prefs.consumoKmL))
                                .put("fotoBase64", withContext(Dispatchers.Default) { Imagem.paraBase64(fotoNota!!.bitmap, 1600) })
                                .put("fotoPainelBase64", withContext(Dispatchers.Default) { Imagem.paraBase64(painel.bitmap) })
                        } else {
                            corpo.put("tipo", "leitura")
                                .put("periodo", if (etapa == Etapa.INICIO) Lembretes.INICIO else Lembretes.FIM)
                                .put("fotoBase64", withContext(Dispatchers.Default) { Imagem.paraBase64(painel.bitmap) })
                        }
                        val r = Api.enviar(prefs.url, corpo)
                        aoConcluir(mensagemRetorno(r, etapa, prefs))
                    } catch (e: Exception) {
                        erro = "Não foi possível enviar: ${e.message}. Verifique a internet e tente de novo."
                    } finally { ocupado = false }
                }
            }, modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(if (ocupado) "Enviando..." else etapa.botao, fontSize = 18.sp) }
    }
}

private fun mensagemRetorno(r: JSONObject, etapa: Etapa, prefs: Prefs): String {
    val partes = mutableListOf(
        when (etapa) {
            Etapa.INICIO -> "Início do turno registrado. Bom trabalho!"
            Etapa.ABASTECIMENTO -> "Abastecimento registrado."
            Etapa.FIM -> "Turno encerrado."
        }
    )
    r.optJSONObject("hoje")?.let { h ->
        if (etapa == Etapa.FIM || h.optDouble("kmRodados", 0.0) > 0) {
            partes += "Hoje você rodou ${km(h.opt("kmRodados"))} e gastou ${reais(h.optDouble("gasto", 0.0))} de combustível."
        }
    }
    val s = r.optJSONObject("situacao")
    if (s != null && s.optString("situacao") == "Abastecer") {
        partes += "Atenção: o veículo precisa ser abastecido (restam ${km(s.opt("kmRestantes"))}). O responsável foi avisado."
    }
    return partes.joinToString("\n")
}
