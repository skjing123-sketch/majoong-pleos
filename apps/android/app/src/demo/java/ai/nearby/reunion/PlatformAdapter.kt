package ai.nearby.reunion

import android.content.Context

/** Portable UI demonstration. This flavor never claims to receive vehicle data. */
class PlatformAdapter(context: Context, private val emit: EventSink) : NativePlatform {
    override fun start() {
        emit(nativeEvent("status", "platform" to "demo", "connected" to false,
            "monitoring" to false, "message" to "일반 Android 체험 · 차량 SDK 미연결"))
        refresh()
    }
    override fun stop() = refresh()
    override fun refresh() {
        emit(nativeEvent("vehicle", "source" to "demo", "displayAllowed" to false,
            "reason" to "demo_controls_only", "simulated" to true))
    }
}
