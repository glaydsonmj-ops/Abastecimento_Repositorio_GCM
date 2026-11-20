package br.com.gcmservicos.abastecimento

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File

/**
 * Registros feitos sem internet: ficam guardados no celular e são enviados sozinhos
 * quando a conexão voltar (mesmo com o aplicativo fechado). Cada registro leva "id" e
 * "quando" (hora do celular), então o sistema grava a hora certa e não duplica.
 */
object Fila {
    private fun pasta(ctx: Context) = File(ctx.filesDir, "pendentes").apply { mkdirs() }

    fun quantidade(ctx: Context): Int = pasta(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    fun guardar(ctx: Context, corpo: JSONObject): Boolean = try {
        val id = corpo.optString("id").ifBlank { System.currentTimeMillis().toString() }
        File(pasta(ctx), "${System.currentTimeMillis()}_$id.json").writeText(corpo.toString())
        agendar(ctx)
        true
    } catch (e: Exception) {
        false
    }

    /** Pede ao Android para enviar assim que houver internet. */
    fun agendar(ctx: Context) {
        if (quantidade(ctx) == 0) return
        val pedido = OneTimeWorkRequestBuilder<EnvioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("enviar_pendentes", ExistingWorkPolicy.APPEND_OR_REPLACE, pedido)
    }

    /** Envia os guardados, do mais antigo ao mais novo. Devolve false se faltou internet. */
    suspend fun enviar(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        while (true) {
            val f = pasta(ctx).listFiles { x -> x.name.endsWith(".json") }?.minByOrNull { it.name } ?: return true
            val corpo = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); continue }
            try {
                Api.enviar(prefs.url, corpo)
                f.delete()
            } catch (e: Api.ErroServidor) {
                // Recusado pelo sistema (ex.: login excluído): descarta para não travar os outros.
                f.delete()
            } catch (e: Exception) {
                return false
            }
        }
    }
}

class EnvioWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (Fila.enviar(applicationContext)) Result.success() else Result.retry()
}
