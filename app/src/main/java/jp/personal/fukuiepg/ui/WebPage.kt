package jp.personal.fukuiepg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** アプリ設定（端末に保存） */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // v1.8: 標準の拡大率を80%に変えたので、これまで保存していた拡大率を一度だけ80%に戻す
        if (!sp.getBoolean("zoomReset_v1_8", false)) {
            val e = sp.edit()
            sp.all.keys.filter { it.startsWith("zoom_") }.forEach { e.remove(it) }
            e.putBoolean("zoomReset_v1_8", true).apply()
        }
    }

    fun zoom(key: String): Int = sp.getInt("zoom_$key", ZOOM_DEFAULT)
    fun setZoom(key: String, v: Int) = sp.edit().putInt("zoom_$key", v).apply()

    var hideAds: Boolean
        get() = sp.getBoolean("hideAds", true)
        set(v) = sp.edit().putBoolean("hideAds", v).apply()

    /** 番組表を広く表示（Gガイドの見出し部分を隠す） */
    var compact: Boolean
        get() = sp.getBoolean("compact", true)
        set(v) = sp.edit().putBoolean("compact", v).apply()

    /** 地デジに石川のHAB・MROを追加 */
    var extraChannels: Boolean
        get() = sp.getBoolean("extraChannels", true)
        set(v) = sp.edit().putBoolean("extraChannels", v).apply()
}

const val ZOOM_MIN = 50
const val ZOOM_MAX = 200
const val ZOOM_STEP = 5
/** 最初の拡大率（「標準に戻す」もこの値） */
const val ZOOM_DEFAULT = 80

/**
 * タブごとのWebViewと、その表示状態。
 * 拡大率はページの CSS zoom で変えて、タブごとに端末へ保存する（次回起動時もそのまま）。
 */
class Page(context: Context, val key: String, val homeUrl: String?, private val prefs: Prefs) {
    var progress by mutableIntStateOf(100)
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var zoom by mutableIntStateOf(prefs.zoom(key))
    /** ピンチ中の拡大率（指を離すまでの表示用。null ならピンチしていない） */
    var pinchZoom by mutableStateOf<Int?>(null)
    var loaded = false

    private var pinchFactor = 1f

    init {
        // CableGate は「どのケーブル局か」をクッキーで覚える。初回から福井ケーブルテレビの番組表が出るよう先に入れておく
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://www.cablegate.tv", "catvId=aBfnjnCF; path=/; max-age=31536000")
            setCookie("https://www.cablegate.tv", "areaId=36; path=/; max-age=31536000")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    val web: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // 拡大縮小はアプリ側（下のピンチ処理・ボタン）で行う
        settings.setSupportZoom(true)        // viewport の倍率指定を効かせる
        settings.builtInZoomControls = false // ブラウザ標準のピンチは使わない
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

            override fun onPageCommitVisible(view: WebView, url: String?) {
                applyPageSettings()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                this@Page.canGoBack = view.canGoBack()
                this@Page.title = view.title.orEmpty()
                applyPageSettings()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@Page.progress = newProgress
            }
        }

        // 2本指のピンチで拡大縮小。ピンチ中は画面ごと拡大して見せ、指を離したらページの拡大率に反映する
        val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchFactor = 1f
                pivotX = d.focusX
                pivotY = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val minF = ZOOM_MIN.toFloat() / zoom
                val maxF = ZOOM_MAX.toFloat() / zoom
                pinchFactor = (pinchFactor * d.scaleFactor).coerceIn(minF, maxF)
                scaleX = pinchFactor
                scaleY = pinchFactor
                pinchZoom = roundZoom(zoom * pinchFactor)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaleX = 1f
                scaleY = 1f
                pinchZoom = null
                setZoomValue(roundZoom(zoom * pinchFactor))
            }
        })
        setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            // ピンチ中はページのスクロールを止める
            detector.isInProgress || e.pointerCount > 1 && e.actionMasked == MotionEvent.ACTION_MOVE
        }
    }

    private fun roundZoom(v: Float): Int =
        ((v / ZOOM_STEP).roundToInt() * ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)

    fun setZoomValue(v: Int) {
        val z = v.coerceIn(ZOOM_MIN, ZOOM_MAX)
        zoom = z
        prefs.setZoom(key, z)
        applyZoom()
    }

    fun zoomIn() = setZoomValue((zoom / ZOOM_STEP + 1) * ZOOM_STEP)
    fun zoomOut() = setZoomValue(((zoom + ZOOM_STEP - 1) / ZOOM_STEP - 1) * ZOOM_STEP)
    fun resetZoom() = setZoomValue(ZOOM_DEFAULT)

    /**
     * 拡大率はページの viewport（表示倍率）で変える。
     * ブラウザのピンチ拡大と同じしくみなので、番組表の時刻列と番組欄がずれない。
     */
    private fun applyZoom() {
        web.evaluateJavascript("(${ZOOM_JS})(${zoom / 100.0});", null)
    }

    fun applyPageSettings() {
        applyZoom()
        if (prefs.hideAds) web.evaluateJavascript(HIDE_ADS_JS, null)
        if (prefs.compact) web.evaluateJavascript(COMPACT_JS, null)
        web.evaluateJavascript(NOW_LINE_JS, null)
        web.evaluateJavascript(NOW_LINE_CABLEGATE_JS, null)
        if (prefs.extraChannels) web.evaluateJavascript(EXTRA_CHANNELS_JS, null)
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

/**
 * 表示倍率を変えるスクリプト。元のページの横幅（スマホ用ならその幅、PC用なら980px）を基準に、
 * 倍率 z のときは「幅 = 基準幅 / z」で組み立てて「z 倍」で表示する。
 */
const val ZOOM_JS = """
function(z){
  var m = document.querySelector('meta[name=viewport]');
  if (!m) {
    m = document.createElement('meta'); m.name = 'viewport';
    m.setAttribute('data-fukui-orig', '');
    (document.head || document.documentElement).appendChild(m);
  } else if (!m.hasAttribute('data-fukui-orig')) {
    m.setAttribute('data-fukui-orig', m.getAttribute('content') || '');
  }
  var orig = m.getAttribute('data-fukui-orig');
  var sw = screen.width || 360;
  var base = 980;
  var mw = /width\s*=\s*([^,\s]+)/.exec(orig);
  if (mw) base = (mw[1] === 'device-width') ? sw : (parseFloat(mw[1]) || 980);
  var scale = sw / base * z;
  m.setAttribute('content', 'width=' + Math.round(base / z) + ', initial-scale=' + scale +
    ', minimum-scale=' + scale + ', maximum-scale=' + scale + ', user-scalable=no');
  document.documentElement.style.zoom = '';
}
"""

/**
 * 番組表を広く表示するスクリプト（Gガイド用）。
 * ロゴ・ログイン・検索欄・上の広告帯を隠し、「番組表の種類／地域／日付」の切り替え行だけ残す。
 * Gガイドは見出しの高さに合わせて番組表の位置を計算し直すので、最後に resize を送って再計算させる。
 */
const val COMPACT_JS = """
(function(){
  if (location.host.indexOf('bangumi.org') < 0) return;
  if (!document.getElementById('fukui-compact')) {
    var st = document.createElement('style');
    st.id = 'fukui-compact';
    st.textContent = '.sticking-banner,.pc_header,.second_line,.jump_yesterday_menu,#ad_area{display:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  window.dispatchEvent(new Event('resize'));
  setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 500);
})();
"""

/**
 * 現在時刻の赤い横線を番組表に引くスクリプト（Gガイド用）。
 * 番組ごとの開始・終了時刻（s / e 属性）と位置から、今の時刻の高さを計算する。5秒ごとに引き直す。
 * 番組表の中には要素を足さず、ページの一番上の階層（body）に重ねて描く（Gガイドの動きを邪魔しないため）。
 * 今日以外の日付を表示しているときは線を出さない。
 */
const val NOW_LINE_JS = """
(function(){
  if (location.host.indexOf('bangumi.org') < 0) return;
  function pad(n){ return (n < 10 ? '0' : '') + n; }
  function stamp(d){ return '' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + pad(d.getHours()) + pad(d.getMinutes()); }
  function toDate(s){ return new Date(+s.slice(0,4), +s.slice(4,6) - 1, +s.slice(6,8), +s.slice(8,10), +s.slice(10,12)); }
  function draw(){
    var line = document.getElementById('fukui-now-line');
    var now = new Date(), ns = stamp(now);
    var uls = document.querySelectorAll('ul[id^="program_line_"]');
    var hit = null, ul = null;
    for (var i = 0; i < uls.length && !hit; i++) {
      var lis = uls[i].children;
      for (var j = 0; j < lis.length; j++) {
        var s = lis[j].getAttribute('s'), e = lis[j].getAttribute('e');
        if (s && e && s <= ns && ns < e && lis[j].offsetHeight > 0) { hit = lis[j]; ul = uls[i]; break; }
      }
    }
    if (!hit) { if (line) line.remove(); return; }
    var st = toDate(hit.getAttribute('s')), en = toDate(hit.getAttribute('e'));
    // Gガイドの番組表の中には手を入れず、ページ全体（body）の上に重ねて線を引く
    var hr = hit.getBoundingClientRect();
    var area = document.getElementById('program_area') || ul.parentNode;
    var ar = area.getBoundingClientRect();
    var y = hr.top + window.scrollY + (now - st) / Math.max(en - st, 1) * hr.height;
    if (!line) {
      line = document.createElement('div');
      line.id = 'fukui-now-line';
      line.style.cssText = 'position:absolute;height:0;border-top:2px solid #E53935;z-index:5;pointer-events:none;';
    }
    if (line.parentNode !== document.body) document.body.appendChild(line);
    line.style.top = Math.round(y - 1) + 'px';
    line.style.left = Math.round(ar.left + window.scrollX) + 'px';
    var w = 0;
    for (var k = 0; k < uls.length; k++) { var r = uls[k].getBoundingClientRect(); var lc = uls[k].lastElementChild; var rr = lc ? lc.getBoundingClientRect().right : r.right; if (rr > w) w = rr; }
    line.style.width = Math.max(Math.round(w - ar.left), 0) + 'px';
  }
  window.__fukuiNowLineDraw = draw;
  if (window.__fukuiNowLine) { draw(); return; }
  window.__fukuiNowLine = true;
  draw();
  setInterval(draw, 5000);
})();
"""

/**
 * 現在時刻の赤い横線（CableGate＝CSタブ用）。
 * 番組の data-start-date / data-end-date と位置から今の高さを計算する。2秒ごとに引き直す。
 */
const val NOW_LINE_CABLEGATE_JS = """
(function(){
  if (location.host.indexOf('cablegate.tv') < 0) return;
  function parse(s){ return s ? new Date(s.replace(' ', 'T')) : null; }
  function draw(){
    var inner = document.querySelector('.schedule-table-columns-inner');
    var line = document.getElementById('fukui-now-line');
    if (!inner) { if (line) line.remove(); return; }
    var now = new Date(), hit = null;
    var progs = inner.querySelectorAll('.schedule-column .program[data-start-date]');
    for (var i = 0; i < progs.length; i++) {
      var p = progs[i];
      if (p.offsetHeight <= 0) continue;
      var st = parse(p.getAttribute('data-start-date')), en = parse(p.getAttribute('data-end-date'));
      if (st && en && st <= now && now < en) { hit = { p: p, st: st, en: en }; break; }
    }
    if (!hit) { if (line) line.remove(); return; }
    var col = hit.p.offsetParent;
    var y = (col ? col.offsetTop : 0) + hit.p.offsetTop + (now - hit.st) / Math.max(hit.en - hit.st, 1) * hit.p.offsetHeight;
    if (getComputedStyle(inner).position === 'static') inner.style.position = 'relative';
    if (!line) {
      line = document.createElement('div');
      line.id = 'fukui-now-line';
      line.style.cssText = 'position:absolute;left:0;height:0;border-top:2px solid #E53935;z-index:3;pointer-events:none;';
    }
    if (line.parentNode !== inner) inner.appendChild(line);
    line.style.top = (y - 1) + 'px';
    line.style.width = inner.scrollWidth + 'px';
  }
  window.__fukuiNowLineDraw = draw;
  if (window.__fukuiNowLineCg) { draw(); return; }
  window.__fukuiNowLineCg = true;
  draw();
  // CableGate は番組表をページの読み込み後に組み立て、スクロールなどで描き直すので、こまめに引き直す
  setInterval(draw, 2000);
})();
"""

/**
 * 福井の地上波番組表に、石川エリアの HAB（北陸朝日放送）と MRO（北陸放送）の列を追加するスクリプト（Gガイド用）。
 * 同じ日付の石川の番組表を読み込み、福井の番組表の時刻の目盛りに合わせて並べ直す。
 */
const val EXTRA_CHANNELS_JS = """
(function(){
  if (location.host.indexOf('bangumi.org') < 0) return;
  if (location.pathname !== '/epg/td' || !/ggm_group_id=62\b/.test(location.search)) return;
  if (window.__fukuiExtraCh) return;
  var WANT = ['HAB', 'MRO'];
  function tmin(s){ return Date.UTC(+s.slice(0,4), +s.slice(4,6) - 1, +s.slice(6,8), +s.slice(8,10), +s.slice(10,12)) / 60000; }
  function px(v){ return parseFloat(v) || 0; }

  // 福井の番組表から「時刻 → 縦位置」の対応表を作る（Gガイドは時間帯によって1時間の高さが違うため）
  var pts = [];
  document.querySelectorAll('ul[id^="program_line_"] > li[s][e]').forEach(function(li){
    var top = px(li.style.top), h = px(li.style.height);
    pts.push([tmin(li.getAttribute('s')), top]);
    pts.push([tmin(li.getAttribute('e')), top + h]);
  });
  if (pts.length < 4) return;  // まだ番組表が読み込まれていない（読み込み完了時にもう一度呼ばれる）
  window.__fukuiExtraCh = true;
  pts.sort(function(a, b){ return a[0] - b[0] || a[1] - b[1]; });
  var P = [];
  pts.forEach(function(p){ if (!P.length || p[0] > P[P.length - 1][0]) P.push(p); });
  function ypos(t){
    var lo = 0, hi = P.length - 1;
    if (t <= P[0][0]) { lo = 0; hi = 1; }
    else if (t >= P[hi][0]) { lo = hi - 1; }
    else { while (hi - lo > 1) { var m = (lo + hi) >> 1; if (P[m][0] <= t) lo = m; else hi = m; } }
    var a = P[lo], b = P[hi];
    return a[1] + (t - a[0]) * (b[1] - a[1]) / Math.max(b[0] - a[0], 1);
  }

  var url = location.pathname + location.search.replace(/ggm_group_id=62\b/, 'ggm_group_id=60');
  fetch(url, { credentials: 'same-origin' }).then(function(r){ return r.text(); }).then(function(html){
    var doc = new DOMParser().parseFromString(html, 'text/html');
    var chUl = document.querySelector('#ch_area ul');
    var area = document.getElementById('program_area');
    if (!chUl || !area) return;
    var srcCh = doc.querySelectorAll('#ch_area ul > li');
    var next = document.querySelectorAll('ul[id^="program_line_"]').length + 1;
    for (var i = 0; i < srcCh.length; i++) {
      var name = srcCh[i].textContent;
      if (!WANT.some(function(w){ return name.indexOf(w) >= 0; })) continue;
      var srcUl = doc.getElementById('program_line_' + i);
      if (!srcUl) continue;
      var ul = document.createElement('ul');
      ul.id = 'program_line_' + next;
      Array.prototype.forEach.call(srcUl.children, function(li){
        var s = li.getAttribute('s'), e = li.getAttribute('e');
        if (!s || !e) return;
        var y1 = ypos(tmin(s)), y2 = ypos(tmin(e));
        if (y2 - y1 < 1) return;
        var n = document.importNode(li, true);
        n.style.top = Math.round(y1) + 'px';
        n.style.height = Math.max(Math.round(y2 - y1) - 1, 1) + 'px';
        ul.appendChild(n);
      });
      area.appendChild(ul);
      chUl.appendChild(document.importNode(srcCh[i], true));
      next++;
    }
    window.dispatchEvent(new Event('resize'));
    if (window.__fukuiNowLineDraw) window.__fukuiNowLineDraw();
  }).catch(function(){});
})();
"""

/**
 * 広告を隠すスクリプト（表示だけを隠す。ページの内容は変えない）。
 * - よく使われる広告枠（Google広告・楽天ウィジェットなど）を CSS で非表示
 * - 他サイトから読み込まれる iframe（広告）を非表示にし、広告だけを包んでいた枠も詰める
 * - 画面の下に固定表示されるオーバーレイ広告を非表示
 * あとから差し込まれる広告にも対応するため、ページの変化を監視して繰り返し実行する。
 */
const val HIDE_ADS_JS = """
(function(){
  if (window.__fukuiHideAds) { window.__fukuiHideAds(); return; }
  var css = [
    'ins.adsbygoogle','[id^="google_ads"]','[id^="div-gpt-ad"]','[id*="gpt-ad"]','[class*="gpt-ad"]',
    '[data-google-query-id]','iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
    'iframe[src*="rakuten"]','[id*="rakuten_ad"]','[class*="rakuten-ad"]',
    'iframe[src*="i-mobile"]','iframe[src*="adingo"]','iframe[src*="fluct"]','[id*="fluct"]',
    '[id*="taboola"]','[class*="taboola"]','[id*="logly"]','[class*="logly"]',
    '[class*="ad-banner"]','[class*="adBanner"]','[id*="adBanner"]','[class*="ad_banner"]',
    '.fixed-banner','[data-ad-banner]',
    '#ca-interstitial-ad-container','#interstitial_from_other','#interstitial_browser_back',
    '.ca_profitx_ad_container','[class*="ca_profitx"]','[id*="interstitial"]','[class*="interstitial"]',
    '[id*="geniee"]','[class*="geniee"]','[id^="gn_"]',
    '[class*="adArea"]','[id*="adArea"]','[class*="ad_area"]','[id*="ad_area"]','[class*="ad-area"]'
  ].join(',') + '{display:none!important;}';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  function hide(el){ if (el && el.style) el.style.setProperty('display','none','important'); }

  function sweep(){
    var vh = window.innerHeight || 800;
    // 他サイトの iframe = 広告として隠し、それだけを包んでいた小さな枠も隠す
    var grid = document.getElementById('contents');
    document.querySelectorAll('iframe').forEach(function(f){
      if (grid && grid.contains(f)) return;  // Gガイドの番組表の中には手を入れない
      var src = f.getAttribute('src') || '';
      var other = src === '' || src.indexOf('about:') === 0 || (src.indexOf('//') >= 0 && src.indexOf(location.host) < 0);
      if (!other) return;
      hide(f);
      var p = f.parentElement, n = 0;
      while (p && p !== document.body && n < 4 && p.children.length <= 2 && p.offsetHeight < 400) {
        hide(p); p = p.parentElement; n++;
      }
    });
    // 画面下部に固定表示されているもの（オーバーレイ広告）
    var cands = [];
    for (var i = 0; i < document.body.children.length; i++) {
      var c = document.body.children[i];
      cands.push(c);
      for (var j = 0; j < c.children.length && j < 50; j++) cands.push(c.children[j]);
    }
    var vw = window.innerWidth || 360;
    cands.forEach(function(el){
      if (el.id === 'fukui-now-line') return;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed') return;
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return;
      var r = el.getBoundingClientRect();
      // 画面下部のオーバーレイ広告
      if (r.height > 0 && r.top > vh * 0.55 && r.height < vh * 0.45) { hide(el); return; }
      // 画面全体を覆う暗い幕（全画面広告＝インタースティシャルの背景）
      if (r.width >= vw * 0.9 && r.height >= vh * 0.9) hide(el);
    });
    // 全画面広告が止めたスクロールを元に戻す
    [document.documentElement, document.body].forEach(function(e){
      if (e && e.style && e.style.overflow === 'hidden') e.style.overflow = '';
    });
  }

  var timer = null;
  window.__fukuiHideAds = sweep;
  sweep();
  new MutationObserver(function(){
    if (timer) return;
    timer = setTimeout(function(){ timer = null; sweep(); }, 400);
  }).observe(document.documentElement, {childList: true, subtree: true});
})();
"""
