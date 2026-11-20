package br.com.gcmservicos.abastecimento

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/** Lembretes diários de foto do painel (início e fim do dia). */
object Lembretes {
    private const val CANAL = "lembretes_km"
    const val INICIO = "Início do dia"
    const val FIM = "Fim do dia"
    const val EXTRA_PERIODO = "periodo"

    fun criarCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Lembretes de km", NotificationManager.IMPORTANCE_HIGH)
        canal.description = "Pede a foto do painel no início e no fim do dia"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    fun agendar(context: Context) {
        val prefs = Prefs(context)
        agendarUm(context, INICIO, prefs.lembreteInicio, 1)
        agendarUm(context, FIM, prefs.lembreteFim, 2)
    }

    private fun agendarUm(context: Context, periodo: String, hora: String, codigo: Int) {
        val partes = hora.split(":")
        val h = partes.getOrNull(0)?.trim()?.toIntOrNull() ?: return
        val m = partes.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val intent = Intent(context, LembreteReceiver::class.java).putExtra(EXTRA_PERIODO, periodo)
        val pi = PendingIntent.getBroadcast(
            context, codigo, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun notificar(context: Context, periodo: String) {
        val abrir = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PERIODO, periodo)
        val pi = PendingIntent.getActivity(
            context, if (periodo == INICIO) 11 else 12, abrir,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val texto = if (periodo == INICIO) "Bom dia! O veículo está funcionando corretamente? Responda no app e tire a foto do km para começar o dia."
        else "Fim do expediente: tire a foto do km final do painel."
        val n = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(if (periodo == INICIO) "Bom dia! Checagem do veículo" else "Foto do km: fim do dia")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(if (periodo == INICIO) 1 else 2, n)
        } catch (e: SecurityException) {
            // Permissão de notificação negada pelo usuário.
        }
    }
}

class LembreteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val periodo = intent.getStringExtra(Lembretes.EXTRA_PERIODO) ?: Lembretes.INICIO
        Lembretes.criarCanal(context)
        if (Prefs(context).configurado) Lembretes.notificar(context, periodo)
        Lembretes.agendar(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Lembretes.agendar(context)
    }
}
