package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * Which windows are chrome. The failure this pins down: on a StepX ROM, opening an app from the
 * accessibility service starts it in the multi-window container, and the freeform window gets two
 * decorations - (366,2124,834,2186) and (444,323,756,437) on a 1200x2670 screen - reported as
 * TYPE_APPLICATION windows rooted in SystemUI and stacked *above* the app. The old filter only
 * dropped small TYPE_SYSTEM windows, so the decorations won: the app's own window disappeared from
 * the tree, [TreeCapture.foregroundPackage] answered "com.android.systemui", and the page the GUI
 * fallback was opened for was never seen. A window is chrome by its package and its size, never by
 * the type it reports.
 */
class TreeCaptureChromeTest {
    private val screen = 1200 * 2670.0

    private fun share(w: Int, h: Int) = (w.toLong() * h).toDouble() / screen

    @Test fun aSmallSystemWindowIsChromeWhateverThePhoneLooksLike() {
        // Status bar / navigation bar / floating ball: small system windows.
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_SYSTEM, share(1200, 60), "com.android.systemui"))
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_SYSTEM, 0.02, "com.android.systemui"))
    }

    @Test fun aSmallWindowRootedInSystemUiIsChromeEvenWhenItClaimsToBeAnApp() {
        // StepX's freeform decorations, verbatim bounds from the device.
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, share(468, 62), "com.android.systemui"))
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, share(312, 114), "com.android.systemui"))
        // Any system package gets the same treatment, not just SystemUI.
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, 0.1, "com.android.settings"))
        // A root we may not read: the old code kept such a window and this keeps it, because
        // guessing wrong here hides content instead of decoration.
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, 0.1, null))
    }

    @Test fun theAppUnderneathIsNotChrome() {
        // The WeChat window the decorations were sitting on top of: (120,323,1080,2186).
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, share(960, 1863), "com.tencent.mm"))
        // A small window belonging to a real app is still that app's content (floating bubble,
        // a chat head, a pip video) - the old code kept it and this keeps it.
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_APPLICATION, 0.2, "com.tencent.mm"))
    }

    @Test fun aBigSystemWindowIsContent() {
        // The notification shade pulled all the way down, or a full-screen system dialog.
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_SYSTEM, 0.9, "com.android.systemui"))
        // Half-screen split: the system side owns half the screen, so it is not decoration.
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_SYSTEM, 0.5, "com.android.systemui"))
    }

    @Test fun theInputMethodAndTheSplitDividerAreNotTheSizeFilter() {
        // A keyboard is not a small window of a system package, so this returns false; skipping
        // the input method is TreeCapture's own job and must not depend on the size of the keys.
        assertFalse(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 0.4, "com.baidu.input"))
        // The split-screen divider is a thin system window: chrome, and never content.
        assertTrue(TreeCapture.isChrome(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, 0.05, "com.android.systemui"))
    }
}
