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
    if (window.__tvPaused) return;
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

/** Tắt tiếng + dừng video ngay (khi về bảng chọn kênh). */
private const val PAUSE_JS = """window.__tvPaused=true;Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(v){try{v.muted=true;v.pause();}catch(e){}});"""

/** Cho phép phát lại (khi quay lại xem đúng kênh cũ). */
private const val RESUME_JS = """window.__tvPaused=false;"""

private const val GOLD = 0xFFFFD27A.toInt()
private const val GREEN = 0xFF7CFC9A.toInt()
private const val BLUE = 0xFF1E90FF.toInt()   // xanh dương sáng cho viền chọn

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    // ---- dữ liệu ----
    private val all = ArrayList<Ch>()                 // tất cả kênh, đánh số 1..N theo thứ tự trong channels.json
    private val groupNames = ArrayList<String>()
    private val groupChs = ArrayList<List<Ch>>()
    private var cur: Ch? = null                       // kênh đang xem
    private var pending: Ch? = null                   // kênh đang "lướt" tới, chưa tải
    private var shownList: List<Ch> = emptyList()     // danh sách kênh đang hiện bên phải
    private var leftSel = 0                           // chỉ số đài đang chọn trong groupNames

    // ---- giao diện ----
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var fullLayer: FrameLayout
    private lateinit var osd: TextView
    private lateinit var osdTime: TextView
    private lateinit var msg: TextView
    private lateinit var cover: FrameLayout
    private lateinit var coverNum: TextView
    private lateinit var coverName: TextView
    private var countdown = 0
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
    private var lastBack = 0L
    private val numBuf = StringBuilder()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadChannels()
        buildUi()
        hideSystemBars()
        // Mở app mới (kể cả sau khi đã gạt khỏi đa nhiệm): luôn bắt đầu lại từ đầu, hiện "Tivi của Bắp"
        showOverlay(true)
        showWelcome()
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

    private fun listFor(gi: Int): List<Ch> = groupChs[gi]

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
        setSelector(GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(4), BLUE); cornerRadius = dp(10).toFloat() })
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

        // Điện thoại: chạm vào màn hình đang xem là mở bảng chọn kênh (lớp trong suốt phủ lên WebView)
        val tapLayer = View(this).apply { setOnClickListener { if (!overlayOn()) showOverlay(false) } }
        root.addView(tapLayer, FrameLayout.LayoutParams(-1, -1))

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        root.addView(fullLayer, FrameLayout.LayoutParams(-1, -1))

        osd = pill(30f)
        root.addView(osd, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START; setMargins(dp(32), dp(24), 0, 0) })
        osdTime = pill(24f)
        root.addView(osdTime, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(24), dp(32), 0) })
        msg = pill(22f)
        root.addView(msg, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, dp(40)) })

        // ---- màn che lúc đang tải kênh: đếm ngược 5..1 rồi tự biến mất ----
        cover = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        coverName = TextView(this).apply { textSize = 34f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        coverNum = TextView(this).apply {
            textSize = 110f; setTextColor(BLUE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val coverSub = TextView(this).apply { text = "Đang tải kênh…"; textSize = 20f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER }
        box.addView(coverName); box.addView(coverNum); box.addView(coverSub)
        cover.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))

        // ---- bảng chọn kênh: trái = đài, phải = kênh ----
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(40), dp(28), dp(40), dp(16))
            visibility = View.GONE
        }
        groupList = mkList(); chList = mkList()
        groupList.nextFocusRightId = chList.id
        chList.nextFocusLeftId = groupList.id

        leftAd = RowAdapter({ groupNames.size }) { t, p ->
            val l = groupChs[p]
            // Đài 1 kênh: hiện số kênh. Đài nhiều kênh: hiện dải số (vd 5–13)
            t.text = if (l.size == 1) "${l[0].num}   ${groupNames[p]}" else "${l.first().num}–${l.last().num}   ${groupNames[p]}"
            t.setTextColor(if (p == leftSel) GOLD else Color.WHITE)
            t.typeface = if (p == leftSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        rightAd = RowAdapter({ shownList.size }) { t, p ->
            val c = shownList[p]
            t.text = c.label()
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
        // Đài chỉ có 1 kênh: bấm OK là xem luôn, không phải vào thêm một trang nữa
        groupList.setOnItemClickListener { _, _, pos, _ ->
            val l = groupChs.getOrNull(pos) ?: return@setOnItemClickListener
            if (l.size == 1) {
                play(l[0])
            } else {
                setGroup(pos)               // luôn hiện đúng các kênh của đài vừa bấm
                chList.setSelection(0)
                chList.requestFocus()
            }
        }
        chList.setOnItemClickListener { _, _, pos, _ -> shownList.getOrNull(pos)?.let { play(it) } }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(title("ĐÀI"))
        left.addView(groupList, LinearLayout.LayoutParams(-1, 0, 1f))
        rightTitle = title("")
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        right.addView(rightTitle)
        right.addView(chList, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(left, LinearLayout.LayoutParams(0, -1, 3f))
        overlay.addView(right, LinearLayout.LayoutParams(0, -1, 7f))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        setGroup(0)
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
        hideOverlay(false)
        web.onResume()
        showCover(c.label())
        startWatch()
        load(c.url)
    }

    // Lướt kênh: chỉ cập nhật tên kênh ngay, trang chỉ tải sau 0,6 giây không bấm nữa (đỡ giật trên máy yếu)
    private val tick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) {
                hideCover()
                cur?.let { showOsd(it.label()) }
            } else {
                coverNum.text = countdown.toString()
                ui.postDelayed(this, 1000)
            }
        }
    }

    private fun showCover(name: String) {
        coverName.text = name
        countdown = 5
        coverNum.text = "5"
        cover.visibility = View.VISIBLE
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
    }

    private fun hideCover() {
        ui.removeCallbacks(tick)
        cover.visibility = View.GONE
    }

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
            if (overlayOn()) { ui.postDelayed(this, 10000); return }
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
        if (groupNames.isEmpty()) return
        leftSel = gi.coerceIn(0, groupNames.size - 1)
        val l = listFor(leftSel)
        // Đài chỉ có 1 kênh: bản thân đài là kênh, cột bên phải để trống
        shownList = if (l.size == 1) emptyList() else l
        leftAd.notifyDataSetChanged()
        rightAd.notifyDataSetChanged()
        rightTitle.text = if (l.size == 1) "" else groupNames[leftSel]
        chList.setSelection(0)
    }

    /** Lần đầu mở app (chưa xem kênh nào): bảng bên phải hiện "Tivi của Bắp". */
    private fun showWelcome() {
        shownList = emptyList()
        rightAd.notifyDataSetChanged()
        rightTitle.text = "Tivi của Bắp"
    }

    /** Đồng bộ cột Kênh với đài đang được tô sáng ở cột Đài (không chỉ dựa vào onItemSelected). */
    private fun syncGroup() {
        val p = groupList.selectedItemPosition
        if (p >= 0 && p != leftSel) setGroup(p)
    }


    /** Mở bảng chọn. focusGroups = true: con trỏ ở cột Đài; mặc định nhảy tới đúng kênh đang xem. */
    private fun showOverlay(focusGroups: Boolean) {
        ui.removeCallbacks(zapRun); pending = null
        hideCover()
        val c = cur
        val gi = if (c == null) 0 else groupNames.indexOf(c.group).coerceAtLeast(0)
        setGroup(gi)
        overlay.visibility = View.VISIBLE
        if (c != null) silence()
        groupList.setSelection(leftSel)
        chList.setSelection(if (c != null) shownList.indexOf(c).coerceAtLeast(0) else 0)
        if (focusGroups || shownList.isEmpty()) groupList.requestFocus() else chList.requestFocus()
    }

    /** Về bảng chọn kênh là tắt tiếng ngay. */
    private fun silence() {
        web.evaluateJavascript(PAUSE_JS, null)
        web.onPause()
    }

    /** resume = true: quay lại xem đúng kênh cũ (bật tiếng lại). play() gọi với false vì sắp tải kênh mới. */
    private fun hideOverlay(resume: Boolean = true) {
        val wasOn = overlayOn()
        overlay.visibility = View.GONE
        root.requestFocus()
        if (wasOn && resume && cur != null) {
            web.onResume()
            web.evaluateJavascript(RESUME_JS, null)
            web.evaluateJavascript(TV_FULL_JS, null)
        }
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
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_GUIDE) {
                if (down && cur != null) hideOverlay()
                return true
            }
            if (down && groupList.hasFocus()) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {                // sang cột Kênh: chắc chắn đúng đài
                    syncGroup()
                    if (shownList.isEmpty()) return true               // đài 1 kênh: không có gì ở bên phải
                }
                val r = super.dispatchKeyEvent(e)
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
                    k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_PAGE_DOWN) ui.post { syncGroup() }
                return r
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
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE -> true
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> if (e.repeatCount == 0) showOverlay(false)
            KeyEvent.KEYCODE_INFO -> cur?.let { showOsd(it.label() + "   •   " + it.group) }
            KeyEvent.KEYCODE_PROG_YELLOW -> if (e.repeatCount == 0) { showMsg("Tải lại kênh…", 3000); reloadCur() }
            KeyEvent.KEYCODE_PROG_BLUE -> if (e.repeatCount == 0) showOverlay(false)
        }
        return true
    }

    // Back: đang xem -> mở thẳng cột Đài (không lùi từng trang). Bấm Back 2 lần nhanh (dưới 1,5 giây) -> thoát app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        val quick = now - lastBack < 1500
        lastBack = now
        when {
            fullView != null -> hideFull()
            overlayOn() -> if (cur == null || quick) finish() else hideOverlay()
            quick -> finish()
            else -> showOverlay(true)
        }
    }
}
