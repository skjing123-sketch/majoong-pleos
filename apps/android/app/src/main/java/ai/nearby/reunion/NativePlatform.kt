package ai.nearby.reunion

import org.json.JSONObject

interface NativePlatform {
    fun start()
    fun stop()
    fun refresh()
    fun setUiVisible(visible: Boolean) {}
    // May run on the WebView bridge thread; record synchronously without touching Android views.
    fun markArrivalPopupHandled(sequence: Long) {}
    // Read diagnostics or request the fixed SDK sample route in debug builds only.
    fun debugProbe(command: String) {}
    fun isDisplayAllowed(): Boolean = false
    fun close() = stop()
}

typealias EventSink = (JSONObject) -> Unit

fun nativeEvent(type: String, vararg values: Pair<String, Any?>): JSONObject =
    JSONObject().put("type", type).put("timestamp", System.currentTimeMillis()).apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
