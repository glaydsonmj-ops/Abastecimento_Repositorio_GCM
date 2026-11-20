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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

internal val BR = Locale("pt", "BR")
internal val CANTO = RoundedCornerShape(20.dp)
internal val CANTO_CAMPO = RoundedCornerShape(14.dp)

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
    data object Checklists : Tela
    data object Relatorio : Tela
    data class Checklist(val nome: String, val itens: List<String>) : Tela
}

/** Foto já processada: imagem com data e hora gravadas, nitidez e km lido. */
private class Foto(val bitmap: Bitmap, val nitida: Boolean, val kmLido: Long?, val nota: Ocr.DadosNota? = null)

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
        Fila.agendar(ctx)
    }
    LaunchedEffect(pedido.value) {
        val p = pedido.value
        if (p != null && prefs.configurado) {
            // Às 8h a tela inicial pergunta primeiro se o veículo está funcionando.
            tela = if (p == Lembretes.FIM) Tela.Registro(Etapa.FIM) else Tela.Inicio
        }
        pedido.value = null
    }
    // Atualização automática: ao abrir, confere se há versão nova e oferece atualizar com um toque.
    var versaoNova by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { versaoNova = Atualizador.versaoNova() }
    versaoNova?.let { v -> DialogoAtualizacao(v) { versaoNova = null } }

    val podeVoltar = tela !is Tela.Inicio && (tela !is Tela.Escolher || prefs.configurado)
    BackHandler(enabled = podeVoltar) { tela = if (tela is Tela.Checklist) Tela.Checklists else Tela.Inicio }

    val hojeTexto = SimpleDateFormat("dd/MM/yyyy", BR).format(Date())
    Column(Modifier.fillMaxSize()) {
        when (val t = tela) {
            Tela.Escolher -> Cabecalho("Combustível GCM", "Entrar", if (podeVoltar) ({ tela = Tela.Inicio }) else null)
            Tela.Inicio -> Cabecalho("Olá, ${prefs.tecnico.substringBefore(' ')}", "Veículo ${prefs.placa} · $hojeTexto", null)
            is Tela.Registro -> Cabecalho(t.etapa.titulo, "Etapa ${t.etapa.numero} de 3 · $hojeTexto") { tela = Tela.Inicio }
            Tela.Checklists -> Cabecalho("Check-lists", "Segurança do veículo, ferramental e EPI", null)
            Tela.Relatorio -> Cabecalho("Relatório", "Veículo ${prefs.placa}", null)
            is Tela.Checklist -> Cabecalho(t.nome, "Check-list semanal · $hojeTexto") { tela = Tela.Checklists }
        }
        FaixaMarca()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val t = tela) {
                Tela.Escolher -> TelaEscolher(prefs) { Lembretes.agendar(ctx); tela = Tela.Inicio }
                Tela.Inicio -> TelaInicio(prefs, aviso, { aviso = null; tela = Tela.Registro(it) }, { prefs.senha = ""; aviso = null; tela = Tela.Escolher }, { aviso = null; tela = Tela.Checklists })
                is Tela.Registro -> TelaRegistro(prefs, t.etapa) { msg -> aviso = msg; tela = Tela.Inicio }
                Tela.Checklists -> TelaChecklists(prefs, aviso) { nome, itens -> aviso = null; tela = Tela.Checklist(nome, itens) }
                Tela.Relatorio -> TelaRelatorio(prefs)
                is Tela.Checklist -> TelaChecklist(prefs, t.nome, t.itens) { msg -> aviso = msg; tela = Tela.Checklists }
            }
        }
        if (tela == Tela.Inicio || tela == Tela.Checklists || tela == Tela.Relatorio) {
            BarraNavegacao(
                atual = when (tela) { Tela.Checklists -> 1; Tela.Relatorio -> 2; else -> 0 },
                aoEscolher = { i -> aviso = null; tela = when (i) { 1 -> Tela.Checklists; 2 -> Tela.Relatorio; else -> Tela.Inicio } }
            )
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

internal fun Modifier.tela() = this.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)

internal fun km(v: Any?): String {
    val n = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return "-"
    return NumberFormat.getIntegerInstance(BR).format(n) + " km"
}

internal fun reais(v: Double): String = NumberFormat.getCurrencyInstance(BR).format(v)

internal fun dec(v: Double, casas: Int = 2): String =
    NumberFormat.getNumberInstance(BR).apply { minimumFractionDigits = casas; maximumFractionDigits = casas }.format(v)

/** Cartão branco padrão. */
@Composable
internal fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable ColumnScope.() -> Unit) {
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
internal fun Aviso(texto: String, fundo: Color, cor: Color, icone: ImageVector) {
    Row(
        Modifier.fillMaxWidth().background(fundo, CANTO_CAMPO).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icone, contentDescription = null, tint = cor, modifier = Modifier.size(22.dp))
        Text(texto, color = cor, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Erro(texto: String) = Aviso(texto, Cores.VERMELHO_CLARO, Cores.VERMELHO, Icons.Filled.Warning)

@Composable
internal fun Campo(valor: String, aoMudar: (String) -> Unit, rotulo: String, teclado: KeyboardType) {
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
    val escopo = rememberCoroutineScope()
    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var senha by remember { mutableStateOf("") }
    var senha2 by remember { mutableStateOf("") }
    var placas by remember { mutableStateOf<List<String>>(emptyList()) }
    var placa by remember { mutableStateOf("") }
    var nomeLogado by remember { mutableStateOf("") }
    // Celular sem dono ainda: abre direto o cadastro. Depois, só pede a senha do dono.
    var cadastrando by remember { mutableStateOf(prefs.tecnico.isBlank()) }
    var nomeDigitado by remember { mutableStateOf(prefs.tecnico) }
    var outroNome by remember { mutableStateOf(prefs.tecnico.isBlank()) }
    var cad by remember { mutableStateOf(mapOf<String, String>()) }

    fun concluir(nome: String, p: String) {
        prefs.tecnico = nome; prefs.senha = senha; prefs.placa = p
        aoConcluir()
    }
    fun salvarConfig(r: JSONObject) {
        r.optJSONObject("config")?.let { c ->
            prefs.consumoKmL = c.optDouble("consumoKmL", 10.0)
            prefs.valorPadrao = c.optDouble("valorPadrao", 180.0)
            prefs.lembreteInicio = c.optString("lembreteInicio", "08:00")
            prefs.lembreteFim = c.optString("lembreteFim", "17:00")
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LogoGcm(Modifier.fillMaxWidth().height(130.dp))
        if (nomeLogado.isNotBlank()) {
            // Técnico sem veículo definido na planilha: escolhe o veículo uma vez.
            Text("Qual veículo você usa?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            placas.forEach { p -> CartaoOpcao(p, null, placa == p) { placa = p } }
            BotaoMarca("Continuar", { concluir(nomeLogado, placa) }, habilitado = placa.isNotBlank())
            return@Column
        }
        if (cadastrando) {
            // Primeiro acesso: o técnico cadastra o próprio nome, o veículo e a senha.
            Text("Bem-vindo! Faça seu cadastro", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Preencha uma única vez. Depois, o aplicativo só pede a sua senha.", color = Cores.SUAVE)
            Cartao {
                Text("Seus dados", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                CAMPOS_CADASTRO.forEach { (chave, rotulo, teclado) ->
                    Campo(cad[chave] ?: "", { v -> cad = cad + (chave to (if (chave == "placa") v.uppercase() else v)) }, rotulo, teclado)
                }
            }
            Cartao {
                Text("Crie sua senha", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                CampoSenha(senha, { senha = it }, "Senha (4 números)")
                CampoSenha(senha2, { senha2 = it }, "Repita a senha")
            }
            erro?.let { Erro(it) }
            BotaoMarca(
                if (carregando) "Cadastrando..." else "Cadastrar e entrar",
                {
                    val nome = (cad["nome"] ?: "").trim()
                    erro = when {
                        nome.split(Regex(" +")).size < 2 -> "Digite seu nome e sobrenome."
                        (cad["placa"] ?: "").isBlank() -> "Digite a placa do veículo."
                        senha != senha2 -> "As senhas não são iguais."
                        else -> null
                    }
                    if (erro != null) return@BotaoMarca
                    carregando = true
                    escopo.launch {
                        try {
                            val corpo = JSONObject().put("tipo", "cadastro").put("senha", senha)
                            CAMPOS_CADASTRO.forEach { (chave, _, _) -> corpo.put(chave, (cad[chave] ?: "").trim()) }
                            val r = Api.enviar(prefs.url, corpo)
                            salvarConfig(r)
                            concluir(r.optString("tecnico", nome), r.optString("placa"))
                        } catch (e: Exception) {
                            erro = e.message ?: "Sem conexão com o sistema."
                        } finally {
                            carregando = false
                        }
                    }
                },
                habilitado = !carregando && senha.length == 4 && senha2.length == 4
            )
            TextButton(onClick = { cadastrando = false; erro = null; senha = ""; senha2 = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("Já tenho cadastro? Entrar", color = Cores.AZUL, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            return@Column
        }

        // Entrar: o dono do celular só digita a senha.
        Cartao {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Cores.MAGENTA)
                Text(
                    if (outroNome) "Entrar" else "Olá, ${prefs.tecnico.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() }}!",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            if (outroNome) Campo(nomeDigitado, { nomeDigitado = it }, "Seu nome e sobrenome", KeyboardType.Text)
            else Text("Digite sua senha para entrar.", color = Cores.SUAVE)
            CampoSenha(senha, { senha = it }, "Senha (4 números)")
            erro?.let { Erro(it) }
            BotaoMarca(
                if (carregando) "Entrando..." else "Entrar",
                {
                    val nomeTec = nomeDigitado.trim()
                    carregando = true; erro = null
                    escopo.launch {
                        try {
                            val r = Api.enviar(prefs.url, JSONObject().put("tipo", "login").put("tecnico", nomeTec).put("senha", senha))
                            salvarConfig(r)
                            val nome = r.optString("tecnico", nomeTec)
                            val ps = r.optJSONArray("placas")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                            val definida = r.optString("placa").ifBlank { if (ps.size == 1) ps.first() else "" }
                            if (definida.isNotBlank()) concluir(nome, definida)
                            else if (ps.isEmpty()) erro = "Nenhum veículo cadastrado. Fale com o responsável."
                            else { placas = ps; placa = prefs.placa.takeIf { it in ps } ?: ""; nomeLogado = nome }
                        } catch (e: Exception) {
                            erro = e.message ?: "Sem conexão com o sistema."
                        } finally {
                            carregando = false
                        }
                    }
                },
                habilitado = !carregando && senha.length == 4 && nomeDigitado.trim().contains(' ')
            )
        }
        if (!outroNome) {
            TextButton(onClick = { outroNome = true; nomeDigitado = ""; senha = ""; erro = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Não é você? Entrar com outro nome", color = Cores.SUAVE)
            }
        }
        TextButton(onClick = { cadastrando = true; senha = ""; senha2 = ""; erro = null }, modifier = Modifier.fillMaxWidth()) {
            Text("Primeiro acesso? Fazer meu cadastro", color = Cores.AZUL, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** Aviso de versão nova: baixa e abre o instalador do Android, sem desinstalar nem perder os dados. */
@Composable
private fun DialogoAtualizacao(versao: Int, aoFechar: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var baixando by remember { mutableStateOf(false) }
    var progresso by remember { mutableIntStateOf(0) }
    var erro by remember { mutableStateOf<String?>(null) }
    var pediuPermissao by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!baixando) aoFechar() },
        title = { Text("Nova versão disponível", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A versão 1.0.$versao do aplicativo está pronta. Seus dados e sua senha continuam no celular.")
                if (pediuPermissao && !Atualizador.podeInstalar(ctx))
                    Text("Na tela que abriu, ative \"Permitir desta fonte\", volte e toque em Atualizar de novo. Isso só é pedido uma vez.", color = Cores.SUAVE)
                if (baixando) {
                    LinearProgressIndicator(progress = { progresso / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Baixando... $progresso%", color = Cores.SUAVE)
                }
                erro?.let { Text(it, color = Cores.VERMELHO) }
            }
        },
        confirmButton = {
            TextButton(enabled = !baixando, onClick = {
                erro = null
                if (!Atualizador.podeInstalar(ctx)) {
                    pediuPermissao = true
                    Atualizador.abrirPermissao(ctx)
                    return@TextButton
                }
                baixando = true
                escopo.launch {
                    try {
                        val arquivo = Atualizador.baixar(ctx, versao) { progresso = it }
                        Atualizador.instalar(ctx, arquivo)
                    } catch (e: Exception) {
                        erro = e.message ?: "Sem conexão. Tente de novo."
                    } finally {
                        baixando = false
                    }
                }
            }) { Text("Atualizar", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !baixando, onClick = aoFechar) { Text("Depois") } }
    )
}

private val CAMPOS_CADASTRO = listOf(
    Triple("nome", "Seu nome e sobrenome", KeyboardType.Text),
    Triple("email", "Seu e-mail (opcional)", KeyboardType.Email),
    Triple("placa", "Placa do veículo", KeyboardType.Text),
    Triple("modelo", "Modelo do veículo", KeyboardType.Text),
    Triple("ano", "Ano do veículo", KeyboardType.Number),
    Triple("cor", "Cor do veículo", KeyboardType.Text),
)

@Composable
private fun CampoSenha(valor: String, aoMudar: (String) -> Unit, rotulo: String) {
    OutlinedTextField(
        valor, { aoMudar(it.filter(Char::isDigit).take(4)) }, label = { Text(rotulo) }, singleLine = true, shape = CANTO_CAMPO,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp),
        modifier = Modifier.fillMaxWidth()
    )
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
private fun TelaInicio(prefs: Prefs, aviso: String?, aoAbrir: (Etapa) -> Unit, aoTrocar: () -> Unit, aoChecklists: () -> Unit) {
    var hoje by remember { mutableStateOf<JSONObject?>(null) }
    var checklists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var veiculo by remember { mutableStateOf<JSONObject?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var atualizar by remember { mutableIntStateOf(0) }

    var atualizando by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var pendentes by remember { mutableIntStateOf(Fila.quantidade(ctx)) }

    LaunchedEffect(atualizar) {
        erro = null
        fun aplicar(r: JSONObject) {
            hoje = r.optJSONObject("hoje")
            checklists = r.optJSONArray("checklists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            // O responsável pode trocar o veículo do técnico pela gestão de frota.
            r.optString("placa").takeIf { it.isNotBlank() && it != prefs.placa }?.let { prefs.placa = it }
            val vs = r.optJSONArray("veiculos") ?: org.json.JSONArray()
            veiculo = (0 until vs.length()).map { vs.getJSONObject(it) }.firstOrNull { it.optString("placa") == prefs.placa }
            veiculo?.optLong("kmAtual")?.takeIf { it > prefs.ultimoKm }?.let { prefs.ultimoKm = it }
        }
        // Mostra na hora os últimos dados guardados no aparelho e atualiza em seguida.
        val dia = SimpleDateFormat("yyyy-MM-dd", BR).format(Date())
        val chaveCache = dia + "|" + prefs.tecnico + "|" + prefs.placa
        if (hoje == null) {
            try {
                val g = JSONObject(prefs.ultimaConsulta)
                if (g.optString("chave") == chaveCache) aplicar(g.getJSONObject("r"))
            } catch (e: Exception) { /* sem dados guardados */ }
        }
        atualizando = true
        try {
            // Primeiro envia o que ficou guardado sem internet.
            if (Fila.quantidade(ctx) > 0) { Fila.enviar(ctx); pendentes = Fila.quantidade(ctx); if (pendentes > 0) Fila.agendar(ctx) }
            val url = prefs.url + "?tecnico=" + Uri.encode(prefs.tecnico) + "&placa=" + Uri.encode(prefs.placa)
            val r = Api.consultar(url, prefs.senha)
            aplicar(r)
            prefs.ultimaConsulta = JSONObject().put("chave", chaveCache).put("r", r).toString()
            prefs.salvarOleo(veiculo?.optJSONObject("oleo"))
            r.optJSONObject("config")?.let { c ->
                prefs.consumoKmL = c.optDouble("consumoKmL", prefs.consumoKmL)
                prefs.valorPadrao = c.optDouble("valorPadrao", prefs.valorPadrao)
            }
        } catch (e: Exception) {
            // Senha trocada ou apagada na planilha: volta para a tela de login.
            if (e.message?.startsWith("Seu cadastro foi excluído") == true) {
                // Login excluído pelo responsável: o celular esquece o técnico e abre o cadastro.
                prefs.tecnico = ""; prefs.placa = ""; prefs.ultimaConsulta = ""
                aoTrocar()
            } else if (e.message?.startsWith("Entre novamente") == true) aoTrocar()
            else erro = if (hoje != null) "Não foi possível atualizar agora. Mostrando os últimos dados." else "Sem conexão com o sistema. Toque em Atualizar."
        } finally {
            atualizando = false
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (atualizando && hoje != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(Modifier.weight(1f).clip(CircleShape), color = Cores.MAGENTA)
                Text("Atualizando...", color = Cores.SUAVE, fontSize = 13.sp)
            }
        }
        if (erro != null && hoje != null) Aviso(erro!!, Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
        aviso?.let { Aviso(it, Cores.VERDE_CLARO, Cores.VERDE, Icons.Filled.CheckCircle) }
        if (pendentes > 0) Aviso("$pendentes registro(s) guardado(s) no celular, aguardando internet para enviar.", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)

        CartaoAlertas()

        // Checagem diária: o veículo está funcionando?
        hoje?.let { h -> CartaoChecagem(prefs, h) { atualizar++ } }

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
            AvisosPrevistos(v)
            v.optJSONObject("oleo")?.let { o -> CartaoOleo(prefs, o) { nv -> veiculo = nv } }
        }
        val pendentes = checklists.filter { !it.optBoolean("feitoNaSemana") }
        if (pendentes.isNotEmpty()) {
            Card(onClick = aoChecklists, shape = CANTO_CAMPO, colors = CardDefaults.cardColors(containerColor = Cores.LARANJA_CLARO)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Cores.LARANJA)
                    Text(
                        "Check-list da semana pendente: " + pendentes.joinToString(" e ") { it.optString("nome") } + ".",
                        color = Cores.TEXTO, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Cores.SUAVE)
                }
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
internal fun Indicador(rotulo: String, valor: String, cor: Color, modifier: Modifier) {
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
    // Leitura automática no próprio celular: km do hodômetro (painel) ou litros e valor (nota).
    val texto = try { Ocr.ler(original) } catch (e: Exception) { null }
    val kmLido = if (lerKm && texto != null) try { Ocr.extrairKm(texto, prefs.ultimoKm.takeIf { it > 0 }) } catch (e: Exception) { null } else null
    val nota = if (!lerKm && texto != null) try { Ocr.extrairNota(texto) } catch (e: Exception) { null } else null
    val carimbo = SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(Date()) + "  " + prefs.tecnico + "  " + prefs.placa
    val final = withContext(Dispatchers.Default) { Imagem.carimbar(original, carimbo) }
    return Foto(final, nitida, kmLido, nota)
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
            try {
                val f = processar(ctx, uri, prefs, lerKm = false)
                fotoNota = f; tentativasNota++
                // Preenche litros e valor lidos da nota (o técnico confere e corrige se precisar).
                f.nota?.litros?.let { if (litros.isBlank()) litros = dec(it) }
                f.nota?.valor?.let { valor = dec(it) }
            }
            catch (e: Exception) { erro = "Erro ao processar a foto. Tente novamente." }
            finally { ocupado = false }
        }
    }
    val cameraPainel = rememberCamera { uri ->
        ocupado = true; erro = null
        escopo.launch {
            try {
                val f = processar(ctx, uri, prefs, lerKm = true)
                fotoPainel = f; tentativasPainel++
                // Km lido da foto já vem preenchido; o técnico só confere.
                f.kmLido?.let { kmTexto = it.toString() }
            }
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
                val lido = fotoPainel?.kmLido
                Campo(kmTexto, { kmTexto = it.filter(Char::isDigit) },
                    if (lido != null) "Km lido da foto: confira e corrija se precisar" else "Digite o km que aparece no painel", KeyboardType.Number)
                if (kmDigitado != null && lido != null && kmTexto.length >= 3 && abs(lido - kmDigitado) > 2) {
                    Aviso("Confira: na foto parece estar ${km(lido)}.", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
                }
                if (kmDigitado != null && prefs.ultimoKm > 0 && kmDigitado < prefs.ultimoKm && kmTexto.length >= 3) {
                    Aviso("Este km é menor que o último registrado (${km(prefs.ultimoKm)}). Confira antes de enviar.", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
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
                    var corpo: JSONObject? = null
                    try {
                        corpo = JSONObject()
                            .put("senha", prefs.senha)
                            .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                            .put("km", kmDigitado)
                            .put("quando", System.currentTimeMillis())
                            .put("id", "a" + System.currentTimeMillis() + "_" + (1000..9999).random())
                            .put("origem", when {
                                painel.kmLido == null -> "Digitado"
                                painel.kmLido == kmDigitado -> "Lido da foto (confirmado pelo técnico)"
                                else -> "Corrigido pelo técnico (foto: ${painel.kmLido})"
                            })
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
                        if (kmDigitado != null && kmDigitado > prefs.ultimoKm) prefs.ultimoKm = kmDigitado
                        aoConcluir(mensagemRetorno(r, etapa, prefs))
                    } catch (e: Api.ErroServidor) {
                        erro = "Não foi possível enviar: ${e.message}"
                    } catch (e: Exception) {
                        // Sem internet: guarda no celular e envia sozinho quando o sinal voltar.
                        val c = corpo
                        if (c != null && Fila.guardar(ctx, c)) {
                            if (kmDigitado != null && kmDigitado > prefs.ultimoKm) prefs.ultimoKm = kmDigitado
                            aoConcluir("Sem internet agora. O registro foi guardado no celular e será enviado automaticamente quando o sinal voltar.")
                        } else erro = "Não foi possível enviar. Verifique a internet e tente de novo."
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
    r.optString("avisoKm").takeIf { it.isNotBlank() }?.let { partes += it }
    val s = r.optJSONObject("situacao")
    if (s != null && s.optString("situacao") == "Abastecer") {
        partes += "Atenção: o veículo precisa ser abastecido (restam ${km(s.opt("kmRestantes"))}). O responsável foi avisado."
    }
    return partes.joinToString("\n")
}
