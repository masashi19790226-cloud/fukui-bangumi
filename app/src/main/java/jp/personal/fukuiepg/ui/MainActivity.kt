@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.personal.fukuiepg.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 下のタブ。url が null のタブはリンク集。 */
enum class AppTab(val label: String, val icon: ImageVector, val url: String?) {
    GR("地デジ", Icons.Filled.Tv, "https://bangumi.org/epg/td?ggm_group_id=62"),
    BS("BS", Icons.Filled.Satellite, "https://bangumi.org/epg/bs"),
    CS("CS", Icons.Filled.LiveTv, "https://bangumi.org/epg/cs"),
    MORE("その他", Icons.Filled.Menu, null),
}

/** 「その他」タブのリンク集（各社の公式番組表） */
data class Link(val title: String, val note: String, val url: String)

val LINKS = listOf(
    Link("FCTV（福井・さかいケーブルテレビ）番組表", "Cablegate のEPG。FCTVのチャンネル構成で表示", "https://www.cablegate.tv/pc/epg.php?catvid=aBfnjnCF&type=home&areaid=36"),
    Link("FCTV コミュニティチャンネル", "FCTV独自番組の番組表", "https://www.fctv.jp/community/ch.html"),
    Link("FCTV チャンネルラインナップ", "契約コースで見られるチャンネル", "https://www.fctv.jp/catv_service/line"),
    Link("福井放送（FBC）番組表", "FBCの公式番組表", "https://www.fbc.jp/timetable/"),
    Link("福井テレビ 週間番組表", "福井テレビの公式番組表", "https://www.fukui-tv.co.jp/program/week/week_main.php"),
    Link("J:COM 番組表（福井・地上波）", "地上波・BS・CSを切り替えて表示", "https://tvguide.myjcom.jp/?area_id=10036"),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFFFFB74D))
    } else {
        lightColorScheme(primary = Color(0xFF1565C0), secondary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(AppTab.GR) }
    // 「その他」から開いたページ（null ならリンク集を表示）
    var moreOpened by rememberSaveable { mutableStateOf<String?>(null) }

    val prefs = remember { Prefs(context) }
    var hideAds by remember { mutableStateOf(prefs.hideAds) }
    var compact by remember { mutableStateOf(prefs.compact) }
    var extraChannels by remember { mutableStateOf(prefs.extraChannels) }
    val pages = remember {
        AppTab.entries.associateWith { Page(context, it.name, it.url, prefs) }
    }
    val page = pages.getValue(tab)
    val showingWeb = tab.url != null || moreOpened != null
    if (tab.url != null) page.ensureLoaded()
    if (tab == AppTab.MORE && !page.loaded) moreOpened?.let { page.open(it) }

    // 戻るボタン：ページ内で戻る → リンク集に戻る → アプリ終了
    BackHandler(enabled = showingWeb && (page.canGoBack || (tab == AppTab.MORE && moreOpened != null))) {
        if (page.canGoBack) page.web.goBack()
        else moreOpened = null
    }

    // 上のバーはなくして、操作は下の1段のバーにまとめる（番組表をできるだけ広く表示）
    Scaffold(
        bottomBar = {
            BottomBar(
                tab = tab,
                page = page,
                showingWeb = showingWeb,
                onTab = { t ->
                    if (tab == t && t.url != null) pages.getValue(t).home() // もう一度押すと今の番組表へ
                    if (tab == t && t == AppTab.MORE) moreOpened = null
                    tab = t
                },
                onBack = { if (page.canGoBack) page.web.goBack() else moreOpened = null },
                onOpenBrowser = {
                    page.web.url?.let { u ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (showingWeb) {
                // key でタブごとに別のWebViewを差し替える（各タブの表示位置は保たれる）
                androidx.compose.runtime.key(tab) {
                    AndroidView(
                        factory = { (page.web.parent as? ViewGroup)?.removeView(page.web); page.web },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (page.progress < 100) {
                    LinearProgressIndicator(
                        progress = { page.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                    )
                }
                page.pinchZoom?.let { z ->
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                    ) {
                        Text(
                            "$z%", color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            } else {
                LinkList(
                    hideAds = hideAds,
                    onHideAdsChange = { v ->
                        hideAds = v
                        prefs.hideAds = v
                        pages.values.forEach { if (it.loaded) it.web.reload() }
                    },
                    compact = compact,
                    onCompactChange = { v ->
                        compact = v
                        prefs.compact = v
                        pages.values.forEach { if (it.loaded) it.web.reload() }
                    },
                    extraChannels = extraChannels,
                    onExtraChannelsChange = { v ->
                        extraChannels = v
                        prefs.extraChannels = v
                        pages.values.forEach { if (it.loaded) it.web.reload() }
                    },
                    onOpen = { link ->
                        moreOpened = link.url
                        pages.getValue(AppTab.MORE).apply { web.clearHistory(); open(link.url) }
                    },
                )
            }
        }
    }
}

/** 下の1段のバー：地デジ・BS・CS ＋ メニュー ＋ 縮小・倍率・拡大（「その他」はメニューの中から開く） */
@Composable
fun BottomBar(
    tab: AppTab,
    page: Page,
    showingWeb: Boolean,
    onTab: (AppTab) -> Unit,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(AppTab.GR, AppTab.BS, AppTab.CS).forEach { t ->
                BarItem(t.icon, t.label, selected = tab == t, modifier = Modifier.weight(1f)) { onTab(t) }
            }
            // メニュー（押しやすいようにタブの並びに置く）
            Box(Modifier.weight(1f).fillMaxHeight()) {
                BarItem(Icons.Filled.Menu, "メニュー", selected = false, modifier = Modifier.fillMaxSize()) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (showingWeb && (page.canGoBack || tab == AppTab.MORE)) {
                        DropdownMenuItem(
                            text = { Text("戻る") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            onClick = { menu = false; onBack() },
                        )
                    }
                    if (tab.url != null) {
                        DropdownMenuItem(
                            text = { Text("今の時間の番組表へ") },
                            leadingIcon = { Icon(Icons.Filled.Home, null) },
                            onClick = { menu = false; page.home() },
                        )
                    }
                    if (showingWeb) {
                        DropdownMenuItem(
                            text = { Text("再読み込み") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { menu = false; page.web.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text("拡大率を標準（$ZOOM_DEFAULT%）に戻す") },
                            leadingIcon = { Icon(Icons.Filled.ZoomIn, null) },
                            onClick = { menu = false; page.resetZoom() },
                        )
                        DropdownMenuItem(
                            text = { Text("ブラウザで開く") },
                            leadingIcon = { Icon(Icons.Filled.OpenInBrowser, null) },
                            onClick = { menu = false; onOpenBrowser() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("その他・設定") },
                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { menu = false; onTab(AppTab.MORE) },
                    )
                }
            }
            if (showingWeb) {
                IconButton(onClick = { page.zoomOut() }, enabled = page.zoom > ZOOM_MIN, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ZoomOut, "縮小", modifier = Modifier.size(20.dp))
                }
                Text(
                    "${page.pinchZoom ?: page.zoom}%",
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { page.resetZoom() }.padding(horizontal = 2.dp),
                )
                IconButton(onClick = { page.zoomIn() }, enabled = page.zoom < ZOOM_MAX, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ZoomIn, "拡大", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** バーのボタン1つ（アイコン＋小さい文字） */
@Composable
fun BarItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(
            label, color = color, fontSize = 10.sp, lineHeight = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun LinkList(
    hideAds: Boolean,
    onHideAdsChange: (Boolean) -> Unit,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    extraChannels: Boolean,
    onExtraChannelsChange: (Boolean) -> Unit,
    onOpen: (Link) -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("広告を隠す") },
                supportingContent = { Text("番組表ページの広告枠を非表示にします（隠しきれない場合もあります）") },
                trailingContent = { Switch(checked = hideAds, onCheckedChange = onHideAdsChange) },
            )
            ListItem(
                headlineContent = { Text("番組表を広く表示") },
                supportingContent = { Text("Gガイドのロゴ・ログイン・検索欄を隠し、日付などの切り替えだけ残します") },
                trailingContent = { Switch(checked = compact, onCheckedChange = onCompactChange) },
            )
            ListItem(
                headlineContent = { Text("地デジに HAB・MRO を追加") },
                supportingContent = { Text("石川の北陸朝日放送（HAB）と北陸放送（MRO）の列を、福井の番組表の右に並べます") },
                trailingContent = { Switch(checked = extraChannels, onCheckedChange = onExtraChannelsChange) },
            )
            HorizontalDivider()
        }
        items(LINKS) { l ->
            ListItem(
                headlineContent = { Text(l.title) },
                supportingContent = { Text(l.note) },
                modifier = Modifier.clickable { onOpen(l) },
            )
            HorizontalDivider()
        }
        item {
            Text(
                "各番組表は、それぞれの提供元のWebサイトを表示しています。\n拡大縮小：2本指でピンチ、または下のバーの －／＋（タブごとに記憶）\n赤い横線が現在時刻です\nもう一度同じタブを押すと今の時間の番組表に戻ります\nバージョン $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
