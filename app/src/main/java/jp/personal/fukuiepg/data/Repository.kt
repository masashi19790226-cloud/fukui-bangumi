package jp.personal.fukuiepg.data

import android.content.Context
import androidx.room.withTransaction
import jp.personal.fukuiepg.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

val JST: ZoneId = ZoneId.of("Asia/Tokyo")

@Serializable
data class IndexDto(
    val generatedAt: String = "",
    val days: List<String> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
)

@Serializable
data class ChannelDto(val id: String, val band: String, val number: String, val name: String)

@Serializable
data class DayDto(val date: String = "", val generatedAt: String = "", val programs: List<ProgramDto> = emptyList())

@Serializable
data class ProgramDto(
    val id: String,
    val channelId: String,
    val start: String,
    val end: String,
    val title: String = "",
    val desc: String = "",
    val genre: String = "",
)

/** アプリ設定（SharedPreferences） */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.EPG_BASE_URL
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()

    var minutesBefore: Int
        get() = sp.getInt("minutesBefore", 5)
        set(v) = sp.edit().putInt("minutesBefore", v).apply()

    var lastUpdated: Long
        get() = sp.getLong("lastUpdated", 0L)
        set(v) = sp.edit().putLong("lastUpdated", v).apply()

    var generatedAt: String
        get() = sp.getString("generatedAt", "") ?: ""
        set(v) = sp.edit().putString("generatedAt", v).apply()
}

class EpgRepository(context: Context) {
    private val db = EpgDatabase.get(context)
    val dao = db.dao()
    val settings = Settings(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun url(path: String): String {
        val base = settings.baseUrl
        require(base.isNotBlank()) { "番組データのURLが未設定です（設定画面で入力してください）" }
        return if (base.endsWith("/")) base + path else "$base/$path"
    }

    private fun get(path: String): String? {
        val req = Request.Builder().url(url(path) + "?t=" + System.currentTimeMillis() / 60000).build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null
            if (!res.isSuccessful) error("HTTP ${res.code}: $path")
            return res.body?.string()
        }
    }

    /** 今日を含む7日分をダウンロードして端末DBに保存する。 */
    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val index = json.decodeFromString(IndexDto.serializer(), get("index.json") ?: error("index.json が見つかりません"))
            dao.upsertChannels(index.channels.mapIndexed { i, c ->
                ChannelEntity(c.id, c.band, c.number, c.name, bandOrder(c.band) * 10000 + i)
            })
            if (index.channels.isNotEmpty()) dao.deleteChannelsExcept(index.channels.map { it.id })

            var count = 0
            val today = LocalDate.now(JST)
            val days = index.days.ifEmpty { (0..6).map { today.plusDays(it.toLong()).toString() } }
            for (d in days) {
                val body = get("$d.json") ?: continue
                val day = json.decodeFromString(DayDto.serializer(), body)
                val list = day.programs.map { it.toEntity() }
                // その日のファイルに入っている範囲だけ入れ替える（番組の差し替え・時間変更に追従）
                if (list.isNotEmpty()) {
                    val from = list.minOf { it.startAt }
                    val to = list.maxOf { it.startAt } + 1
                    db.withTransaction {
                        dao.deleteProgramsStarting(from, to)
                        dao.insertPrograms(list)
                    }
                }
                count += list.size
            }
            dao.deleteOld(System.currentTimeMillis() - 24 * 3600_000L)
            dao.deleteExpiredFavorites(System.currentTimeMillis() - 24 * 3600_000L)
            settings.lastUpdated = System.currentTimeMillis()
            settings.generatedAt = index.generatedAt
            count
        }
    }

    suspend fun isEmpty(): Boolean = dao.programCount() == 0

    private fun bandOrder(band: String) = when (band) { "GR" -> 0; "BS" -> 1; "CS" -> 2; else -> 3 }
}

fun ProgramDto.toEntity() = ProgramEntity(
    id = id,
    channelId = channelId,
    startAt = OffsetDateTime.parse(start).toInstant().toEpochMilli(),
    endAt = OffsetDateTime.parse(end).toInstant().toEpochMilli(),
    title = title,
    description = desc,
    genre = genre,
)
