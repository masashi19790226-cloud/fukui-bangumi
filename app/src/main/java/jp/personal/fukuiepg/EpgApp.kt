package jp.personal.fukuiepg

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import jp.personal.fukuiepg.data.EpgRepository
import jp.personal.fukuiepg.notify.AlarmScheduler
import jp.personal.fukuiepg.work.RefreshWorker

class EpgApp : Application() {
    lateinit var repo: EpgRepository
        private set
    lateinit var alarms: AlarmScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        repo = EpgRepository(this)
        alarms = AlarmScheduler(this, repo)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "放送前のお知らせ", NotificationManager.IMPORTANCE_HIGH)
        )
        RefreshWorker.schedulePeriodic(this)
    }

    companion object {
        const val CHANNEL_ID = "program_alarm"
    }
}
