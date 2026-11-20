package br.com.gcmservicos.abastecimento

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta do aplicativo, tirada da logo da GCM Serviços. */
object Cores {
    val MARINHO = Color(0xFF052545)
    val AZUL = Color(0xFF0E3A66)
    val MAGENTA = Color(0xFFD1006C)
    val LARANJA = Color(0xFFFF7A12)
    val FUNDO = Color(0xFFF3F5F9)
    val TEXTO = Color(0xFF14213D)
    val SUAVE = Color(0xFF6B7588)
    val LINHA = Color(0xFFE3E7EE)
    val VERDE = Color(0xFF1B9E5A)
    val VERDE_CLARO = Color(0xFFE5F6EC)
    val VERMELHO = Color(0xFFD32F2F)
    val VERMELHO_CLARO = Color(0xFFFDECEC)
    val LARANJA_CLARO = Color(0xFFFFF1E6)

    val CABECALHO = Brush.linearGradient(listOf(MARINHO, AZUL))
    val MARCA = Brush.horizontalGradient(listOf(MAGENTA, LARANJA))
    val DESATIVADO = Brush.horizontalGradient(listOf(Color(0xFFC9CED8), Color(0xFFC9CED8)))
    val CONCLUIDO = Brush.horizontalGradient(listOf(VERDE, VERDE))
}

@Composable
fun TemaGcm(conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Cores.MAGENTA,
            onPrimary = Color.White,
            secondary = Cores.LARANJA,
            tertiary = Cores.AZUL,
            background = Cores.FUNDO,
            surface = Color.White,
            onSurface = Cores.TEXTO,
            onBackground = Cores.TEXTO,
            error = Cores.VERMELHO,
            outline = Cores.LINHA,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = conteudo,
    )
}

/** Símbolo "G" da GCM. */
@Composable
fun SimboloGcm(tamanho: Dp = 44.dp) {
    Image(painterResource(R.drawable.logo_g), contentDescription = "GCM", modifier = Modifier.size(tamanho))
}

/** Logo completa: GCM Serviços, App de Combustível. */
@Composable
fun LogoGcm(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo_gcm), contentDescription = "GCM Serviços - App de Combustível", modifier = modifier)
}

/** Botão principal com o degradê da marca (magenta para laranja). */
@Composable
fun BotaoMarca(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    concluido: Boolean = false,
    altura: Dp = 60.dp,
    icone: (@Composable () -> Unit)? = null,
) {
    val fundo = when {
        !habilitado -> Cores.DESATIVADO
        concluido -> Cores.CONCLUIDO
        else -> Cores.MARCA
    }
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            contentColor = Color.White, disabledContentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.fillMaxWidth().height(altura),
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(fundo),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icone?.invoke()
                Text(texto, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Faixa fina com o degradê da marca, usada abaixo do cabeçalho. */
@Composable
fun FaixaMarca(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Cores.MARCA))
}
