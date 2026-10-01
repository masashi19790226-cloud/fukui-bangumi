package jp.personal.fukuiepg.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.personal.fukuiepg.EpgApp
import jp.personal.fukuiepg.data.ChannelEntity
import jp.personal.fukuiepg.data.FavoriteEntity
import jp.personal.fukuiepg.data.JST
import jp.personal.fukuiepg.data.ProgramEntity
import jp.personal.fukuiepg.data.ProgramWithChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 番組表グリッドの1日は 5:00〜翌5:00 */
const val DAY_START_HOUR = 5L

fun dayStartMillis(date: LocalDate): Long =
    date.atStartOfDay(JST).plusHours(DAY_START_HOUR).toInstant().toEpochMilli()

/** 5時前は「前日の放送日」とみなす */
fun broadcastDate(ms: Long): LocalDate =
    java.time.Instant.ofEpochMilli(ms).atZone(JST).minusHours(DAY_START_HOUR).toLocalDate()

data class NowRow(val channel: ChannelEntity, val current: ProgramEntity?, val next: ProgramEntity?)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val epg = app as EpgApp
    private val repo = epg.repo
    private val dao = repo.dao

    /** 現在時刻（30秒ごとに更新） */
    val now = MutableStateFlow(System.currentTimeMillis())

    val refreshing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val lastUpdated = MutableStateFlow(repo.settings.lastUpdated)

    val channels: StateFlow<List<ChannelEntity>> =
        dao.channels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 今放送中 ----
    private val nowWindow = now.flatMapLatest { t -> dao.programsBetween(t - 60_000, t + 12 * 3600_000L) }
    val nowRows: StateFlow<List<NowRow>> = combine(channels, nowWindow, now) { chs, progs, t ->
        val byCh = progs.groupBy { it.channelId }
        chs.map { ch ->
            val list = byCh[ch.id].orEmpty().sortedBy { it.startAt }
            val cur = list.firstOrNull { it.startAt <= t && t < it.endAt }
            val next = list.firstOrNull { it.startAt >= (cur?.endAt ?: t) }
            NowRow(ch, cur, next)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 番組表グリッド ----
    val gridDate = MutableStateFlow(broadcastDate(System.currentTimeMillis()))
    val gridPrograms: StateFlow<List<ProgramEntity>> = gridDate.flatMapLatest { d ->
        val s = dayStartMillis(d)
        dao.programsBetween(s, s + 24 * 3600_000L)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- 検索 ----
    val query = MutableStateFlow("")
    val searchResults: StateFlow<List<ProgramWithChannel>> = query.debounce(250).flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList()) else dao.search(q.trim(), System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- お気に入り ----
    val favorites: StateFlow<List<FavoriteEntity>> =
        dao.favorites().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteIds: StateFlow<Set<String>> = combine(dao.favoriteProgramIds(), now) { ids, _ -> ids.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val minutesBefore = MutableStateFlow(repo.settings.minutesBefore)
    val baseUrl = MutableStateFlow(repo.settings.baseUrl)

    /** 詳細シートで表示中の番組 */
    val selected = MutableStateFlow<ProgramWithChannel?>(null)

    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                now.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            // 初回起動 or 最終更新から1時間以上たっていたら更新
            if (repo.isEmpty() || System.currentTimeMillis() - repo.settings.lastUpdated > 3600_000L) refresh()
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val r = repo.refresh()
            refreshing.value = false
            r.onSuccess {
                lastUpdated.value = repo.settings.lastUpdated
                epg.alarms.rescheduleAll()
                message.value = "番組表を更新しました（${it}件）"
            }.onFailure { message.value = "更新できませんでした: ${it.message}" }
            now.value = System.currentTimeMillis()
        }
    }

    fun open(programId: String) = viewModelScope.launch { selected.value = dao.programById(programId) }
    fun open(p: ProgramWithChannel) { selected.value = p }
    fun closeDetail() { selected.value = null }

    fun toggleFavorite(p: ProgramWithChannel) = viewModelScope.launch {
        val existing = dao.favoriteByProgram(p.id)
        if (existing != null) {
            dao.deleteFavorite(existing.id)
            epg.alarms.cancel(p.id)
            message.value = "通知を解除しました"
        } else {
            val min = repo.settings.minutesBefore
            dao.insertFavorite(
                FavoriteEntity(
                    type = FavoriteEntity.PROGRAM, programId = p.id, channelId = p.channelId,
                    title = p.title, startAt = p.startAt, minutesBefore = min,
                )
            )
            epg.alarms.schedule(p, min)
            message.value = "放送${min}分前に通知します"
        }
    }

    fun addKeyword(keyword: String) = viewModelScope.launch {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@launch
        if (favorites.value.any { it.type == FavoriteEntity.KEYWORD && it.keyword == kw }) {
            message.value = "「$kw」は登録済みです"; return@launch
        }
        dao.insertFavorite(FavoriteEntity(type = FavoriteEntity.KEYWORD, keyword = kw, minutesBefore = repo.settings.minutesBefore))
        epg.alarms.rescheduleAll()
        message.value = "「$kw」を含む番組を通知します"
    }

    fun removeFavorite(f: FavoriteEntity) = viewModelScope.launch {
        dao.deleteFavorite(f.id)
        f.programId?.let { epg.alarms.cancel(it) }
        if (f.type == FavoriteEntity.KEYWORD) {
            // キーワードで予約していた通知を取り消してから、残りを予約し直す
            f.keyword?.let { kw ->
                dao.searchTitleOnce(kw, System.currentTimeMillis()).forEach { epg.alarms.cancel(it.id) }
            }
            epg.alarms.rescheduleAll()
        }
    }

    fun setMinutesBefore(m: Int) {
        repo.settings.minutesBefore = m
        minutesBefore.value = m
    }

    fun setBaseUrl(u: String) {
        repo.settings.baseUrl = u
        baseUrl.value = repo.settings.baseUrl
    }

    suspend fun programTitle(id: String): String? = dao.programById(id)?.title
}
