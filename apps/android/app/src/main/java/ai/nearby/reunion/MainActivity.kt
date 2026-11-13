package ai.nearby.reunion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var adapter: NativePlatform
    private lateinit var gateCover: LinearLayout
    private lateinit var gateText: TextView
    private lateinit var gateButton: Button
    private val stateLock = Any()
    private val latest = linkedMapOf<String, JSONObject>()
    private val arrivalSession = ArrivalSnapshotStore<JSONObject>()
    @Volatile private var trustedPage = false
    private var pageReady = false
    private var offline = false
    private var displayAllowed = false
    private var monitoringRequested = false
    @Volatile private var closed = false
    // A visible split-screen pane may be paused while the adjacent navigation has focus.
    private var foreground = false
    private val arrivalProof = ArrivalProofRecorder(SystemClock::elapsedRealtime) { Log.i("MajoongArrivalProof", it) }
    private var lastUiReport = ""
    private var lastGateReport = ""
    @Volatile private var handledPopupArrivalSeq = 0L

    private val localOrigins = setOf("10.0.2.2", "localhost", "127.0.0.1")
    private fun trusted(uri: Uri): Boolean =
        (uri.scheme == "http" && uri.host in localOrigins && uri.port == 8787) ||
            (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && (uri.port == -1 || uri.port == 443))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val events: EventSink = { event -> runOnUiThread { receive(event) } }
        adapter = if (BuildConfig.PLATFORM_MODE == "pleos") MonitoringClient(applicationContext, events)
            else PlatformAdapter(applicationContext, events)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.addJavascriptInterface(Bridge(), "PleosNative")
        val assets = WebViewAssetLoader.Builder().addPathHandler("/", WebViewAssetLoader.PathHandler { path ->
            if (path.contains("..") || path.startsWith("api/")) return@PathHandler blocked()
            val asset = if (path.isEmpty()) "index.html" else path
            val mime = when (asset.substringAfterLast('.')) {
                "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"
                "svg" -> "image/svg+xml"; "json" -> "application/json"; "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "application/octet-stream"
            }
            try { WebResourceResponse(mime, "UTF-8", this.assets.open("web/$asset")) }
            catch (_: Exception) { blocked(404, "Not Found") }
        }).build()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                trustedPage = trusted(Uri.parse(url))
                pageReady = false
            }
            override fun onPageFinished(view: WebView, url: String) {
                trustedPage = trusted(Uri.parse(url))
                pageReady = trustedPage
                if (pageReady) {
                    val saved = synchronized(stateLock) { latest.values.toList() }
                    saved.forEach(::sendToWeb)
                    receive(nativeEvent("lifecycle", "phase" to "page-ready", "platform" to BuildConfig.PLATFORM_MODE,
                        "offline" to offline, "backgroundGuaranteed" to false))
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!trusted(request.url)) return blocked()
                return assets.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (trusted(request.url)) return false
                // Never give an external document the native bridge. Contacts are dialer drafts only.
                if (request.isForMainFrame && request.hasGesture() && displayAllowed && adapter.isDisplayAllowed()) {
                    val uri = request.url
                    val action = when (uri.scheme) { "tel" -> Intent.ACTION_DIAL; "https" -> Intent.ACTION_VIEW; else -> null }
                    if (action != null) runCatching { startActivity(Intent(action, uri)) }
                }
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !offline) {
                    offline = true
                    receive(nativeEvent("server", "offline" to true,
                        "message" to "서버 미연결 · 번들 화면만 사용"))
                    web.loadUrl("https://appassets.androidplatform.net/?platform=android&offline=1")
                }
            }
        }
        val root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        gateCover = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(247, 248, 250)); setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        gateCover.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_majoong_launcher)
            contentDescription = "마중"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(24) })
        gateText = TextView(this).apply {
            textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.rgb(25, 35, 49))
            setLineSpacing(dp(5).toFloat(), 1f)
            setText(R.string.vehicle_connection_required)
        }
        gateCover.addView(gateText)
        gateButton = Button(this).apply {
            setText(R.string.connect_vehicle)
            textSize = 16f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            minWidth = dp(220)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
                setColor(Color.rgb(36, 109, 235)); cornerRadius = dp(12).toFloat()
            }, null)
            setOnClickListener { startMonitoring() }
        }
        gateCover.addView(gateButton, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(24) })
        gateCover.addView(TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.rgb(93, 105, 120))
            setPadding(0, dp(20), 0, 0)
            setText(R.string.monitoring_explanation)
        })
        gateCover.visibility = if (BuildConfig.PLATFORM_MODE == "pleos") View.VISIBLE else View.GONE
        root.addView(gateCover, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        receive(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE, "connected" to false,
            "offline" to false, "message" to "ready", "backgroundGuaranteed" to false))
        val requested = intent.getStringExtra("webUrl")?.let(Uri::parse)
        val target = if (requested != null && trusted(requested)) requested.toString() else BuildConfig.WEB_URL
        web.loadUrl(target)
        if (BuildConfig.PLATFORM_MODE == "demo") adapter.start()
        handleDebugProbe(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugProbe(intent)
    }

    private fun handleDebugProbe(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        when (intent.getStringExtra("majoongDebugProbe")) {
            "connect" -> startMonitoring()
        }
    }

    private fun startMonitoring() {
        monitoringRequested = true
        if (BuildConfig.PLATFORM_MODE == "pleos") {
            val required = mutableListOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
            if (Build.VERSION.SDK_INT >= 33) required.add("android.permission.POST_NOTIFICATIONS")
            val missing = required
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 41); return }
        }
        adapter.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && monitoringRequested) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) adapter.start()
            else {
                monitoringRequested = false
                receive(nativeEvent("status", "monitoring" to false, "connected" to false,
                    "message" to "monitoring_permissions_required", "backgroundGuaranteed" to false))
            }
        }
    }

    private fun receive(event: JSONObject) = synchronized(stateLock) { receiveLocked(event) }

    private fun receiveLocked(event: JSONObject) {
        if (closed) return
        val type = event.optString("type")
        if (type == "snapshot") {
            val events = event.optJSONObject("events") ?: return
            synchronized(stateLock) {
                // A snapshot is authoritative, including absence of an arrival after a hidden departure.
                latest.keys.removeAll(setOf("status", "vehicle", "arrival", "destination"))
                handledPopupArrivalSeq = 0L
                displayAllowed = false
                arrivalSession.replaceSnapshot(events.optJSONObject("arrival"),
                    events.optJSONObject("vehicle")?.optBoolean("displayAllowed") == true && adapter.isDisplayAllowed())
                val snapshotArrival = events.optJSONObject("arrival")
                val handled = if (snapshotArrival?.optBoolean("popupHandled") == true)
                    snapshotArrival.optLong("arrivalSeq") else 0L
                sendToWeb(nativeEvent("session-reset", "reason" to "service_snapshot", "arrivalPopupHandledSeq" to handled))
                listOf("status", "destination", "vehicle", "arrival").forEach { key ->
                    events.optJSONObject(key)?.let(::receive)
                }
            }
            return
        }
        var invalidateArrival = false
        if (type == "arrival") {
            if (event.optBoolean("popupHandled", false)) handledPopupArrivalSeq = event.optLong("arrivalSeq")
            arrivalSession.onArrival(event, displayAllowed && adapter.isDisplayAllowed())
        } else if (type == "trip-invalidated") {
            arrivalSession.clear()
            handledPopupArrivalSeq = 0L
            invalidateArrival = true
        } else if (type == "vehicle") {
            invalidateArrival = arrivalSession.onVehicle(
                event.optBoolean("displayAllowed", false), event.optString("gear"),
                (event.opt("speedMetersPerSecond") as? Number)?.toDouble(),
            )
        } else if (type == "status" && event.has("monitoring") && !event.optBoolean("monitoring")) {
            arrivalSession.clear()
            handledPopupArrivalSeq = 0L
            invalidateArrival = true
            if (BuildConfig.PLATFORM_MODE == "pleos") {
                gateCover.visibility = View.VISIBLE
                gateText.setText(if (event.optString("message").startsWith("monitoring_permissions") ||
                    event.optString("message") == "monitoring_notification_unavailable")
                    R.string.monitoring_permissions_needed else R.string.vehicle_connection_required)
            }
        }
        synchronized(stateLock) {
            if (invalidateArrival) latest.remove("arrival")
            if (type == "trip-invalidated") latest.remove("destination")
            if (type != "trip-invalidated") latest[type] = event
        }
        if (type == "vehicle") {
            displayAllowed = event.optBoolean("displayAllowed", false)
            if (BuildConfig.DEBUG) {
                val summary = "vehicle_gate allowed=$displayAllowed gear=${event.optString("gear")} speed=${event.opt("speedMetersPerSecond")} reason=${event.optString("reason")}" 
                if (summary != lastGateReport) { lastGateReport = summary; Log.i("MajoongProbe", summary) }
            }
            if (BuildConfig.PLATFORM_MODE == "pleos") {
                gateCover.visibility = if (displayAllowed) View.GONE else View.VISIBLE
                gateText.setText(when (event.optString("reason")) {
                    "gear_not_park", "vehicle_moving", "ux_restricted" -> R.string.parking_required
                    "vehicle_state_stale", "vehicle_state_unknown", "ux_restrictions_unknown", "invalid_speed" -> R.string.parking_state_checking
                    else -> R.string.vehicle_connection_required
                })
            }
        }
        if (pageReady) sendToWeb(event)
    }

    private fun sendToWeb(event: JSONObject) {
        if (!trustedPage || !foreground) return
        if (BuildConfig.DEBUG && event.optString("type") == "arrival") {
            Log.i("MajoongProbe", "web_arrival_dispatched seq=${event.optLong("arrivalSeq", 0)} foreground=$foreground")
        }
        // JSONObject serializes untrusted address/name strings; never interpolate them as source.
        val payload = if (event.optString("type") == "arrival") {
            JSONObject(event.toString()).put("popupHandled", event.optLong("arrivalSeq", 0) == handledPopupArrivalSeq)
        } else event
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('pleos:event',{detail:${payload}}));", null)
    }

    inner class Bridge {
        @JavascriptInterface fun getState(): String = synchronized(stateLock) {
            if (!trustedPage) return@synchronized "{}"
            JSONObject().put("platform", BuildConfig.PLATFORM_MODE).put("offline", offline).apply {
                put("arrivalPopupHandledSeq", handledPopupArrivalSeq)
                latest.forEach { (key, value) -> if (key != "arrival") put(key, value) }
                arrivalSession.current?.let { put("arrival", it) }
            }.toString()
        }
        @JavascriptInterface fun startMonitoring() {
            if (trustedPage) runOnUiThread { if (trustedPage) this@MainActivity.startMonitoring() }
        }
        @JavascriptInterface fun stopMonitoring() {
            if (trustedPage) runOnUiThread { if (trustedPage) { monitoringRequested = false; adapter.stop() } }
        }
        @JavascriptInterface fun markArrivalPopupHandled(sequence: Long) {
            if (!trustedPage || sequence <= 0) return
            synchronized(stateLock) {
                if (!closed && trustedPage && arrivalSession.current?.optLong("arrivalSeq", 0) == sequence) {
                    handledPopupArrivalSeq = sequence
                    // Receipt reaches the service before onPause can clear this arrival and unbind.
                    adapter.markArrivalPopupHandled(sequence)
                }
            }
        }
        @JavascriptInterface fun reportUiState(json: String) {
            if (!trustedPage || json.length > 2048) return
            val report = runCatching { JSONObject(json) }.getOrNull() ?: return
            val phase = report.optString("phase")
            val mode = report.optString("dataMode")
            if (phase !in setOf("ready", "driving", "arrived", "parked") || mode !in setOf("demo", "live")) return
            // Reconstruct an allowlisted summary. Never log submitted JSON, photos, names or contacts.
            val safe = JSONObject().put("ready", report.optBoolean("ready", false))
                .put("phase", phase).put("caseCount", report.optInt("caseCount", 0).coerceIn(0, 50))
                .put("detailOpen", report.optBoolean("detailOpen", false)).put("dataMode", mode)
                .put("arrivalPopupOpen", report.optBoolean("arrivalPopupOpen", false))
                .put("arrivalPhotoLoaded", report.optBoolean("arrivalPhotoLoaded", false))
                .put("offline", report.optBoolean("offline", false))
                .put("loading", report.optBoolean("loading", false)).put("hasError", report.optBoolean("hasError", false))
                .put("photoLoadedCount", report.optInt("photoLoadedCount", 0).coerceIn(0, 50))
            runOnUiThread {
                if (closed || !trustedPage) return@runOnUiThread
                safe.put("foreground", foreground).put("gateCoverVisible", gateCover.visibility == View.VISIBLE)
                arrivalProof.ui(arrivalSession.current?.optLong("arrivalSeq", 0) ?: 0,
                    foreground && web.isShown && window.decorView.windowVisibility == View.VISIBLE,
                    gateCover.visibility != View.VISIBLE, displayAllowed && adapter.isDisplayAllowed(),
                    mode == "live" && !offline, report.optBoolean("arrivalPhotoLoaded", false),
                    report.optBoolean("arrivalPopupOpen", false))
                if (!BuildConfig.DEBUG) return@runOnUiThread
                val summary = safe.toString()
                if (lastUiReport != summary) { lastUiReport = summary; Log.i("MajoongProbe", "ui_state $summary") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        arrivalProof.activity(arrivalSession.current?.optLong("arrivalSeq", 0) ?: 0,
            resumed = true, visible = window.decorView.isShown, multiWindow = isInMultiWindowMode, stopped = false)
        if (::web.isInitialized) {
            web.onResume()
            adapter.setUiVisible(true)
            receive(nativeEvent("lifecycle", "phase" to "resumed", "backgroundGuaranteed" to false))
            adapter.refresh()
        }
    }
    override fun onPause() {
        arrivalProof.activity(arrivalSession.current?.optLong("arrivalSeq", 0) ?: 0,
            resumed = false, visible = window.decorView.isShown, multiWindow = isInMultiWindowMode, stopped = false)
        val keepVisible = VisiblePanePolicy.keepOnPause(foreground, isInMultiWindowMode,
            window.decorView.isShown && window.decorView.windowVisibility == View.VISIBLE,
            isFinishing || isDestroyed)
        if (keepVisible) {
            receive(nativeEvent("lifecycle", "phase" to "visible-paused", "backgroundGuaranteed" to false))
        } else deactivateUi()
        super.onPause()
    }

    private fun deactivateUi() {
        if (::web.isInitialized && foreground) {
            receive(nativeEvent("lifecycle", "phase" to "background", "backgroundGuaranteed" to false))
            synchronized(stateLock) {
                arrivalSession.clear()
                latest.remove("arrival")
                sendToWeb(nativeEvent("session-reset", "reason" to "ui_background",
                    "arrivalPopupHandledSeq" to handledPopupArrivalSeq))
            }
            foreground = false
            adapter.setUiVisible(false)
            // Returning to the screen requires a fresh service snapshot before photos are uncovered.
            receive(nativeEvent("vehicle", "displayAllowed" to false, "gear" to "UNKNOWN",
                "speedMetersPerSecond" to null, "reason" to "ui_background", "simulated" to false))
            web.onPause()
        }
    }
    override fun onStop() {
        arrivalProof.activity(arrivalSession.current?.optLong("arrivalSeq", 0) ?: 0,
            resumed = false, visible = false, multiWindow = isInMultiWindowMode, stopped = true)
        // onStop is the definitive hidden boundary, including a previously visible paused pane.
        deactivateUi()
        super.onStop()
    }
    override fun onDestroy() {
        closed = true
        adapter.close()
        trustedPage = false
        web.removeJavascriptInterface("PleosNative")
        web.destroy()
        super.onDestroy()
    }
    private fun blocked(code: Int = 403, reason: String = "Forbidden") =
        WebResourceResponse("text/plain", "UTF-8", code, reason, emptyMap(), ByteArrayInputStream(byteArrayOf()))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
