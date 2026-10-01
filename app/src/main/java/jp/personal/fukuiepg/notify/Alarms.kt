package jp.personal.fukuiepg.notify

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.personal.fukuiepg.EpgApp
import jp.personal.fukuiepg.R
import jp.personal.fukuiepg.data.EpgRepository
import jp.personal.fukuiepg.data.FavoriteEntity
import jp.personal.fukuiepg.data.ProgramWithChannel
import jp.personal.fukuiepg.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/** お気に入り番組・キーワード一致番組の「放送前通知」を AlarmManager に予約する。 */
class AlarmScheduler(private val context: Context, private val repo: EpgRepository) {
    private val am = context.getSystemService(AlarmManager::class.java)

    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val dao = repo.dao
        val targets = linkedMapOf<String, Pair<ProgramWithChannel, Int>>()

        for (f in dao.favoritesOnce()) {
            when (f.type) {
                FavoriteEntity.PROGRAM -> {
                    val id = f.programId ?: continue
                    var p = dao.programById(id)
                    // 番組IDが変わった（放送時間の変更など）場合は、同じチャンネル・同じ題名の近い番組に付け替える
                    if (p == null && f.channelId != null && f.title != null && f.startAt != null) {
                        p = dao.findSameProgram(f.channelId, f.title, f.startAt)
                        if (p != null && kotlin.math.abs(p.startAt - f.startAt) < 12 * 3600_000L) {
                            dao.upsertFavorite(f.copy(programId = p.id, startAt = p.startAt))
                        } else p = null
                    }
                    if (p != null && p.startAt > now) targets[p.id] = p to f.minutesBefore
                }
                FavoriteEntity.KEYWORD -> {
                    val kw = f.keyword?.trim().orEmpty()
                    if (kw.isEmpty()) continue
                    dao.searchTitleOnce(kw, now).filter { it.startAt > now }.take(30).forEach {
                        targets.putIfAbsent(it.id, it to f.minutesBefore)
                    }
                }
            }
        }
        targets.values.forEach { (p, min) -> schedule(p, min) }
    }

    fun schedule(p: ProgramWithChannel, minutesBefore: Int) {
        val at = p.startAt - minutesBefore * 60_000L
        if (at <= System.currentTimeMillis()) return
        val pi = pendingIntent(p.id, p.title, "${p.channelName}  ${fmt(p.startAt)}〜（あと${minutesBefore}分）")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // 多少ずれる可能性あり
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(programId: String) {
        am.cancel(pendingIntent(programId, "", ""))
    }

    private fun pendingIntent(programId: String, title: String, text: String): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java)
            .setAction("jp.personal.fukuiepg.ALARM.$programId")
            .putExtra("title", title)
            .putExtra("text", text)
            .putExtra("id", programId.hashCode())
        return PendingIntent.getBroadcast(
            context, programId.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("M/d HH:mm")
        fun fmt(ms: Long): String = HM.format(Instant.ofEpochMilli(ms).atZone(jp.personal.fukuiepg.data.JST))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, EpgApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(intent.getStringExtra("title") ?: "まもなく放送")
            .setContentText(intent.getStringExtra("text") ?: "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(intent.getIntExtra("id", 0), n)
    }
}

/** 再起動・アプリ更新後に通知を予約し直す。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as EpgApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { app.alarms.rescheduleAll() } finally { pending.finish() }
        }
    }
}
