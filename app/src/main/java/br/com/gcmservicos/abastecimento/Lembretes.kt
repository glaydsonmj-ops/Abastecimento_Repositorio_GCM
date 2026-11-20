package br.com.gcmservicos.abastecimento

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Alertas no celular, de segunda a sexta:
 * - 8h: checagem do veículo e foto do km inicial (e aviso de troca de óleo, quando houver);
 * - 17h: foto do km final.
 * Usa alarme de despertador (setAlarmClock), que toca no horário exato mesmo com a tela
 * bloqueada e o aparelho em economia de energia.
 */
object Lembretes {
    private const val CANAL = "alertas_gcm"
    private const val CANAL_ANTIGO = "lembretes_km"
    const val INICIO = "Início do dia"
    const val FIM = "Fim do dia"
    const val EXTRA_PERIODO = "periodo"
    private const val ID_OLEO = 3

    fun criarCanal(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CANAL_ANTIGO)
        val canal = NotificationChannel(CANAL, "Alertas do dia", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Checagem das 8h, foto do km e troca de óleo"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(canal)
    }

    fun agendar(context: Context) {
        val prefs = Prefs(context)
        agendarUm(context, INICIO, prefs.lembreteInicio, 1)
        agendarUm(context, FIM, prefs.lembreteFim, 2)
    }

    /** Próximo dia útil (segunda a sexta) no horário informado. */
    internal fun proximoDiaUtil(hora: String, agora: Long = System.currentTimeMillis()): Long? {
        val partes = hora.split(":")
        val h = partes.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = partes.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            timeInMillis = agora
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= agora) add(Calendar.DAY_OF_YEAR, 1)
            while (get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun agendarUm(context: Context, periodo: String, hora: String, codigo: Int) {
        val quando = proximoDiaUtil(hora) ?: return
        val intent = Intent(context, LembreteReceiver::class.java).putExtra(EXTRA_PERIODO, periodo)
        val pi = PendingIntent.getBroadcast(
            context, codigo, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                val abrir = PendingIntent.getActivity(
                    context, 20 + codigo, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(quando, abrir), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
        }
    }

    private fun intentAbrir(context: Context, periodo: String?, codigo: Int): PendingIntent {
        val abrir = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (periodo != null) abrir.putExtra(EXTRA_PERIODO, periodo)
        return PendingIntent.getActivity(context, codigo, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun mostrar(context: Context, id: Int, titulo: String, texto: String, abrir: PendingIntent) {
        val n = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permissão de notificação negada pelo usuário.
        }
    }

    fun notificar(context: Context, periodo: String) {
        if (periodo == INICIO) {
            mostrar(
                context, 1, "Bom dia! Checagem do veículo",
                "O veículo está funcionando corretamente? Responda no app e tire a foto do km para começar o dia.",
                intentAbrir(context, periodo, 11)
            )
            avisoOleo(Prefs(context))?.let { (titulo, texto) -> mostrar(context, ID_OLEO, titulo, texto, intentAbrir(context, null, 13)) }
        } else {
            mostrar(context, 2, "Fim do expediente", "Tire a foto do km final do painel e encerre o turno.", intentAbrir(context, periodo, 12))
        }
    }

    /** Texto do alerta de troca de óleo para o técnico, ou null quando não há o que avisar. */
    fun avisoOleo(prefs: Prefs, agora: Long = System.currentTimeMillis()): Pair<String, String>? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hoje = fmt.format(agora)
        val amanha = fmt.format(agora + 86_400_000L)
        val agendada = prefs.oleoAgendada
        val situacao = prefs.oleoSituacao
        val precisa = situacao == "Trocar em breve" || situacao == "Atrasada"
        return when {
            agendada == hoje -> "Troca de óleo hoje" to "Hoje é o dia da troca de óleo agendada. Depois da troca, registre no app (aba Relatório)."
            agendada == amanha -> "Troca de óleo amanhã" to "A troca de óleo do veículo está agendada para amanhã (${prefs.oleoAgendadaBr})."
            agendada.isNotBlank() && agendada < hoje && precisa ->
                "Troca de óleo pendente" to "A troca agendada para ${prefs.oleoAgendadaBr} ainda não foi registrada. Registre a troca ou agende nova data no app."
            agendada.isBlank() && situacao == "Atrasada" ->
                "Troca de óleo atrasada" to "Passou ${-prefs.oleoKmFaltando} km do previsto. Agende a troca de óleo no app."
            agendada.isBlank() && situacao == "Trocar em breve" ->
                "Agende a troca de óleo" to "Faltam ${prefs.oleoKmFaltando} km para a troca de óleo. Toque aqui e agende a data no app."
            else -> null
        }
    }
}

class LembreteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val periodo = intent.getStringExtra(Lembretes.EXTRA_PERIODO) ?: Lembretes.INICIO
        val app = context.applicationContext
        val pendente = goAsync()
        Thread {
            try {
                Lembretes.criarCanal(app)
                val prefs = Prefs(app)
                if (prefs.configurado) {
                    // Atualiza a situação da troca de óleo antes do alerta das 8h (até 8 segundos).
                    if (periodo == Lembretes.INICIO) atualizarOleo(prefs)
                    Lembretes.notificar(app, periodo)
                }
            } catch (e: Exception) {
                // Mesmo sem internet o alerta principal já foi tentado; segue para reagendar.
            } finally {
                Lembretes.agendar(app)
                pendente.finish()
            }
        }.start()
    }

    private fun atualizarOleo(prefs: Prefs) {
        try {
            runBlocking {
                withTimeoutOrNull(8_000) {
                    val url = prefs.url + "?tecnico=" + Uri.encode(prefs.tecnico) + "&placa=" + Uri.encode(prefs.placa)
                    val r = Api.consultar(url, prefs.chave)
                    val vs = r.optJSONArray("veiculos") ?: return@withTimeoutOrNull
                    for (i in 0 until vs.length()) {
                        val v = vs.getJSONObject(i)
                        if (v.optString("placa") == prefs.placa) prefs.salvarOleo(v.optJSONObject("oleo"))
                    }
                }
            }
        } catch (e: Exception) {
            // Sem conexão: usa a última situação guardada.
        }
    }
}

/** Reagenda os alertas após reiniciar o celular, atualizar o app ou mudar a hora. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Lembretes.criarCanal(context)
        Lembretes.agendar(context)
    }
}
