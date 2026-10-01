@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package jp.personal.fukuiepg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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

/** タブごとのWebViewと、その表示状態 */
class Page(context: Context, val homeUrl: String?) {
    var progress by mutableIntStateOf(100)
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var loaded = false

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // http/https はアプリ内で表示、それ以外（電話・地図・アプリ起動など）は外部へ
                if (u.scheme == "http" || u.scheme == "https") return false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                this@Page.canGoBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                this@Page.canGoBack = view.canGoBack()
                this@Page.title = view.title.orEmpty()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@Page.progress = newProgress
            }
        }
    }

    fun open(url: String) {
        loaded = true
        web.loadUrl(url)
    }

    /** 初めて表示するときだけ読み込む */
    fun ensureLoaded() {
        if (!loaded && homeUrl != null) open(homeUrl)
    }

    /** トップ（今の時間の番組表）に戻る */
    fun home() {
        val h = homeUrl ?: return
        web.clearHistory()
        open(h)
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(AppTab.GR) }
    // 「その他」から開いたページ（null ならリンク集を表示）
    var moreOpened by rememberSaveable { mutableStateOf<String?>(null) }

    val pages = remember {
        AppTab.entries.associateWith { Page(context, it.url) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showingWeb && page.title.isNotBlank()) page.title else tab.label + "の番組表",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showingWeb && (page.canGoBack || tab == AppTab.MORE)) {
                        IconButton(onClick = {
                            if (page.canGoBack) page.web.goBack() else moreOpened = null
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                    }
                },
                actions = {
                    if (showingWeb) {
                        if (tab.url != null) {
                            IconButton(onClick = { page.home() }) { Icon(Icons.Filled.Home, "今の番組表へ") }
                        }
                        IconButton(onClick = { page.web.reload() }) { Icon(Icons.Filled.Refresh, "再読み込み") }
                        IconButton(onClick = {
                            page.web.url?.let { u ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                            }
                        }) { Icon(Icons.Filled.OpenInBrowser, "ブラウザで開く") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (tab == t && t.url != null) pages.getValue(t).home() // もう一度押すと今の番組表へ
                            if (tab == t && t == AppTab.MORE) moreOpened = null
                            tab = t
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (showingWeb && page.progress < 100) {
                LinearProgressIndicator(progress = { page.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxSize()) {
                if (showingWeb) {
                    // key でタブごとに別のWebViewを差し替える（各タブの表示位置は保たれる）
                    androidx.compose.runtime.key(tab) {
                        AndroidView(
                            factory = { (page.web.parent as? ViewGroup)?.removeView(page.web); page.web },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    LinkList { link ->
                        moreOpened = link.url
                        pages.getValue(AppTab.MORE).apply { web.clearHistory(); open(link.url) }
                    }
                }
            }
        }
    }
}

@Composable
fun LinkList(onOpen: (Link) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
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
                "各番組表は、それぞれの提供元のWebサイトをそのまま表示しています。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
