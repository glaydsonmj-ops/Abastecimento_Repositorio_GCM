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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

private val BR = Locale("pt", "BR")
private val CANTO = RoundedCornerShape(20.dp)
private val CANTO_CAMPO = RoundedCornerShape(14.dp)

class MainActivity : ComponentActivity() {
    private val pedido = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lembretes.criarCanal(this)
        pedido.value = intent.getStringExtra(Lembretes.EXTRA_PERIODO)
        setContent {
            TemaGcm {
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
private enum class Etapa(val numero: Int, val titulo: String, val botao: String, val descricao: String) {
    INICIO(1, "Início do turno", "OK", "8h · foto do painel e km"),
    ABASTECIMENTO(2, "Abastecimento", "Enviar abastecimento", "Nota fiscal, valor, litros e km"),
    FIM(3, "Fim do turno", "Encerrar turno", "17h · foto do painel e km"),
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
    val podeVoltar = tela is Tela.Registro || (tela is Tela.Escolher && prefs.configurado)
    BackHandler(enabled = podeVoltar) { tela = Tela.Inicio }

    val hojeTexto = SimpleDateFormat("dd/MM/yyyy", BR).format(Date())
    Column(Modifier.fillMaxSize()) {
        when (val t = tela) {
            Tela.Escolher -> Cabecalho("Abastecimento GCM", "Identificação do técnico", if (podeVoltar) ({ tela = Tela.Inicio }) else null)
            Tela.Inicio -> Cabecalho("Olá, ${prefs.tecnico.substringBefore(' ')}", "Veículo ${prefs.placa} · $hojeTexto", null)
            is Tela.Registro -> Cabecalho(t.etapa.titulo, "Etapa ${t.etapa.numero} de 3 · $hojeTexto") { tela = Tela.Inicio }
        }
        FaixaMarca()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val t = tela) {
                Tela.Escolher -> TelaEscolher(prefs) { Lembretes.agendar(ctx); tela = Tela.Inicio }
                Tela.Inicio -> TelaInicio(prefs, aviso, { aviso = null; tela = Tela.Registro(it) }, { tela = Tela.Escolher })
                is Tela.Registro -> TelaRegistro(prefs, t.etapa) { msg -> aviso = msg; tela = Tela.Inicio }
            }
        }
    }
}

/** Cabeçalho azul-marinho com o símbolo da GCM. */
@Composable
private fun Cabecalho(titulo: String, subtitulo: String, aoVoltar: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().background(Cores.CABECALHO)) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (aoVoltar != null) {
                IconButton(onClick = aoVoltar, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
            } else {
                Box(Modifier.size(46.dp).background(Color.White, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    SimboloGcm(34.dp)
                }
            }
            Column {
                Text(titulo, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(subtitulo, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
        }
    }
}

private fun Modifier.tela() = this.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)

private fun km(v: Any?): String {
    val n = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return "-"
    return NumberFormat.getIntegerInstance(BR).format(n) + " km"
}

private fun reais(v: Double): String = NumberFormat.getCurrencyInstance(BR).format(v)

private fun dec(v: Double, casas: Int = 2): String =
    NumberFormat.getNumberInstance(BR).apply { minimumFractionDigits = casas; maximumFractionDigits = casas }.format(v)

/** Cartão branco padrão. */
@Composable
private fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = CANTO,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = conteudo)
    }
}

/** Faixa de aviso colorida (sucesso, alerta ou erro). */
@Composable
private fun Aviso(texto: String, fundo: Color, cor: Color, icone: ImageVector) {
    Row(
        Modifier.fillMaxWidth().background(fundo, CANTO_CAMPO).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icone, contentDescription = null, tint = cor, modifier = Modifier.size(22.dp))
        Text(texto, color = cor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Erro(texto: String) = Aviso(texto, Cores.VERMELHO_CLARO, Cores.VERMELHO, Icons.Filled.Warning)

@Composable
private fun Campo(valor: String, aoMudar: (String) -> Unit, rotulo: String, teclado: KeyboardType) {
    OutlinedTextField(
        valor, aoMudar, label = { Text(rotulo) }, singleLine = true, shape = CANTO_CAMPO,
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth()
    )
}

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
        Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LogoGcm(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp))
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Cores.MAGENTA)
                    Text("Chave de acesso", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("Digite a chave fornecida pelo responsável. Isso é pedido só uma vez.", color = Cores.SUAVE)
                Campo(chaveTexto, { chaveTexto = it.trim() }, "Chave", KeyboardType.Text)
                BotaoMarca("Continuar", { prefs.chave = chaveTexto; temChave = true }, habilitado = chaveTexto.isNotBlank())
            }
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

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LogoGcm(Modifier.fillMaxWidth().height(130.dp))
        Text("Quem é você?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (carregando) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
        erro?.let {
            Erro(it)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tentativa++ }, shape = CANTO_CAMPO) { Text("Tentar de novo") }
                TextButton(onClick = { prefs.chave = ""; temChave = false }) { Text("Trocar chave") }
            }
        }
        tecnicos.forEach { t ->
            val nome = t.optString("nome")
            CartaoOpcao(nome, Icons.Filled.Person, tecnico == nome) {
                tecnico = nome
                t.optString("placa").takeIf { it.isNotBlank() }?.let { placa = it }
            }
        }
        if (tecnicos.isNotEmpty() && placas.size > 1) {
            Text("Veículo", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            placas.forEach { p -> CartaoOpcao(p, null, placa == p) { placa = p } }
        }
        if (tecnicos.isNotEmpty()) {
            BotaoMarca(
                "Continuar", { prefs.tecnico = tecnico; prefs.placa = placa; aoConcluir() },
                habilitado = tecnico.isNotBlank() && placa.isNotBlank()
            )
        }
    }
}

@Composable
private fun CartaoOpcao(texto: String, icone: ImageVector?, selecionado: Boolean, aoClicar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CANTO_CAMPO)
            .background(if (selecionado) Cores.LARANJA_CLARO else Color.White)
            .border(2.dp, if (selecionado) Cores.LARANJA else Cores.LINHA, CANTO_CAMPO)
            .clickable(onClick = aoClicar).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icone != null) Icon(icone, contentDescription = null, tint = if (selecionado) Cores.MAGENTA else Cores.SUAVE)
        Text(texto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (selecionado) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Cores.MAGENTA)
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

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        aviso?.let { Aviso(it, Cores.VERDE_CLARO, Cores.VERDE, Icons.Filled.CheckCircle) }

        // Resumo do dia: sempre visível
        Cartao {
            Text("Resumo de hoje", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val h = hoje
            if (h == null) {
                if (erro != null) Erro(erro!!) else LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
            } else {
                val kmDia = h.optDouble("kmRodados", 0.0)
                val litros = h.optDouble("litros", 0.0)
                val preco = h.optDouble("precoLitro", 0.0)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Indicador("Km rodados", km(kmDia), Cores.AZUL, Modifier.weight(1f))
                    Indicador("Combustível gasto", reais(h.optDouble("gasto", 0.0)), Cores.MAGENTA, Modifier.weight(1f))
                }
                Text(
                    if (preco > 0) "${km(kmDia)} ÷ ${prefs.consumoKmL.toInt()} km/l = ${dec(litros)} litros × ${reais(preco)} por litro"
                    else "O gasto aparece depois do primeiro abastecimento registrado.",
                    color = Cores.SUAVE, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Cores.FUNDO, CANTO_CAMPO).padding(10.dp)
                )
            }
        }

        veiculo?.let { v ->
            when (v.optString("situacao")) {
                "Abastecer" -> Aviso("Abasteça o veículo: restam ${km(v.opt("kmRestantes"))}.", Cores.VERMELHO_CLARO, Cores.VERMELHO, Icons.Filled.Warning)
                "OK" -> Aviso("Tanque: restam cerca de ${km(v.opt("kmRestantes"))}.", Cores.VERDE_CLARO, Cores.VERDE, Icons.Filled.CheckCircle)
            }
        }

        Text("Etapas do dia", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        val h = hoje
        CartaoEtapa(Etapa.INICIO, rememberVectorPainterIcon(Icons.Filled.PlayArrow), h?.optBoolean("inicioFeito") == true) { aoAbrir(Etapa.INICIO) }
        CartaoEtapa(Etapa.ABASTECIMENTO, painterResource(R.drawable.ic_notificacao), h?.optBoolean("abastecimentoFeito") == true) { aoAbrir(Etapa.ABASTECIMENTO) }
        CartaoEtapa(Etapa.FIM, rememberVectorPainterIcon(Icons.AutoMirrored.Filled.ExitToApp), h?.optBoolean("fimFeito") == true) { aoAbrir(Etapa.FIM) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { atualizar++ }, shape = CANTO_CAMPO) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Atualizar")
            }
            TextButton(onClick = aoTrocar) { Text("Trocar técnico", color = Cores.SUAVE) }
        }
    }
}

@Composable
private fun rememberVectorPainterIcon(v: ImageVector) = androidx.compose.ui.graphics.vector.rememberVectorPainter(v)

@Composable
private fun Indicador(rotulo: String, valor: String, cor: Color, modifier: Modifier) {
    Column(
        modifier.background(cor.copy(alpha = 0.08f), CANTO_CAMPO).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(rotulo, color = Cores.SUAVE, fontSize = 13.sp)
        Text(valor, color = cor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CartaoEtapa(etapa: Etapa, icone: androidx.compose.ui.graphics.painter.Painter, feito: Boolean, aoClicar: () -> Unit) {
    Card(
        onClick = aoClicar, shape = CANTO, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (feito) Cores.VERDE_CLARO else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (feito) 0.dp else 2.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (feito) Cores.CONCLUIDO else Cores.MARCA),
                contentAlignment = Alignment.Center
            ) {
                if (feito) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                else Icon(icone, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${etapa.numero}. ${etapa.titulo}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (feito) "Concluído hoje" else etapa.descricao, color = if (feito) Cores.VERDE else Cores.SUAVE, fontSize = 14.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Cores.SUAVE)
        }
    }
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

/** Bloco de foto: área para fotografar, miniatura e aviso de foto ilegível. */
@Composable
private fun BlocoFoto(rotulo: String, foto: Foto?, ocupado: Boolean, aoFotografar: () -> Unit) {
    if (foto == null) {
        Column(
            Modifier.fillMaxWidth().height(150.dp).clip(CANTO_CAMPO)
                .background(Cores.LARANJA_CLARO)
                .border(2.dp, Cores.LARANJA.copy(alpha = 0.5f), CANTO_CAMPO)
                .clickable(enabled = !ocupado, onClick = aoFotografar),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Cores.MARCA), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(rotulo, fontWeight = FontWeight.Bold, color = Cores.TEXTO, textAlign = TextAlign.Center)
        }
    } else {
        Image(
            foto.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(CANTO_CAMPO)
        )
        if (!foto.nitida) Erro("A foto não está legível. Tire outra com o celular firme e boa iluminação.")
        OutlinedButton(onClick = aoFotografar, enabled = !ocupado, shape = CANTO_CAMPO, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tirar outra foto")
        }
    }
}

/** Título numerado de uma seção do registro. */
@Composable
private fun TituloSecao(numero: Int, titulo: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Cores.MARINHO), contentAlignment = Alignment.Center) {
            Text("$numero", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (etapa == Etapa.ABASTECIMENTO) {
            Cartao {
                TituloSecao(1, "Nota fiscal")
                BlocoFoto("Fotografar a nota fiscal", fotoNota, ocupado, cameraNota)
                if (fotoNota != null) {
                    Campo(valor, { valor = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, "Valor colocado (R$)", KeyboardType.Decimal)
                    Campo(litros, { litros = it.filter { c -> c.isDigit() || c == ',' || c == '.' } }, "Litros que couberam no tanque", KeyboardType.Decimal)
                    if (litrosNum != null && litrosNum > 0) {
                        val preco = if (valorNum != null && valorNum > 0) " · ${reais(valorNum / litrosNum)} por litro" else ""
                        Text(
                            "Rende cerca de ${km(litrosNum * prefs.consumoKmL)}$preco",
                            color = Cores.AZUL, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(Cores.AZUL.copy(alpha = 0.08f), CANTO_CAMPO).padding(12.dp)
                        )
                    }
                }
            }
        }

        Cartao {
            TituloSecao(if (etapa == Etapa.ABASTECIMENTO) 2 else 1, "Km do painel")
            BlocoFoto("Fotografar o painel", fotoPainel, ocupado, cameraPainel)
            if (fotoPainel != null) {
                Campo(kmTexto, { kmTexto = it.filter(Char::isDigit) }, "Digite o km que aparece no painel", KeyboardType.Number)
                val lido = fotoPainel?.kmLido
                if (kmDigitado != null && lido != null && kmTexto.length >= 3 && abs(lido - kmDigitado) > 2) {
                    Aviso("Confira: na foto parece estar ${km(lido)}.", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
                }
            }
        }

        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
        erro?.let { Erro(it) }

        BotaoMarca(
            if (ocupado) "Enviando..." else etapa.botao,
            onClick = {
                val painel = fotoPainel ?: return@BotaoMarca
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
            },
            habilitado = !ocupado && pronto,
            altura = 64.dp,
            icone = { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
        )
        Spacer(Modifier.height(8.dp))
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
