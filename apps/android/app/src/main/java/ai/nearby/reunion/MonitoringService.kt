package ai.nearby.reunion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import org.json.JSONObject

/** Owns a user-started trip session while the Activity is hidden by the vehicle's navigation UI. */
class MonitoringService : Service() {
    private lateinit var adapter: NativePlatform
    private val listeners = linkedSetOf<EventSink>()
    private val latest = linkedMapOf<String, JSONObject>()
    private val arrivalSession = ArrivalSnapshotStore<JSONObject>()
    private val arrivalNotice = ArrivalNoticePolicy()
    private val acknowledgement = ArrivalAcknowledgement()
    private val main = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var uiVisible = false
    private var arrivalToast: Toast? = null
    private var notificationShowsArrival = false
    private var handledArrivalSeq = 0L
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun subscribe(listener: EventSink) {
            // Re-evaluate freshness before replaying a previously open parking gate.
            if (monitoring) adapter.refresh()
            expireArrival(preserveVisibleSession = false)
            applyAcknowledgement()
            listeners.add(listener)
            listener(snapshot())
        }
        fun unsubscribe(listener: EventSink) { listeners.remove(listener) }
        fun refresh() = adapter.refresh()
        fun debugProbe(command: String) = adapter.debugProbe(command)
        fun isDisplayAllowed(): Boolean = monitoring && adapter.isDisplayAllowed()
        fun stopMonitoring() = this@MonitoringService.stopMonitoring()
        fun markArrivalPopupHandled(sequence: Long) {
            // This local Binder method can run on the WebView bridge thread. Record before posting UI work.
            if (acknowledgement.markHandled(sequence)) main.post { applyAcknowledgement() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        adapter = PlatformAdapter(applicationContext, ::receive)
        receive(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE,
            "connected" to false, "monitoring" to false, "message" to "stopped",
            "backgroundGuaranteed" to false))
    }

    override fun onBind(intent: Intent): IBinder {
        uiVisible = true
        cancelToast()
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        uiVisible = false
        updateArrivalNotice()
        return true
    }

    override fun onRebind(intent: Intent) {
        uiVisible = true
        cancelToast()
        super.onRebind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                        getString(R.string.monitoring_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        description = getString(R.string.monitoring_channel_description)
                        setShowBadge(false)
                    })
                    if (!manager.areNotificationsEnabled() ||
                        manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                        stopMonitoring()
                        receive(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE,
                            "monitoring" to false, "connected" to false, "backgroundGuaranteed" to false,
                            "message" to "monitoring_notification_unavailable"))
                        return START_NOT_STICKY
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification(notificationShowsArrival), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification(notificationShowsArrival))
                    }
                    monitoring = true
                    adapter.start()
                    debugCommands.attach(this) { command -> adapter.debugProbe(command) }
                    probe("monitoring_service_started")
                } catch (error: Exception) {
                    stopMonitoring()
                    receive(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE,
                        "monitoring" to false, "connected" to false, "backgroundGuaranteed" to false,
                        "message" to "monitoring_service_failed:${error.javaClass.simpleName}"))
                }
            }
            ACTION_STOP -> stopMonitoring()
            else -> stopSelf(startId)
        }
        // A killed process has lost its trip state. Never silently restart and claim continuous coverage.
        return START_NOT_STICKY
    }

    private fun notification(arrived: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_majoong)
            .setContentTitle(getString(if (arrived) R.string.monitoring_arrived_title else R.string.monitoring_title))
            .setContentText(getString(if (arrived) R.string.monitoring_arrived_description else R.string.monitoring_description))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, getString(R.string.monitoring_stop), stop).build())
            .build()
    }

    private fun receive(event: JSONObject) {
        val type = event.optString("type")
        if (type != "arrival") expireArrival()
        if (type == "arrival") {
            val now = SystemClock.elapsedRealtime()
            arrivalSession.onArrival(event, adapter.isDisplayAllowed(), now)
            acknowledgement.onArrival(event.optLong("arrivalSeq"))
            arrivalNotice.onArrival(event.optLong("arrivalSeq"), now)
            event.put("popupHandled", event.optLong("arrivalSeq") == handledArrivalSeq)
        } else if (type == "trip-invalidated") {
            arrivalSession.clear()
            arrivalNotice.clear()
            acknowledgement.clear()
            handledArrivalSeq = 0L
            latest.remove("arrival")
            latest.remove("destination")
            cancelToast()
        } else if (type == "vehicle") {
            if (arrivalSession.onVehicle(event.optBoolean("displayAllowed"), event.optString("gear"),
                    (event.opt("speedMetersPerSecond") as? Number)?.toDouble())) {
                latest.remove("arrival")
                arrivalNotice.clear()
                acknowledgement.clear()
            }
        } else if (type == "status" && event.has("monitoring") && !event.optBoolean("monitoring")) {
            arrivalSession.clear()
            arrivalNotice.clear()
            acknowledgement.clear()
            latest.remove("arrival")
        }
        // At most one snapshot per event type, not an unbounded queue of hidden WebView updates.
        if (type == "status") event.put("serviceRunning", monitoring)
        // Invalidation is an edge, not a snapshot to replay after a newer arrival.
        if (type != "trip-invalidated") latest[type] = JSONObject(event.toString())
        listeners.toList().forEach { it(JSONObject(event.toString())) }
        updateArrivalNotice()
    }

    private fun updateArrivalNotice() {
        if (!monitoring) { cancelToast(); return }
        expireArrival()
        applyAcknowledgement()
        val now = SystemClock.elapsedRealtime()
        val parked = adapter.isDisplayAllowed()
        val arrived = parked && arrivalNotice.isFresh(now)
        if (!arrived || uiVisible) cancelToast()
        if (notificationShowsArrival != arrived) {
            notificationShowsArrival = arrived
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(arrived)) }
        }
        val sequence = arrivalNotice.takeToast(now, parked, uiVisible) ?: return
        val toast = Toast.makeText(applicationContext, R.string.monitoring_arrival_toast, Toast.LENGTH_SHORT)
        arrivalToast = toast
        if (Build.VERSION.SDK_INT >= 30) toast.addCallback(object : Toast.Callback() {
            override fun onToastShown() { probe("arrival_toast_shown seq=$sequence") }
            override fun onToastHidden() {
                if (arrivalToast === toast) arrivalToast = null
                probe("arrival_toast_hidden seq=$sequence")
            }
        })
        probe("arrival_toast_requested seq=$sequence")
        // Standard text only: no overlay permission, custom view, fixed gravity or navigation HUN.
        runCatching { toast.show() }.onFailure { probe("arrival_toast_failed error=${it.javaClass.simpleName}") }
    }

    private fun snapshot(): JSONObject {
        val events = JSONObject()
        latest.forEach { (type, value) -> if (type != "arrival") events.put(type, JSONObject(value.toString())) }
        arrivalSession.current?.let { events.put("arrival", JSONObject(it.toString())) }
        return nativeEvent("snapshot", "events" to events)
    }

    private fun expireArrival(preserveVisibleSession: Boolean = uiVisible && adapter.isDisplayAllowed()) {
        if (!arrivalSession.expire(SystemClock.elapsedRealtime(), preserveVisibleSession)) return
        latest.remove("arrival")
        arrivalNotice.clear()
        acknowledgement.clear()
        handledArrivalSeq = 0L
        cancelToast()
        // Absence in the complete snapshot also removes a visible Activity's cached arrival.
        listeners.toList().forEach { it(snapshot()) }
        probe("arrival_expired")
    }

    private fun cancelToast() {
        arrivalToast?.cancel()
        arrivalToast = null
    }

    private fun applyAcknowledgement() {
        val sequence = acknowledgement.handledSequence()
        if (sequence <= 0 || arrivalSession.current?.optLong("arrivalSeq") != sequence) return
        handledArrivalSeq = sequence
        arrivalNotice.markForegroundHandled(sequence)
        latest["arrival"]?.put("popupHandled", true)
        arrivalSession.current?.put("popupHandled", true)
        cancelToast()
    }

    private fun stopMonitoring() {
        debugCommands.detach(this)
        monitoring = false
        cancelToast()
        arrivalNotice.clear()
        acknowledgement.clear()
        notificationShowsArrival = false
        adapter.stop()
        arrivalSession.clear()
        latest.remove("arrival")
        latest.remove("destination")
        handledArrivalSeq = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        probe("monitoring_service_stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopMonitoring()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        debugCommands.detach(this)
        monitoring = false
        cancelToast()
        arrivalNotice.clear()
        acknowledgement.clear()
        adapter.close()
        listeners.clear()
        latest.clear()
        super.onDestroy()
    }

    private fun probe(message: String) { if (BuildConfig.DEBUG) Log.i("MajoongProbe", message) }

    companion object {
        private val debugCommands = RunningProbeCommands { BuildConfig.DEBUG }

        // Called only by the debug receiver on the main thread; no Activity/service is created.
        internal fun dispatchRunningDebugProbe(command: String?): RunningProbeCommands.Result =
            debugCommands.dispatch(command)

        const val ACTION_START = "ai.nearby.reunion.monitor.START"
        const val ACTION_STOP = "ai.nearby.reunion.monitor.STOP"
        private const val CHANNEL_ID = "trip_monitoring"
        private const val NOTIFICATION_ID = 2101
    }
}
