package br.com.gcmservicos.abastecimento

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Comunicação com o Google Apps Script publicado como app da Web. */
object Api {

    /** Consulta com a senha do técnico (o nome do técnico já vai no endereço). */
    suspend fun consultar(url: String, senha: String): JSONObject = withContext(Dispatchers.IO) {
        val sep = if (url.contains("?")) "&" else "?"
        verificar(requisicao(url + sep + "senha=" + URLEncoder.encode(senha, "UTF-8"), null))
    }

    suspend fun enviar(url: String, corpo: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        verificar(requisicao(url, corpo.toString()))
    }

    private fun verificar(resp: JSONObject): JSONObject {
        if (!resp.optBoolean("ok")) throw IOException(resp.optString("erro", "Erro desconhecido no servidor"))
        return resp
    }

    /** O Apps Script responde com redirecionamento (302); seguimos manualmente com GET. */
    private fun requisicao(url: String, corpo: String?): JSONObject {
        var conn = abrir(url)
        if (corpo != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
        }
        var codigo = conn.responseCode
        var saltos = 0
        while (codigo in 301..308 && saltos < 5) {
            val destino = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = abrir(destino)
            codigo = conn.responseCode
            saltos++
        }
        val texto = try {
            (if (codigo < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        if (codigo >= 400) throw IOException("O servidor respondeu com erro $codigo. Verifique o endereço do script.")
        return try {
            JSONObject(texto)
        } catch (e: JSONException) {
            throw IOException("Resposta inválida do servidor. Verifique o endereço do script e se ele foi implantado para \"Qualquer pessoa\".")
        }
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 90_000
        }
}
