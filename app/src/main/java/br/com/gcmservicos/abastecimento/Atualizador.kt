package br.com.gcmservicos.abastecimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização do aplicativo sem desinstalar: confere no GitHub se há versão nova,
 * baixa o APK e abre o instalador do Android (que pede um toque em "Atualizar").
 * Os dados do técnico (cadastro, senha, veículo) continuam no celular.
 */
object Atualizador {
    private const val REPO = "https://github.com/glaydsonmj-ops/Abastecimento_Repositorio_GCM"

    /** Número da versão mais nova publicada (ex.: 21 para v1.0.21), ou null sem internet. */
    suspend fun versaoNova(): Int? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val destino = conn.getHeaderField("Location") ?: ""
            conn.disconnect()
            val n = Regex("/tag/v\\d+\\.\\d+\\.(\\d+)").find(destino)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > BuildConfig.VERSION_CODE) n else null
        } catch (e: Exception) {
            null
        }
    }

    /** O Android exige, uma única vez, a permissão "Instalar apps desconhecidos" para este app. */
    fun podeInstalar(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun abrirPermissao(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Baixa o APK da versão [versao], informando o progresso de 0 a 100. */
    suspend fun baixar(ctx: Context, versao: Int, progresso: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val pasta = File(ctx.cacheDir, "atualizacao").apply { mkdirs() }
        pasta.listFiles()?.forEach { it.delete() }
        val arquivo = File(pasta, "abastecimento-gcm.apk")
        val conn = (URL("$REPO/releases/download/v1.0.$versao/abastecimento-gcm.apk").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode >= 400) throw IOException("Não foi possível baixar a atualização (erro ${conn.responseCode}).")
            val total = conn.contentLengthLong
            var lido = 0L
            conn.inputStream.use { ent ->
                arquivo.outputStream().use { sai ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ent.read(buf)
                        if (n < 0) break
                        sai.write(buf, 0, n)
                        lido += n
                        if (total > 0) withContext(Dispatchers.Main) { progresso((lido * 100 / total).toInt()) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        arquivo
    }

    fun instalar(ctx: Context, arquivo: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fotos", arquivo)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
