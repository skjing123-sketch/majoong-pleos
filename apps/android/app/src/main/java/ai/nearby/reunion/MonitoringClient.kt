package ai.nearby.reunion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/** Activity-side view of the started service; closing a screen does not own or stop the trip. */
class MonitoringClient(private val context: Context, private val emit: EventSink) : NativePlatform {
    @Volatile private var service: MonitoringService.LocalBinder? = null
    private var bound = false
    private var visible = false
    private var closed = false
    private var startPending = false
    private var probePending: String? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible || closed) return
            service = binder as MonitoringService.LocalBinder
            service?.subscribe(emit)
            probePending?.let { service?.debugProbe(it) }
            probePending = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            emit(nativeEvent("vehicle", "displayAllowed" to false, "gear" to "UNKNOWN",
                "reason" to "monitoring_service_disconnected", "simulated" to false))
            emit(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE,
                "monitoring" to false, "connected" to false, "backgroundGuaranteed" to false,
                "message" to "monitoring_service_disconnected"))
        }
        override fun onBindingDied(name: ComponentName) {
            unbind()
            if (visible && !closed) bind()
        }
    }

    override fun setUiVisible(visible: Boolean) {
        if (closed) return
        this.visible = visible
        if (visible) {
            bind()
            if (startPending) start()
        } else unbind()
    }

    override fun start() {
        if (closed) return
        startPending = true
        if (!visible) return // Only a visible, user-initiated action may launch the FGS.
        startPending = false
        try {
            context.startForegroundService(Intent(context, MonitoringService::class.java)
                .setAction(MonitoringService.ACTION_START))
            bind()
        } catch (error: Exception) {
            emit(nativeEvent("status", "platform" to BuildConfig.PLATFORM_MODE,
                "monitoring" to false, "connected" to false, "backgroundGuaranteed" to false,
                "message" to "monitoring_start_failed:${error.javaClass.simpleName}"))
        }
    }

    override fun stop() {
        startPending = false
        probePending = null
        val connected = service
        if (connected != null) connected.stopMonitoring()
        else context.stopService(Intent(context, MonitoringService::class.java))
    }
    override fun refresh() { service?.refresh() }
    override fun debugProbe(command: String) {
        val connected = service
        if (connected != null) connected.debugProbe(command) else probePending = command
    }
    override fun markArrivalPopupHandled(sequence: Long) { service?.markArrivalPopupHandled(sequence) }
    override fun isDisplayAllowed(): Boolean = visible && service?.isDisplayAllowed() == true
    override fun close() {
        closed = true
        startPending = false
        probePending = null
        unbind()
    }

    private fun bind() {
        if (bound || closed) return
        bound = context.bindService(Intent(context, MonitoringService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    private fun unbind() {
        service?.unsubscribe(emit)
        service = null
        if (bound) { context.unbindService(connection); bound = false }
    }
}
