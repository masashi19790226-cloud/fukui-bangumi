package jp.personal.fukuiepg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
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

    fun zoom(key: String): Int = sp.getInt("zoom_$key", 100)
    fun setZoom(key: String, v: Int) = sp.edit().putInt("zoom_$key", v).apply()

    var hideAds: Boolean
        get() = sp.getBoolean("hideAds", true)
        set(v) = sp.edit().putBoolean("hideAds", v).apply()
}

const val ZOOM_MIN = 50
const val ZOOM_MAX = 200
const val ZOOM_STEP = 10

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
        ((v / 5f).roundToInt() * 5).coerceIn(ZOOM_MIN, ZOOM_MAX)

    fun setZoomValue(v: Int) {
        val z = v.coerceIn(ZOOM_MIN, ZOOM_MAX)
        zoom = z
        prefs.setZoom(key, z)
        applyZoom()
    }

    fun zoomIn() = setZoomValue((zoom / ZOOM_STEP + 1) * ZOOM_STEP)
    fun zoomOut() = setZoomValue(((zoom + ZOOM_STEP - 1) / ZOOM_STEP - 1) * ZOOM_STEP)
    fun resetZoom() = setZoomValue(100)

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
    '[class*="adArea"]','[id*="adArea"]','[class*="ad_area"]','[id*="ad_area"]','[class*="ad-area"]'
  ].join(',') + '{display:none!important;}';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  function hide(el){ if (el && el.style) el.style.setProperty('display','none','important'); }

  function sweep(){
    var vh = window.innerHeight || 800;
    // 他サイトの iframe = 広告として隠し、それだけを包んでいた小さな枠も隠す
    document.querySelectorAll('iframe').forEach(function(f){
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
    cands.forEach(function(el){
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed') return;
      var r = el.getBoundingClientRect();
      if (r.height > 0 && r.top > vh * 0.55 && r.height < vh * 0.45) hide(el);
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
