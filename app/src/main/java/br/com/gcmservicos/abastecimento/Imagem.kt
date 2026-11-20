package br.com.gcmservicos.abastecimento

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

object Imagem {

    /** Cria um arquivo temporário para a câmera gravar a foto. */
    fun novoArquivo(context: Context): Uri {
        val pasta = File(context.cacheDir, "fotos").apply { mkdirs() }
        pasta.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val arquivo = File.createTempFile("foto_", ".jpg", pasta)
        return FileProvider.getUriForFile(context, context.packageName + ".fotos", arquivo)
    }

    /** Carrega a foto já na orientação correta e reduzida. */
    fun carregar(context: Context, uri: Uri, ladoMaximo: Int = 1600): Bitmap {
        val fonte = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(fonte) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val escala = ladoMaximo.toFloat() / maxOf(w, h)
            if (escala < 1f) decoder.setTargetSize((w * escala).toInt(), (h * escala).toInt())
        }
    }

    fun paraBase64(bitmap: Bitmap, ladoMaximo: Int = 1280): String {
        val escala = ladoMaximo.toFloat() / maxOf(bitmap.width, bitmap.height)
        val img = if (escala < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * escala).toInt(), (bitmap.height * escala).toInt(), true)
        else bitmap
        val saida = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 80, saida)
        return Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP)
    }

    /** Variância do Laplaciano: valores baixos indicam foto desfocada. */
    fun nitidez(bitmap: Bitmap): Double {
        val w = 400
        val h = (bitmap.height * w / bitmap.width).coerceAtLeast(3)
        val p = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        p.getPixels(px, 0, w, 0, 0, w, h)
        val g = DoubleArray(w * h) {
            val c = px[it]
            0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
        }
        var soma = 0.0
        var somaQ = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
            soma += l; somaQ += l * l; n++
        }
        val media = soma / n
        return somaQ / n - media * media
    }

    const val NITIDEZ_MINIMA = 60.0
}
