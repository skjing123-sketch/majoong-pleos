package ai.nearby.reunion

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebPagesTest {
    @Test fun pleosNetworkFailureKeepsLiveDataModeOnTrustedBundledOrigin() {
        val uri = URI(LocalWebPages.offline("pleos"))
        assertEquals("https", uri.scheme)
        assertEquals("appassets.androidplatform.net", uri.host)
        assertTrue(uri.query.split('&').contains("dataMode=live"))
        assertTrue(uri.query.split('&').contains("offline=1"))
    }
    @Test fun explicitlySeparateDemoAppCanStillShowFictionalOfflineData() {
        assertFalse(URI(LocalWebPages.offline("demo")).query.contains("dataMode="))
    }
    @Test fun unknownPlatformCannotSelectFictionalDataByDefault() {
        assertTrue(URI(LocalWebPages.offline("")).query.contains("dataMode=live"))
    }
}
