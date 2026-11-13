package ai.nearby.reunion

/** A failed live page must never silently become a fictional-data session. */
internal object LocalWebPages {
    fun offline(platform: String): String =
        "https://appassets.androidplatform.net/?platform=android&offline=1" +
            if (platform == "demo") "" else "&dataMode=live"
}
