package br.com.gcmservicos.abastecimento

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray?.objetos(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun urlTecnico(prefs: Prefs, extra: String = ""): String =
    prefs.url + "?tecnico=" + Uri.encode(prefs.tecnico) + "&placa=" + Uri.encode(prefs.placa) + extra

// ---------------------------------------------------------------- Navegação

/** Barra inferior com as abas Hoje, Check-lists e Relatório. */
@Composable
internal fun BarraNavegacao(atual: Int, aoEscolher: (Int) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        val itens = listOf(
            Triple("Hoje", Icons.Filled.Home, 0),
            Triple("Check-lists", Icons.AutoMirrored.Filled.List, 1),
            Triple("Relatório", Icons.Filled.DateRange, 2),
        )
        itens.forEach { (rotulo, icone, i) ->
            NavigationBarItem(
                selected = atual == i,
                onClick = { aoEscolher(i) },
                icon = { Icon(icone, contentDescription = null) },
                label = { Text(rotulo, fontWeight = if (atual == i) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Cores.MAGENTA, selectedTextColor = Cores.MAGENTA,
                    indicatorColor = Cores.LARANJA_CLARO, unselectedIconColor = Cores.SUAVE, unselectedTextColor = Cores.SUAVE
                )
            )
        }
    }
}

// ---------------------------------------------------------------- Checagem diária

/**
 * Pergunta diária: "O veículo está funcionando corretamente?".
 * Se não estiver, o técnico descreve o problema e toca em "Veículo em manutenção".
 */
@Composable
internal fun CartaoChecagem(prefs: Prefs, hoje: JSONObject, aoEnviar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    var problema by remember { mutableStateOf(false) }
    var descricao by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val emManutencao = hoje.optBoolean("emManutencao")
    val feita = hoje.optBoolean("checagemFeita")

    fun enviar(funcionando: Boolean) {
        ocupado = true; erro = null
        escopo.launch {
            try {
                Api.enviar(
                    prefs.url, JSONObject().put("chave", prefs.chave).put("tipo", "checagem")
                        .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                        .put("funcionando", funcionando).put("descricao", descricao.trim())
                )
                problema = false
                aoEnviar()
            } catch (e: Exception) {
                erro = "Não foi possível enviar. Verifique a internet e tente de novo."
            } finally { ocupado = false }
        }
    }

    if (emManutencao) {
        Card(shape = CANTO, colors = CardDefaults.cardColors(containerColor = Cores.VERMELHO_CLARO), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Build, contentDescription = null, tint = Cores.VERMELHO)
                    Text("Veículo em manutenção", color = Cores.VERMELHO, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("O responsável já foi avisado. Quando o veículo voltar a funcionar, toque no botão abaixo.", color = Cores.TEXTO)
                erro?.let { Erro(it) }
                OutlinedButton(onClick = { enviar(true) }, enabled = !ocupado, shape = CANTO_CAMPO, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (ocupado) "Enviando..." else "O veículo voltou a funcionar", color = Cores.VERDE, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    if (feita) return

    Card(shape = CANTO, colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()
            .border(2.dp, Cores.LARANJA, CANTO)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bom dia! O veículo está funcionando corretamente?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (!problema) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { enviar(true) }, enabled = !ocupado, shape = CANTO_CAMPO,
                        colors = ButtonDefaults.buttonColors(containerColor = Cores.VERDE),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text("Sim", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { problema = true }, enabled = !ocupado, shape = CANTO_CAMPO,
                        colors = ButtonDefaults.buttonColors(containerColor = Cores.VERMELHO),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text("Não", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            } else {
                Campo(descricao, { descricao = it }, "O que está acontecendo? (opcional)", KeyboardType.Text)
                Button(
                    onClick = { enviar(false) }, enabled = !ocupado, shape = CANTO_CAMPO,
                    colors = ButtonDefaults.buttonColors(containerColor = Cores.VERMELHO),
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ocupado) "Enviando..." else "Veículo em manutenção", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { problema = false }, enabled = !ocupado) { Text("Voltar", color = Cores.SUAVE) }
            }
            if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
            erro?.let { Erro(it) }
        }
    }
}

/** Avisos de troca de óleo e seguro obrigatório na tela inicial. */
@Composable
internal fun AvisosPrevistos(veiculo: JSONObject) {
    veiculo.optJSONObject("oleo")?.let { o ->
        when (o.optString("situacao")) {
            "Atrasada" -> Aviso("Troca de óleo atrasada: passou ${km(-o.optDouble("kmFaltando"))} do previsto.", Cores.VERMELHO_CLARO, Cores.VERMELHO, Icons.Filled.Warning)
            "Trocar em breve" -> Aviso("Troca de óleo em breve: faltam ${km(o.optDouble("kmFaltando"))}.", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
        }
    }
    veiculo.optJSONObject("seguro")?.let { s ->
        when (s.optString("situacao")) {
            "Vencido" -> Aviso("Seguro obrigatório vencido em ${s.optString("vencimento")}.", Cores.VERMELHO_CLARO, Cores.VERMELHO, Icons.Filled.Warning)
            "Vence em breve" -> Aviso("Seguro obrigatório vence em ${s.optString("vencimento")} (${s.optInt("diasRestantes")} dias).", Cores.LARANJA_CLARO, Cores.LARANJA, Icons.Filled.Warning)
        }
    }
}

// ---------------------------------------------------------------- Check-lists

@Composable
internal fun TelaChecklists(prefs: Prefs, aviso: String?, aoAbrir: (String, List<String>) -> Unit) {
    var lista by remember { mutableStateOf<List<JSONObject>?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var atualizar by remember { mutableIntStateOf(0) }
    LaunchedEffect(atualizar) {
        erro = null
        try {
            lista = Api.consultar(urlTecnico(prefs), prefs.chave).optJSONArray("checklists").objetos()
        } catch (e: Exception) {
            erro = "Sem conexão com o sistema. Toque em Atualizar."
        }
    }
    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        aviso?.let { Aviso(it, Cores.VERDE_CLARO, Cores.VERDE, Icons.Filled.CheckCircle) }
        Text("Preencha cada check-list uma vez por semana. Itens com problema são avisados ao responsável.", color = Cores.SUAVE)
        erro?.let {
            Erro(it)
            OutlinedButton(onClick = { atualizar++ }, shape = CANTO_CAMPO) { Text("Atualizar") }
        }
        if (lista == null && erro == null) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
        lista?.forEach { c ->
            val nome = c.optString("nome")
            val feito = c.optBoolean("feitoNaSemana")
            val itens = c.optJSONArray("itens")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val problemas = c.optJSONArray("problemas").objetos()
            Card(
                onClick = { aoAbrir(nome, itens) }, shape = CANTO, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(if (feito) Cores.CONCLUIDO else Cores.MARCA),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (feito) Icons.Filled.Check else if (nome.startsWith("Segurança")) Icons.Filled.Warning else Icons.Filled.Build,
                            contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(nome, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (feito) "Feito nesta semana" else "Pendente nesta semana",
                            color = if (feito) Cores.VERDE else Cores.LARANJA, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                        )
                        val ultimo = c.optString("ultimoEm")
                        Text(
                            "${itens.size} itens" + if (ultimo.isNotBlank()) " · último em $ultimo" else "",
                            color = Cores.SUAVE, fontSize = 13.sp
                        )
                        if (problemas.isNotEmpty()) {
                            Text(
                                "Com problema: " + problemas.joinToString(", ") { it.optString("item") },
                                color = Cores.VERMELHO, fontSize = 13.sp
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Cores.SUAVE)
                }
            }
        }
    }
}

private val CONDICOES = listOf("OK", "Com problema", "Não tem")

@Composable
internal fun TelaChecklist(prefs: Prefs, nome: String, itens: List<String>, aoConcluir: (String) -> Unit) {
    val escopo = rememberCoroutineScope()
    val condicao = remember { mutableStateMapOf<String, String>() }
    val observacao = remember { mutableStateMapOf<String, String>() }
    var ocupado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val faltam = itens.count { condicao[it] == null }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (itens.isEmpty()) {
            Erro("Nenhum item cadastrado. Peça ao responsável para cadastrar os itens na planilha.")
            return@Column
        }
        OutlinedButton(
            onClick = { itens.forEach { if (condicao[it] == null) condicao[it] = "OK" } },
            shape = CANTO_CAMPO, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Cores.VERDE, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Marcar os restantes como OK", color = Cores.VERDE)
        }
        itens.forEach { item ->
            val atual = condicao[item]
            Cartao {
                Text(item, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CONDICOES.forEach { c ->
                        val cor = when (c) { "OK" -> Cores.VERDE; "Com problema" -> Cores.LARANJA; else -> Cores.VERMELHO }
                        val sel = atual == c
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(CANTO_CAMPO)
                                .background(if (sel) cor else Color.White)
                                .border(2.dp, if (sel) cor else Cores.LINHA, CANTO_CAMPO)
                                .clickable { condicao[item] = c },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(c, color = if (sel) Color.White else cor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (atual != null && atual != "OK") {
                    Campo(observacao[item] ?: "", { observacao[item] = it }, "Descreva o problema (opcional)", KeyboardType.Text)
                }
            }
        }
        erro?.let { Erro(it) }
        if (ocupado) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
        BotaoMarca(
            when {
                ocupado -> "Enviando..."
                faltam > 0 -> "Faltam $faltam itens"
                else -> "Enviar check-list"
            },
            onClick = {
                ocupado = true; erro = null
                escopo.launch {
                    try {
                        val lista = JSONArray()
                        itens.forEach { i ->
                            lista.put(JSONObject().put("item", i).put("condicao", condicao[i]).put("observacao", observacao[i]?.trim() ?: ""))
                        }
                        val r = Api.enviar(
                            prefs.url, JSONObject().put("chave", prefs.chave).put("tipo", "checklist")
                                .put("tecnico", prefs.tecnico).put("placa", prefs.placa)
                                .put("checklist", nome).put("itens", lista)
                        )
                        val n = r.optInt("problemas")
                        aoConcluir(
                            if (n > 0) "Check-list enviado. $n item(ns) com problema; o responsável foi avisado."
                            else "Check-list enviado. Tudo OK!"
                        )
                    } catch (e: Exception) {
                        erro = "Não foi possível enviar: ${e.message}. Verifique a internet e tente de novo."
                    } finally { ocupado = false }
                }
            },
            habilitado = !ocupado && faltam == 0,
            icone = { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- Relatório

@Composable
internal fun TelaRelatorio(prefs: Prefs) {
    val escopo = rememberCoroutineScope()
    var rel by remember { mutableStateOf<JSONObject?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var atualizar by remember { mutableIntStateOf(0) }
    var registrandoOleo by remember { mutableStateOf(false) }
    var kmOleo by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(atualizar) {
        erro = null
        try {
            rel = Api.consultar(urlTecnico(prefs, "&relatorio=1"), prefs.chave).optJSONObject("relatorio")
        } catch (e: Exception) {
            erro = "Sem conexão com o sistema. Toque em Atualizar."
        }
    }

    Column(Modifier.tela().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        aviso?.let { Aviso(it, Cores.VERDE_CLARO, Cores.VERDE, Icons.Filled.CheckCircle) }
        erro?.let { Erro(it) }
        val r = rel
        if (r == null) {
            if (erro == null) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Cores.MAGENTA)
            else OutlinedButton(onClick = { atualizar++ }, shape = CANTO_CAMPO) { Text("Atualizar") }
            return@Column
        }

        // Combustível do mês
        val mes = r.optJSONObject("mes") ?: JSONObject()
        Cartao {
            Text("Combustível no mês", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Indicador("Gasto", reais(mes.optDouble("gasto", 0.0)), Cores.MAGENTA, Modifier.weight(1f))
                Indicador("Km rodados", km(mes.optDouble("km", 0.0)), Cores.AZUL, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Indicador("Litros", dec(mes.optDouble("litros", 0.0), 1) + " L", Cores.AZUL, Modifier.weight(1f))
                Indicador("Abastecimentos", mes.optInt("abastecimentos").toString(), Cores.AZUL, Modifier.weight(1f))
            }
        }

        // Últimos 7 dias
        val dias = r.optJSONArray("dias").objetos()
        if (dias.isNotEmpty()) {
            Cartao {
                Text("Seus últimos 7 dias", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val max = (dias.maxOfOrNull { it.optDouble("km", 0.0) } ?: 0.0).coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    dias.forEach { d ->
                        val k = d.optDouble("km", 0.0)
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Text(if (k > 0) k.toInt().toString() else "", fontSize = 11.sp, color = Cores.SUAVE)
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight((k / max * 0.75).toFloat().coerceAtLeast(0.02f))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(Cores.MARCA)
                            )
                            Text(d.optString("dia"), fontSize = 11.sp, color = Cores.SUAVE)
                        }
                    }
                }
                Text("Km rodados por dia. Gasto estimado na semana: " +
                    reais(dias.sumOf { it.optDouble("gasto", 0.0) }), color = Cores.SUAVE, fontSize = 13.sp)
            }
        }

        // Troca de óleo
        val oleo = r.optJSONObject("oleo") ?: JSONObject()
        Cartao {
            Text("Troca de óleo", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val situacao = oleo.optString("situacao")
            if (situacao == "Sem registro") {
                Text("Ainda não há troca de óleo registrada para este veículo.", color = Cores.SUAVE)
            } else {
                val faltam = oleo.optDouble("kmFaltando", 0.0)
                val intervalo = oleo.optDouble("intervalo", 5000.0).coerceAtLeast(1.0)
                val cor = when (situacao) { "Atrasada" -> Cores.VERMELHO; "Trocar em breve" -> Cores.LARANJA; else -> Cores.VERDE }
                Text(
                    if (faltam < 0) "Atrasada: passou ${km(-faltam)} do previsto" else "Próxima troca em ${km(faltam)}",
                    color = cor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold
                )
                LinearProgressIndicator(
                    progress = { (1 - faltam / intervalo).toFloat().coerceIn(0f, 1f) },
                    color = cor, trackColor = Cores.FUNDO,
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
                )
                Text(
                    "Última troca: ${km(oleo.opt("kmUltimaTroca"))}" +
                        (oleo.optString("dataUltimaTroca").takeIf { it.isNotBlank() }?.let { " em $it" } ?: "") +
                        " · próxima: ${km(oleo.opt("proximaTrocaKm"))}",
                    color = Cores.SUAVE, fontSize = 13.sp
                )
            }
            if (!registrandoOleo) {
                OutlinedButton(onClick = { registrandoOleo = true; kmOleo = r.optLong("kmAtual").takeIf { it > 0 }?.toString() ?: "" },
                    shape = CANTO_CAMPO, modifier = Modifier.fillMaxWidth()) { Text("Registrar troca de óleo feita") }
            } else {
                Campo(kmOleo, { kmOleo = it.filter(Char::isDigit) }, "Km do painel na troca", KeyboardType.Number)
                BotaoMarca(
                    if (ocupado) "Enviando..." else "Confirmar troca de óleo",
                    onClick = {
                        ocupado = true
                        escopo.launch {
                            try {
                                val resp = Api.enviar(
                                    prefs.url, JSONObject().put("chave", prefs.chave).put("tipo", "troca_oleo")
                                        .put("tecnico", prefs.tecnico).put("placa", prefs.placa).put("km", kmOleo.toLong())
                                )
                                resp.optJSONObject("relatorio")?.let { rel = it }
                                registrandoOleo = false
                                aviso = "Troca de óleo registrada."
                            } catch (e: Exception) {
                                erro = "Não foi possível registrar a troca de óleo. Tente de novo."
                            } finally { ocupado = false }
                        }
                    },
                    habilitado = !ocupado && (kmOleo.toLongOrNull() ?: 0) > 0, altura = 52.dp
                )
                TextButton(onClick = { registrandoOleo = false }) { Text("Cancelar", color = Cores.SUAVE) }
            }
        }

        // Seguro obrigatório
        val seguro = r.optJSONObject("seguro") ?: JSONObject()
        Cartao {
            Text("Seguro obrigatório", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            when (val s = seguro.optString("situacao")) {
                "Sem registro" -> Text("O vencimento ainda não foi cadastrado pelo responsável.", color = Cores.SUAVE)
                else -> {
                    val dias = seguro.optInt("diasRestantes")
                    val cor = when (s) { "Vencido" -> Cores.VERMELHO; "Vence em breve" -> Cores.LARANJA; else -> Cores.VERDE }
                    Text(
                        if (dias < 0) "Vencido há ${-dias} dias" else if (dias == 0) "Vence hoje" else "Vence em $dias dias",
                        color = cor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold
                    )
                    Text("Vencimento: ${seguro.optString("vencimento")}", color = Cores.SUAVE)
                }
            }
        }

        // Histórico de abastecimentos
        val abast = r.optJSONArray("abastecimentos").objetos()
        Cartao {
            Text("Histórico de abastecimentos", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (abast.isEmpty()) Text("Nenhum abastecimento registrado.", color = Cores.SUAVE)
            abast.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = Cores.LINHA)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.optString("data"), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${dec(a.optDouble("litros", 0.0), 1)} L · ${km(a.opt("km"))}" +
                                (if (a.optDouble("precoLitro", 0.0) > 0) " · ${reais(a.optDouble("precoLitro"))}/L" else "") +
                                " · ${a.optString("tecnico")}",
                            color = Cores.SUAVE, fontSize = 13.sp
                        )
                    }
                    Text(reais(a.optDouble("valor", 0.0)), color = Cores.MAGENTA, fontWeight = FontWeight.Bold)
                }
            }
        }

        OutlinedButton(onClick = { aviso = null; atualizar++ }, shape = CANTO_CAMPO) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Atualizar")
        }
    }
}
