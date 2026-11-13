package ai.nearby.reunion

/** Focus loss is not hiding in split-screen. Unknown/hidden/finishing windows fail closed. */
internal object VisiblePanePolicy {
    fun keepOnPause(active: Boolean, multiWindow: Boolean, visible: Boolean, finishing: Boolean): Boolean =
        active && multiWindow && visible && !finishing
}
