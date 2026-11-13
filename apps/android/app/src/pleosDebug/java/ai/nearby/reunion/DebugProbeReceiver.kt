package ai.nearby.reunion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only, DUMP-protected shell entry point. Never launches an Activity or starts a service. */
class DebugProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) {
            setResult(-1, "invalid_debug_request", null)
            Log.i("MajoongProbe", "debug_probe_rejected reason=invalid_debug_request")
            return
        }
        val command = intent.getStringExtra("majoongDebugProbe")
        try {
            val result = MonitoringService.dispatchRunningDebugProbe(command)
            setResult(result.code, result.label, null)
            if (result == RunningProbeCommands.Result.ACCEPTED) {
                // The whitelist excludes external coordinates, callbacks and arbitrary payloads.
                Log.i("MajoongProbe", "debug_probe_dispatched command=$command")
            } else {
                Log.i("MajoongProbe", "debug_probe_rejected reason=${result.label}")
            }
        } catch (error: Exception) {
            setResult(-4, "dispatch_failed", null)
            Log.i("MajoongProbe", "debug_probe_rejected reason=dispatch_failed error=${error.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION = "ai.nearby.reunion.debug.PROBE"
    }
}
