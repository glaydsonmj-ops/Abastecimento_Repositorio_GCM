package br.com.gcmservicos.abastecimento

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/** Leitura de texto no próprio aparelho (Google ML Kit, sem internet). */
object Ocr {
    private val leitor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun ler(bitmap: Bitmap): Text = leitor.process(InputImage.fromBitmap(bitmap, 0)).await()

    /**
     * Procura o número do hodômetro. Dá preferência a números próximos do último km
     * conhecido e, depois, ao texto maior na foto (o hodômetro costuma ser o maior número).
     */
    fun extrairKm(texto: Text, kmReferencia: Long?): Long? {
        data class Candidato(val valor: Long, val digitos: Int, val altura: Int)
        val candidatos = mutableListOf<Candidato>()
        val padrao = Regex("\\d[\\d .]{1,10}\\d")
        for (bloco in texto.textBlocks) for (linha in bloco.lines) {
            val bruto = linha.text.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
            for (m in padrao.findAll(bruto)) {
                val d = m.value.filter { it.isDigit() }
                if (d.length in 3..7) {
                    candidatos += Candidato(d.toLong(), d.length, linha.boundingBox?.height() ?: 0)
                }
            }
        }
        if (candidatos.isEmpty()) return null
        if (kmReferencia != null && kmReferencia > 0) {
            val plausivel = candidatos
                .filter { it.valor >= kmReferencia && it.valor - kmReferencia <= 3000 }
                .minByOrNull { abs(it.valor - kmReferencia) }
            if (plausivel != null) return plausivel.valor
        }
        return candidatos.sortedWith(
            compareByDescending<Candidato> { if (it.digitos >= 4) 1 else 0 }
                .thenByDescending { it.altura }
                .thenByDescending { it.digitos }
        ).first().valor
    }

    data class DadosNota(val litros: Double?, val valor: Double?)

    /** Tenta achar litros e valor total na nota do posto. */
    fun extrairNota(texto: Text): DadosNota {
        val t = texto.text
        val litros = Regex("(\\d{1,3}[.,]\\d{2,3})\\s*(?:L|LT|LTS|LITROS?)\\b", RegexOption.IGNORE_CASE)
            .findAll(t).mapNotNull { numero(it.groupValues[1]) }.filter { it in 1.0..200.0 }.firstOrNull()
            ?: Regex("(?:QTDE?|QUANT(?:IDADE)?|LITROS?)\\D{0,12}(\\d{1,3}[.,]\\d{2,3})", RegexOption.IGNORE_CASE)
                .findAll(t).mapNotNull { numero(it.groupValues[1]) }.filter { it in 1.0..200.0 }.firstOrNull()
        val valores = Regex("(?:TOTAL|PAGAR|R\\$)\\D{0,15}(\\d{1,4}[.,]\\d{2})(?!\\d)", RegexOption.IGNORE_CASE)
            .findAll(t).mapNotNull { numero(it.groupValues[1]) }.filter { it in 5.0..5000.0 }.toList()
        return DadosNota(litros, valores.maxOrNull())
    }

    fun numero(s: String): Double? {
        val limpo = if (s.contains(',')) s.replace(".", "").replace(',', '.') else s
        return limpo.toDoubleOrNull()
    }
}
