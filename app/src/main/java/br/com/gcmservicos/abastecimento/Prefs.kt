package br.com.gcmservicos.abastecimento

import android.content.Context

/** Configurações guardadas no aparelho. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", null)?.takeIf { it.isNotBlank() } ?: Sistema.URL
        set(v) = sp.edit().putString("url", v.trim()).apply()
    /** Senha de 4 números do técnico (criada no primeiro acesso). */
    var senha: String
        get() = sp.getString("senha", "") ?: ""
        set(v) = sp.edit().putString("senha", v.trim()).apply()
    var tecnico: String
        get() = sp.getString("tecnico", "") ?: ""
        set(v) = sp.edit().putString("tecnico", v).apply()
    var placa: String
        get() = sp.getString("placa", "") ?: ""
        set(v) = sp.edit().putString("placa", v).apply()
    var consumoKmL: Double
        get() = sp.getFloat("consumo", 10f).toDouble()
        set(v) = sp.edit().putFloat("consumo", v.toFloat()).apply()
    var valorPadrao: Double
        get() = sp.getFloat("valorPadrao", 180f).toDouble()
        set(v) = sp.edit().putFloat("valorPadrao", v.toFloat()).apply()
    var lembreteInicio: String
        get() = sp.getString("lembreteInicio", "08:00") ?: "08:00"
        set(v) = sp.edit().putString("lembreteInicio", v).apply()
    var lembreteFim: String
        get() = sp.getString("lembreteFim", "17:00") ?: "17:00"
        set(v) = sp.edit().putString("lembreteFim", v).apply()

    /** Última resposta do sistema para a tela inicial (mostrada enquanto atualiza). */
    var ultimaConsulta: String
        get() = sp.getString("ultimaConsulta", "") ?: ""
        set(v) = sp.edit().putString("ultimaConsulta", v).apply()

    /** Último km conhecido do veículo (ajuda a leitura da foto e o aviso de km menor). */
    var ultimoKm: Long
        get() = sp.getLong("ultimoKm", 0)
        set(v) = sp.edit().putLong("ultimoKm", v).apply()

    val configurado: Boolean
        get() = url.isNotBlank() && senha.isNotBlank() && tecnico.isNotBlank() && placa.isNotBlank()

    // Situação da troca de óleo guardada no aparelho, usada no alerta das 8h.
    var oleoSituacao: String
        get() = sp.getString("oleoSituacao", "") ?: ""
        set(v) = sp.edit().putString("oleoSituacao", v).apply()
    var oleoKmFaltando: Long
        get() = sp.getLong("oleoKmFaltando", 0)
        set(v) = sp.edit().putLong("oleoKmFaltando", v).apply()
    var oleoAgendada: String
        get() = sp.getString("oleoAgendada", "") ?: ""
        set(v) = sp.edit().putString("oleoAgendada", v).apply()
    var oleoAgendadaBr: String
        get() = sp.getString("oleoAgendadaBr", "") ?: ""
        set(v) = sp.edit().putString("oleoAgendadaBr", v).apply()

    // Seguro obrigatório mensal: boleto e comprovante a partir do dia configurado (padrão 20).
    var seguroDia: Int
        get() = sp.getInt("seguroDia", 20)
        set(v) = sp.edit().putInt("seguroDia", v).apply()
    /** Mês (aaaa-mm) em que o boleto e o comprovante já foram enviados. */
    var seguroMesEnviado: String
        get() = sp.getString("seguroMesEnviado", "") ?: ""
        set(v) = sp.edit().putString("seguroMesEnviado", v).apply()

    /** Guarda a situação mensal do seguro (objeto "seguro.mensal" devolvido pelo sistema). */
    fun salvarSeguro(seguro: org.json.JSONObject?) {
        val m = seguro?.optJSONObject("mensal") ?: return
        seguroDia = m.optInt("dia", 20)
        if (m.optBoolean("enviado")) seguroMesEnviado = m.optString("mesIso")
    }

    /** Guarda a situação da troca de óleo do veículo (objeto "oleo" devolvido pelo sistema). */
    fun salvarOleo(oleo: org.json.JSONObject?) {
        if (oleo == null) return
        oleoSituacao = oleo.optString("situacao")
        oleoKmFaltando = oleo.optLong("kmFaltando")
        oleoAgendada = oleo.optString("agendadaIso")
        oleoAgendadaBr = oleo.optString("agendadaPara")
    }
}
