package vn.tvlive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.Locale

data class Ch(val num: Int, val name: String, val url: String, val group: String) {
    fun label() = "$num   $name"
}

private val AD_HOSTS = hashSetOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
    "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
    "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
)

private fun isAdHost(h: String): Boolean {
    var x = h
    while (true) {
        if (AD_HOSTS.contains(x)) return true
        val i = x.indexOf('.')
        if (i < 0) return false
        x = x.substring(i + 1)
    }
}

/** Phóng video phủ kín khung, ẩn phần còn lại của trang và cố gắng phát ngay (lấy từ app TV cũ). */
private const val TV_FULL_JS = """
(function(){
  if (!window.__tvFullStyle) {
    window.__tvFullStyle = true;
    var st = document.createElement('style');
    st.textContent = 'html,body{overflow:hidden!important;background:#000!important}video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function go(){
    var v = document.querySelector('video');
    if (v) { try { v.muted = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
  }
  go();
  if (!window.__tvFullObs) {
    window.__tvFullObs = true;
    var t = null;
    new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; go(); }, 500); })
      .observe(document.documentElement, {childList:true, subtree:true});
  }
})();
"""

/** Chạy lại TV_FULL_JS rồi trả về true nếu video đang thật sự chạy. */
private const val CHECK_JS = TV_FULL_JS + """;(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&v.readyState>2&&v.currentTime>0);})()"""

private const val GOLD = 0xFFFFD27A.toInt()
private const val GREEN = 0xFF7CFC9A.toInt()
private const val BLUE = 0xFF3A7BFF.toInt()

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    // ---- dữ liệu ----
    private val all = ArrayList<Ch>()                 // tất cả kênh, đánh số 1..N theo thứ tự trong channels.json
    private val groupNames = ArrayList<String>()
    private val groupChs = ArrayList<List<Ch>>()
    private val favs = LinkedHashSet<String>()        // url các kênh yêu thích
    private var cur: Ch? = null                       // kênh đang xem
    private var pending: Ch? = null                   // kênh đang "lướt" tới, chưa tải
    private var shownList: List<Ch> = emptyList()     // danh sách kênh đang hiện bên phải
    private var leftSel = 1                           // 0 = Yêu thích, i>0 = groupNames[i-1]

    // ---- giao diện ----
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var fullLayer: FrameLayout
    private lateinit var osd: TextView
    private lateinit var osdTime: TextView
    private lateinit var msg: TextView
    private lateinit var overlay: LinearLayout
    private lateinit var groupList: ListView
    private lateinit var chList: ListView
    private lateinit var rightTitle: TextView
    private lateinit var leftAd: RowAdapter
    private lateinit var rightAd: RowAdapter
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null

    private var stuck = 0
    private var stopped = false
    private var longDone = false
    private var lastBack = 0L
    private val numBuf = StringBuilder()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadChannels()
        loadPrefs()
        buildUi()
        hideSystemBars()
        val last = getSharedPreferences("tv", MODE_PRIVATE).getString("last", null)
        val start = all.firstOrNull { it.url == last }
        if (start != null) ui.postDelayed({ if (!isFinishing) play(start) }, 300)
        else showOverlay(null)
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            web.onResume()
            cur?.let { startWatch(); load(it.url) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Ra khỏi app (Home, tắt TV...): dừng hẳn video + âm thanh để đỡ tốn CPU/mạng
        stopped = true
        ui.removeCallbacks(watch)
        ui.removeCallbacks(zapRun)
        try { web.loadUrl("about:blank"); web.onPause() } catch (e: Exception) { }
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ---------------------------------------------------------------- dữ liệu

    private fun loadChannels() {
        try {
            val txt = assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(txt).getJSONArray("groups")
            var n = 1
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val gname = g.getString("name")
                val chs = g.getJSONArray("channels")
                val list = ArrayList<Ch>()
                for (j in 0 until chs.length()) {
                    val c = chs.getJSONObject(j)
                    val ch = Ch(n++, c.getString("name"), c.getString("url"), gname)
                    list.add(ch); all.add(ch)
                }
                if (list.isNotEmpty()) { groupNames.add(gname); groupChs.add(list) }
            }
        } catch (e: Exception) { }
    }

    private fun loadPrefs() {
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        favs.addAll(sp.getStringSet("favs", emptySet()) ?: emptySet())
    }

    private fun saveFavs() {
        getSharedPreferences("tv", MODE_PRIVATE).edit().putStringSet("favs", HashSet(favs)).apply()
    }

    private fun listFor(gi: Int): List<Ch> = if (gi == 0) all.filter { it.url in favs } else groupChs[gi - 1]

    // ---------------------------------------------------------------- giao diện

    private fun pill(sp: Float) = TextView(this).apply {
        textSize = sp; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = GradientDrawable().apply { setColor(0xB0000000.toInt()); cornerRadius = dp(10).toFloat() }
        visibility = View.GONE
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 24f; setTextColor(GOLD); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), 0, 0, dp(10))
    }

    private fun mkList() = ListView(this).apply {
        id = View.generateViewId()
        divider = null
        dividerHeight = dp(4)
        setSelector(GradientDrawable().apply { setColor(BLUE); cornerRadius = dp(8).toFloat() })
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private inner class RowAdapter(private val count: () -> Int, private val bind: (TextView, Int) -> Unit) : BaseAdapter() {
        override fun getCount() = count()
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 22f; setTextColor(Color.WHITE)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                minHeight = dp(52); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            bind(t, position)
            return t
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true }

        web = WebView(this)
        setupWeb()
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(fullLayer, FrameLayout.LayoutParams(-1, -1))

        osd = pill(30f)
        root.addView(osd, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START; setMargins(dp(32), dp(24), 0, 0) })
        osdTime = pill(24f)
        root.addView(osdTime, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(24), dp(32), 0) })
        msg = pill(22f)
        root.addView(msg, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(40)) })

        // ---- bảng chọn kênh: trái = đài, phải = kênh ----
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF00E0E16.toInt())
            setPadding(dp(40), dp(28), dp(40), dp(16))
            visibility = View.GONE
        }
        groupList = mkList(); chList = mkList()
        groupList.nextFocusRightId = chList.id
        chList.nextFocusLeftId = groupList.id

        leftAd = RowAdapter({ groupNames.size + 1 }) { t, p ->
            t.text = if (p == 0) "★  Yêu thích (${favs.size})" else groupNames[p - 1]
            t.setTextColor(if (p == leftSel) GOLD else Color.WHITE)
            t.typeface = if (p == leftSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        rightAd = RowAdapter({ shownList.size }) { t, p ->
            val c = shownList[p]
            t.text = (if (c.url in favs) "★ " else "") + c.label()
            t.setTextColor(if (c == cur) GREEN else Color.WHITE)
            t.typeface = if (c == cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        groupList.adapter = leftAd
        chList.adapter = rightAd

        groupList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != leftSel) setGroup(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupList.setOnItemClickListener { _, _, _, _ -> chList.requestFocus() }
        chList.setOnItemClickListener { _, _, pos, _ -> shownList.getOrNull(pos)?.let { play(it) } }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(title("ĐÀI"))
        left.addView(groupList, LinearLayout.LayoutParams(-1, 0, 1f))
        rightTitle = title("")
        val hint = TextView(this).apply {
            text = "OK: xem kênh     Giữ OK / nút Đỏ: thêm-bỏ ★ yêu thích     Back: đóng"
            textSize = 16f; setTextColor(0xFFAAAAAA.toInt()); setPadding(dp(16), dp(10), 0, 0)
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        right.addView(rightTitle)
        right.addView(chList, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(hint)
        overlay.addView(left, LinearLayout.LayoutParams(0, -1, 3f))
        overlay.addView(right, LinearLayout.LayoutParams(0, -1, 7f))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        setGroup(if (groupNames.isEmpty()) 0 else 1)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        // WebView không nhận focus: mọi phím của remote do Activity xử lý
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // UA như Chrome thật (bỏ "; wv" và "Version/4.0")
        s.userAgentString = WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace(" Version/4.0", "")
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        } catch (e: Exception) { }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.BLACK)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val sc = (request?.url?.scheme ?: return false).lowercase()
                return sc != "https" && sc != "http"      // không mở app ngoài
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val h = (request?.url?.host ?: return null).lowercase()
                return if (isAdHost(h)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null || url.startsWith("about:")) return
                view?.evaluateJavascript(TV_FULL_JS, null)
                hideMsg()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                val rq = request ?: return
                if (!rq.isForMainFrame) return
                if (rq.url?.toString()?.startsWith("about:") == true) return
                showMsg("Không tải được kênh — tự thử lại sau 5 giây", 6000)
                ui.removeCallbacks(retryRun)
                ui.postDelayed(retryRun, 5000)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (fullView != null) { callback?.onCustomViewHidden(); return }
                fullView = view; fullCb = callback
                view.setBackgroundColor(Color.BLACK)
                fullLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullLayer.visibility = View.VISIBLE
            }
            override fun onHideCustomView() { hideFull() }
        }
    }

    private fun hideFull() {
        val v = fullView ?: return
        fullLayer.removeView(v); fullLayer.visibility = View.GONE
        fullView = null
        fullCb?.onCustomViewHidden(); fullCb = null
    }

    // ---------------------------------------------------------------- phát kênh

    private val retryRun = Runnable { reloadCur() }

    private fun load(url: String) {
        showMsg("Đang tải kênh…", 0)
        web.loadUrl(url)
    }

    private fun reloadCur() { cur?.let { load(it.url) } }

    private fun play(c: Ch) {
        ui.removeCallbacks(zapRun); ui.removeCallbacks(retryRun)
        pending = null
        cur = c
        getSharedPreferences("tv", MODE_PRIVATE).edit().putString("last", c.url).apply()
        hideOverlay()
        showOsd(c.label())
        startWatch()
        load(c.url)
    }

    // Lướt kênh: chỉ cập nhật tên kênh ngay, trang chỉ tải sau 0,6 giây không bấm nữa (đỡ giật trên máy yếu)
    private val zapRun = Runnable { pending?.let { play(it) } }

    private fun preview(c: Ch) {
        pending = c
        showOsd(c.label())
        ui.removeCallbacks(zapRun)
        ui.postDelayed(zapRun, 600)
    }

    private fun zap(delta: Int) {
        if (all.isEmpty()) return
        val base = pending ?: cur ?: all[0]
        val i = all.indexOf(base).coerceAtLeast(0)
        preview(all[(i + delta).mod(all.size)])
    }

    private fun zapGroup(delta: Int) {
        if (groupNames.isEmpty()) return
        val base = pending ?: cur ?: all[0]
        val gi = groupNames.indexOf(base.group).coerceAtLeast(0)
        preview(groupChs[(gi + delta).mod(groupNames.size)][0])
    }

    // Giám sát: sau 15 giây rồi cứ 10 giây kiểm tra video có chạy không; ~40 giây không chạy thì tải lại kênh
    private val watch = object : Runnable {
        override fun run() {
            if (cur == null || isFinishing || stopped) return
            web.evaluateJavascript(CHECK_JS) { r ->
                if (r == "true") {
                    stuck = 0
                } else {
                    stuck++
                    if (stuck >= 4) { stuck = 0; reloadCur() }
                }
            }
            ui.postDelayed(this, 10000)
        }
    }

    private fun startWatch() {
        stuck = 0
        ui.removeCallbacks(watch)
        ui.postDelayed(watch, 15000)
    }

    // ---------------------------------------------------------------- bảng chọn kênh

    private fun overlayOn() = overlay.visibility == View.VISIBLE

    private fun setGroup(gi: Int) {
        leftSel = gi.coerceIn(0, groupNames.size)
        shownList = listFor(leftSel)
        leftAd.notifyDataSetChanged()
        rightAd.notifyDataSetChanged()
        rightTitle.text = if (leftSel == 0) "★ Yêu thích" + (if (shownList.isEmpty()) " — chưa có kênh, giữ OK trên một kênh để thêm" else "")
        else groupNames[leftSel - 1]
    }

    /** Mở bảng chọn; mặc định nhảy tới đúng đài + kênh đang xem. startGroup != null thì mở đài đó. */
    private fun showOverlay(startGroup: Int?) {
        ui.removeCallbacks(zapRun); pending = null
        val c = cur
        val gi = startGroup ?: (if (c == null) 1 else groupNames.indexOf(c.group) + 1).coerceAtLeast(if (groupNames.isEmpty()) 0 else 1)
        setGroup(gi)
        overlay.visibility = View.VISIBLE
        groupList.setSelection(leftSel)
        val idx = if (c != null) shownList.indexOf(c).coerceAtLeast(0) else 0
        chList.setSelection(idx)
        chList.requestFocus()
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        root.requestFocus()
    }

    private fun toggleFav(c: Ch) {
        val added = favs.add(c.url)
        if (!added) favs.remove(c.url)
        saveFavs()
        val pos = chList.selectedItemPosition
        val keepGroup = leftSel
        setGroup(keepGroup)
        if (overlayOn() && pos >= 0) chList.setSelection(pos.coerceAtMost((shownList.size - 1).coerceAtLeast(0)))
        Toast.makeText(this, (if (added) "★ Đã thêm: " else "Đã bỏ khỏi yêu thích: ") + c.name, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- OSD

    private val hideOsd = Runnable { osd.visibility = View.GONE; osdTime.visibility = View.GONE }

    private fun showOsd(text: String) {
        val cal = Calendar.getInstance()
        osdTime.text = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        osd.text = text
        osd.visibility = View.VISIBLE; osdTime.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, 3500)
    }

    private val hideMsgRun = Runnable { msg.visibility = View.GONE }

    private fun showMsg(t: String, autoHideMs: Long) {
        msg.text = t; msg.visibility = View.VISIBLE
        ui.removeCallbacks(hideMsgRun)
        if (autoHideMs > 0) ui.postDelayed(hideMsgRun, autoHideMs)
    }

    private fun hideMsg() { ui.removeCallbacks(hideMsgRun); msg.visibility = View.GONE }

    // ---------------------------------------------------------------- phím số

    private val numRun = Runnable { commitNum() }

    private fun digit(d: Int) {
        if (numBuf.length >= 3) numBuf.setLength(0)
        numBuf.append(d)
        showOsd("Kênh: $numBuf")
        ui.removeCallbacks(numRun)
        if (numBuf.length >= 3) commitNum() else ui.postDelayed(numRun, 1800)
    }

    private fun commitNum() {
        ui.removeCallbacks(numRun)
        val n = numBuf.toString().toIntOrNull()
        numBuf.setLength(0)
        val c = all.firstOrNull { it.num == n }
        if (c != null) play(c) else showOsd("Không có kênh số ${n ?: ""}")
    }

    // ---------------------------------------------------------------- phím điều khiển

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)

        if (overlayOn()) {
            // Giữ OK trên một kênh = thêm/bỏ yêu thích
            if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && chList.hasFocus()) {
                if (down && e.repeatCount == 1) {
                    shownList.getOrNull(chList.selectedItemPosition)?.let { toggleFav(it) }
                    longDone = true
                    return true
                }
                if (down && e.repeatCount > 1) return true
                if (!down && longDone) { longDone = false; return true }
                if (down) longDone = false
            }
            if (k == KeyEvent.KEYCODE_PROG_RED) {
                if (down && e.repeatCount == 0) {
                    if (chList.hasFocus()) shownList.getOrNull(chList.selectedItemPosition)?.let { toggleFav(it) }
                }
                return true
            }
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_GUIDE) {
                if (down && cur != null) hideOverlay()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        // ---- đang xem: remote điều khiển trực tiếp ----
        val dg = when (k) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        val mapped = dg >= 0 || when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE -> true
            else -> false
        }
        if (!mapped) return super.dispatchKeyEvent(e)     // phím âm lượng, v.v. để hệ thống xử lý
        if (!down) return true
        if (dg >= 0) { if (e.repeatCount == 0) digit(dg); return true }
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_UP -> zapGroup(+1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_DOWN -> zapGroup(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> if (e.repeatCount == 0) showOverlay(null)
            KeyEvent.KEYCODE_INFO -> cur?.let { showOsd(it.label() + "   •   " + it.group) }
            KeyEvent.KEYCODE_PROG_RED -> if (e.repeatCount == 0) (pending ?: cur)?.let { toggleFav(it) }
            KeyEvent.KEYCODE_PROG_GREEN -> if (e.repeatCount == 0) showOverlay(0)
            KeyEvent.KEYCODE_PROG_YELLOW -> if (e.repeatCount == 0) { showMsg("Tải lại kênh…", 3000); reloadCur() }
            KeyEvent.KEYCODE_PROG_BLUE -> if (e.repeatCount == 0) showOverlay(null)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullView != null -> hideFull()
            overlayOn() -> if (cur != null) hideOverlay() else finish()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2000) finish()
                else { lastBack = now; Toast.makeText(this, "Nhấn Back lần nữa để thoát", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
