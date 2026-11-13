package ai.nearby.reunion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisiblePanePolicyTest {
    @Test fun touchingNavigationKeepsTheVisibleStartedPaneConnected() {
        assertTrue(VisiblePanePolicy.keepOnPause(true, true, true, false))
    }
    @Test fun fullScreenBackgroundStillHidesPhotos() {
        assertFalse(VisiblePanePolicy.keepOnPause(true, false, true, false))
    }
    @Test fun hiddenSplitPaneCannotKeepPhotosVisible() {
        assertFalse(VisiblePanePolicy.keepOnPause(true, true, false, false))
    }
    @Test fun focusLossCannotActivateAnInactiveOrFinishingPane() {
        assertFalse(VisiblePanePolicy.keepOnPause(false, true, true, false))
        assertFalse(VisiblePanePolicy.keepOnPause(true, true, true, true))
    }
}
