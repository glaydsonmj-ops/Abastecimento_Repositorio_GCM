package br.com.gcmservicos.abastecimento

import android.content.Context

/** Configurações guardadas no aparelho. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()
    var chave: String
        get() = sp.getString("chave", "") ?: ""
        set(v) = sp.edit().putString("chave", v.trim()).apply()
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

    val configurado: Boolean
        get() = url.isNotBlank() && chave.isNotBlank() && tecnico.isNotBlank() && placa.isNotBlank()
}
