package br.com.gcmservicos.abastecimento

/**
 * Endereço do sistema (Google Apps Script) embutido no app.
 * A chave de acesso não fica no código: ela vem do segredo CHAVE_APP do
 * repositório na hora de gerar o APK. Sem ele, o app pede a chave uma vez.
 */
object Sistema {
    const val URL = "https://script.google.com/macros/s/AKfycbzDcW4KwVkj3ER20T-n2nmRFJiQZy92CuLpkyFzOz4HUtjrqCEgsV3pcaDBPGv6O9Ow/exec"
    val CHAVE: String = BuildConfig.CHAVE_APP
}
