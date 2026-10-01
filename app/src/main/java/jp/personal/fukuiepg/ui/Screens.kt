@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.personal.fukuiepg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.personal.fukuiepg.data.FavoriteEntity
import jp.personal.fukuiepg.data.JST
import jp.personal.fukuiepg.data.ProgramEntity
import jp.personal.fukuiepg.data.ProgramWithChannel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MDHM = DateTimeFormatter.ofPattern("M/d(E) HH:mm", java.util.Locale.JAPAN)
private val MDE = DateTimeFormatter.ofPattern("M/d(E)", java.util.Locale.JAPAN)
fun hm(ms: Long): String = HM.format(Instant.ofEpochMilli(ms).atZone(JST))
fun mdhm(ms: Long): String = MDHM.format(Instant.ofEpochMilli(ms).atZone(JST))

private fun bandLabel(b: String) = when (b) { "GR" -> "地デジ"; "BS" -> "BS"; "CS" -> "CS"; else -> b }

/** ジャンル別の背景色（番組表セル） */
@Composable
private fun genreColor(genre: String): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val base = when {
        genre.startsWith("ニュース") -> Color(0xFFE3F2FD)
        genre.startsWith("スポーツ") -> Color(0xFFE8F5E9)
        genre.startsWith("ドラマ") -> Color(0xFFFCE4EC)
        genre.startsWith("アニメ") -> Color(0xFFFFF8E1)
        genre.startsWith("音楽") -> Color(0xFFF3E5F5)
        genre.startsWith("バラエティ") -> Color(0xFFFFF3E0)
        genre.startsWith("映画") -> Color(0xFFEFEBE9)
        genre.startsWith("ドキュメンタリー") -> Color(0xFFE0F2F1)
        else -> Color(0xFFF5F5F5)
    }
    return if (dark) base.copy(red = base.red * 0.25f, green = base.green * 0.25f, blue = base.blue * 0.28f) else base
}

@Composable
private fun EmptyHint(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("番組データがまだありません", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("右上の更新ボタンを押すか、「通知」タブの設定で番組データのURLを確認してください。", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.refresh() }) { Text("今すぐ更新") }
    }
}

// ============ 今放送中 ============
@Composable
fun NowScreen(vm: MainViewModel) {
    val rows by vm.nowRows.collectAsState()
    val now by vm.now.collectAsState()
    if (rows.isEmpty()) { EmptyHint(vm); return }
    val bands = rows.map { it.channel.band }.distinct()
    var band by rememberSaveable { mutableStateOf(bands.first()) }
    if (band !in bands) band = bands.first()

    Column {
        if (bands.size > 1) {
            PrimaryTabRow(selectedTabIndex = bands.indexOf(band)) {
                bands.forEach { b -> Tab(selected = b == band, onClick = { band = b }, text = { Text(bandLabel(b)) }) }
            }
        }
        LazyColumn {
            items(rows.filter { it.channel.band == band }, key = { it.channel.id }) { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = r.current != null) { r.current?.let { vm.open(it.id) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.channel.number, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp))
                        Text(r.channel.name, style = MaterialTheme.typography.labelLarge)
                    }
                    val cur = r.current
                    if (cur != null) {
                        Text(cur.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${hm(cur.startAt)}〜${hm(cur.endAt)}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            val frac = ((now - cur.startAt).toFloat() / (cur.endAt - cur.startAt).coerceAtLeast(1)).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Text("番組情報なし", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    r.next?.let { n ->
                        Text(
                            "次 ${hm(n.startAt)} ${n.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { vm.open(n.id) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// ============ 番組表グリッド ============
private val MIN_DP = 2.2f      // 1分あたりの高さ(dp)
private val COL_W = 150.dp
private val TIME_W = 36.dp

@Composable
fun GridScreen(vm: MainViewModel) {
    val channels by vm.channels.collectAsState()
    val programs by vm.gridPrograms.collectAsState()
    val date by vm.gridDate.collectAsState()
    val now by vm.now.collectAsState()
    val favIds by vm.favoriteIds.collectAsState()
    if (channels.isEmpty()) { EmptyHint(vm); return }

    val today = broadcastDate(System.currentTimeMillis())
    val days = (0L..6L).map { today.plusDays(it) }
    val dayStart = dayStartMillis(date)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current

    // 今日を開いたら現在時刻付近までスクロール
    LaunchedEffect(date, programs.isNotEmpty()) {
        val minutes = if (date == today) ((now - dayStart) / 60_000L - 30).coerceAtLeast(0) else 0
        vScroll.scrollTo(with(density) { (minutes * MIN_DP).dp.roundToPx() })
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days) { d ->
                FilterChip(
                    selected = d == date, onClick = { vm.gridDate.value = d },
                    label = { Text(if (d == today) "今日" else MDE.format(d)) },
                )
            }
        }
        // チャンネル名ヘッダー（横スクロールは本体と連動）
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            Spacer(Modifier.width(TIME_W))
            Row(Modifier.horizontalScroll(hScroll)) {
                channels.forEach { ch ->
                    Text(
                        "${ch.number} ${ch.name}", modifier = Modifier.width(COL_W).padding(4.dp),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val totalH = (24 * 60 * MIN_DP).dp
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            // 時刻の列
            Box(Modifier.width(TIME_W).height(totalH)) {
                for (h in 0 until 24) {
                    Text(
                        "${(h + DAY_START_HOUR.toInt()) % 24}",
                        modifier = Modifier.offset(y = (h * 60 * MIN_DP).dp).height((60 * MIN_DP).dp).width(TIME_W)
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(top = 2.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(Modifier.horizontalScroll(hScroll).height(totalH).clipToBounds()) {
                Row {
                    channels.forEach { ch ->
                        Box(Modifier.width(COL_W).height(totalH)) {
                            programs.filter { it.channelId == ch.id }.forEach { p -> ProgramCell(p, dayStart, p.id in favIds) { vm.open(p.id) } }
                        }
                    }
                }
                // 現在時刻ライン
                if (now in dayStart until dayStart + 24 * 3600_000L) {
                    val y = ((now - dayStart) / 60_000f * MIN_DP).dp
                    Box(Modifier.offset(y = y).width(COL_W * channels.size).height(2.dp).background(Color(0xFFE53935)))
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(p: ProgramEntity, dayStart: Long, fav: Boolean, onClick: () -> Unit) {
    val dayEnd = dayStart + 24 * 3600_000L
    val s = maxOf(p.startAt, dayStart)
    val e = minOf(p.endAt, dayEnd)
    if (e <= s) return
    val top = ((s - dayStart) / 60_000f * MIN_DP).dp
    val h = ((e - s) / 60_000f * MIN_DP).dp
    Column(
        Modifier.offset(y = top).height(h).width(COL_W)
            .background(genreColor(p.genre))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onClick)
            .padding(3.dp)
            .clipToBounds()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hm(p.startAt), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (fav) Icon(Icons.Filled.NotificationsActive, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Text(p.title, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = Color.Unspecified)
        if (h > 60.dp && p.description.isNotBlank()) {
            Text(p.description, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============ 検索 ============
@Composable
fun SearchScreen(vm: MainViewModel) {
    val q by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    var band by rememberSaveable { mutableStateOf("ALL") }
    val shown = if (band == "ALL") results else results.filter { it.band == band }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("番組名・出演者・キーワード") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("ALL" to "すべて", "GR" to "地デジ", "BS" to "BS", "CS" to "CS").forEach { (k, l) ->
                FilterChip(selected = band == k, onClick = { band = k }, label = { Text(l) })
            }
        }
        if (q.isNotBlank()) {
            OutlinedButton(onClick = { vm.addKeyword(q) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Icon(Icons.Filled.NotificationsActive, null); Spacer(Modifier.width(6.dp)); Text("「${q.trim()}」を含む番組を通知")
            }
        }
        LazyColumn {
            items(shown, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${mdhm(p.startAt)}  ${p.channelNumber} ${p.channelName}") },
                    modifier = Modifier.clickable { vm.open(p) },
                )
                HorizontalDivider()
            }
            if (q.isNotBlank() && shown.isEmpty()) {
                item { Text("見つかりませんでした", Modifier.padding(16.dp)) }
            }
        }
    }
}

// ============ 通知（お気に入り）＋設定 ============
@Composable
fun FavoritesScreen(vm: MainViewModel) {
    val favs by vm.favorites.collectAsState()
    val minutes by vm.minutesBefore.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val last by vm.lastUpdated.collectAsState()
    var editUrl by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("通知する番組") }
        val progs = favs.filter { it.type == FavoriteEntity.PROGRAM }
        if (progs.isEmpty()) item { Hint("番組をタップして「通知する」を押すと、ここに並びます。") }
        items(progs, key = { "p${it.id}" }) { f ->
            ListItem(
                headlineContent = { Text(f.title ?: "") },
                supportingContent = { Text("${f.startAt?.let { mdhm(it) } ?: ""}  ・${f.minutesBefore}分前に通知") },
                trailingContent = { IconButton(onClick = { vm.removeFavorite(f) }) { Icon(Icons.Filled.Delete, "削除") } },
                modifier = Modifier.clickable { f.programId?.let { vm.open(it) } },
            )
        }
        item { SectionTitle("キーワード通知") }
        val kws = favs.filter { it.type == FavoriteEntity.KEYWORD }
        if (kws.isEmpty()) item { Hint("検索画面から「〜を含む番組を通知」で登録できます（例：ブローウィンズ）。") }
        items(kws, key = { "k${it.id}" }) { f ->
            ListItem(
                headlineContent = { Text(f.keyword ?: "") },
                supportingContent = { Text("番組名に含まれると${f.minutesBefore}分前に通知") },
                trailingContent = { IconButton(onClick = { vm.removeFavorite(f) }) { Icon(Icons.Filled.Delete, "削除") } },
            )
        }
        item { SectionTitle("設定") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("新しく登録する通知のタイミング", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 30).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { vm.setMinutesBefore(m) }, label = { Text("${m}分前") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("番組データのURL", style = MaterialTheme.typography.bodyMedium)
                Text(baseUrl.ifBlank { "未設定" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { editUrl = true }) { Text("変更") }
                Text(
                    "最終更新: " + if (last > 0) mdhm(last) else "まだ",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.refresh() }) { Text("今すぐ更新") }
                Spacer(Modifier.height(24.dp))
                Text(
                    "番組情報の出典：NHK番組表API（NHK）ほか。放送内容は変更になる場合があります。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editUrl) {
        var text by remember { mutableStateOf(baseUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = false },
            title = { Text("番組データのURL") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("https://ユーザー名.github.io/リポジトリ名/epg/") },
                )
            },
            confirmButton = { TextButton(onClick = { vm.setBaseUrl(text); editUrl = false; vm.refresh() }) { Text("保存して更新") } },
            dismissButton = { TextButton(onClick = { editUrl = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SectionTitle(t: String) = Text(
    t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun Hint(t: String) = Text(
    t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
)

// ============ 番組詳細 ============
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(vm: MainViewModel, p: ProgramWithChannel) {
    val favIds by vm.favoriteIds.collectAsState()
    val minutes by vm.minutesBefore.collectAsState()
    val isFav = p.id in favIds
    val upcoming = p.startAt > System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = { vm.closeDetail() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Text("${p.channelNumber} ${p.channelName}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(p.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("${mdhm(p.startAt)} 〜 ${hm(p.endAt)}（${(p.endAt - p.startAt) / 60_000}分）", style = MaterialTheme.typography.bodyMedium)
            if (p.genre.isNotBlank()) Text(p.genre, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (upcoming || isFav) {
                Button(onClick = { vm.toggleFavorite(p) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.NotificationsActive, null); Spacer(Modifier.width(8.dp))
                    Text(if (isFav) "通知を解除" else "放送${minutes}分前に通知する")
                }
            }
        }
    }
}
