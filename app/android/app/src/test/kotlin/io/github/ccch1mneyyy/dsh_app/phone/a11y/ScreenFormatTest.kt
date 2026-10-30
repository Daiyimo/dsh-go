package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.phone.*
import org.junit.Assert.*
import org.junit.Test

class ScreenFormatTest {
    private fun format(vararg nodes: UiNode): FormattedScreen {
        val bounds = Box(0, 0, 400, 800)
        return ScreenFormat.format(ScreenInput("s1", "Example", "example.app", 400, 800, false,
            listOf(UiWindow(1, WindowKind.APP, "example.app", null, bounds, UiNode(bounds = bounds, children = nodes.toList())))))
    }

    @Test fun passwordContentsNeverAppearAndCodesAreSecret() {
        val screen = format(
            UiNode(className = "android.widget.EditText", text = "never-visible-password", hint = "密码",
                bounds = Box(0, 100, 300, 150), editable = true, password = true),
            UiNode(className = "android.widget.EditText", text = "123456", hint = "验证码",
                bounds = Box(0, 200, 300, 250), editable = true),
        )
        assertFalse(screen.text.contains("never-visible-password"))
        assertFalse(screen.text.contains("123456"))
        assertTrue(screen.elements.any { it.password })
        assertTrue(screen.elements.any { it.secret && !it.password })
    }

    @Test fun warningMarkerAndWriteGateUseTheSameSensitiveSource() {
        val screen = format(UiNode(className = "android.widget.Button", text = "发送",
            bounds = Box(0, 100, 300, 150), clickable = true))
        val element = screen.elements.single()
        assertTrue(screen.text.contains("⚠"))
        val tasks = PhoneTasks().apply { begin(); approve("example.app") }
        val gate = SafetyGate({ PhonePolicies() }, tasks, { null })
        val target = TargetInfo("s1", element.id, element.role, element.label, "example.app", "Example",
            element.password, element.secret, element.editable, element.viewId)
        val verdict = gate.checkWrite(WriteKind.TAP, target, "example.app", "Example") as SafetyGate.Verdict.Confirm
        assertEquals("sensitive:${element.sensitiveWord}", verdict.reason)
    }

    @Test fun screenshotMasksIncludeFieldsOutsideTheTextBudget() {
        val frame = Box(0, 0, 400, 800)
        val passwordBox = Box(0, 500, 300, 550)
        val codeBox = Box(0, 600, 300, 650)
        val root = UiNode(bounds = frame, children = listOf(
            UiNode(bounds = passwordBox, password = true, editable = true),
            UiNode(bounds = codeBox, text = "验证码", showingHint = true, editable = true),
            UiNode(bounds = Box(0, 100, 300, 150), text = "ordinary", editable = true),
        ))
        val windows = listOf(UiWindow(1, WindowKind.APP, "example.app", null, frame, root))
        assertEquals(listOf(passwordBox, codeBox), ScreenShooter.sensitiveBounds(windows))
    }

}

class ScreenFormatKindTest {
    private fun input(
        vararg nodes: UiNode,
        hidden: Boolean = false,
        windows: List<UiWindow>? = null,
    ): ScreenInput {
        val bounds = Box(0, 0, 400, 800)
        val list = windows ?: listOf(
            UiWindow(1, WindowKind.APP, "example.app", null, bounds, UiNode(bounds = bounds, children = nodes.toList())),
        )
        return ScreenInput("s1", "Example", "example.app", 400, 800, false, list, hidden = hidden)
    }

    private fun formatted(vararg nodes: UiNode, hidden: Boolean = false, windows: List<UiWindow>? = null) =
        ScreenFormat.format(input(*nodes, hidden = hidden, windows = windows))

    @Test fun aGameScreenWithOneDeadNodeIsCanvas() {
        // 王者荣耀: the whole tree is one non-clickable "Game view" node behind the window root.
        // The old text-count rule missed this page; the driver must still attach a screenshot.
        val screen = formatted(UiNode(className = "android.view.View", text = "Game view", bounds = Box(0, 0, 400, 800)))
        assertEquals(ScreenKind.CANVAS, screen.kind)
        assertTrue(screen.text.contains(ScreenFormat.CANVAS_NOTE))
        assertFalse(screen.text.contains(ScreenFormat.POOR_TREE_NOTE))
    }

    @Test fun anEmptyTreeIsCanvasAndAnAbsentTreeIsUnknown() {
        assertEquals(ScreenKind.CANVAS, formatted().kind)
        val noWindows = formatted(windows = emptyList())
        assertEquals(ScreenKind.UNKNOWN, noWindows.kind)
        assertFalse(noWindows.kind == ScreenKind.CANVAS)
    }

    @Test fun aTreeWithNothingToDriveIsCanvas() {
        val labelsOnly = formatted(
            UiNode(className = "android.widget.TextView", text = "加载中…", bounds = Box(0, 100, 200, 150)),
            UiNode(className = "android.widget.TextView", text = "请稍候", bounds = Box(0, 200, 200, 250)),
        )
        assertEquals(ScreenKind.CANVAS, labelsOnly.kind)
    }

    @Test fun anOrdinaryPageWithOneButtonIsTree() {
        val page = formatted(
            UiNode(className = "android.widget.Button", text = "发送", bounds = Box(0, 100, 300, 150), clickable = true),
            UiNode(className = "android.widget.TextView", text = "消息", bounds = Box(0, 200, 300, 250)),
        )
        assertEquals(ScreenKind.TREE, page.kind)
        assertFalse(page.text.contains(ScreenFormat.CANVAS_NOTE))
    }

    @Test fun aLongPressOnlyPageStillCountsAsSomethingToDrive() {
        val page = formatted(
            UiNode(className = "android.widget.ImageView", bounds = Box(0, 100, 300, 150), longClickable = true),
            UiNode(className = "android.widget.TextView", text = "长按菜单", bounds = Box(0, 200, 300, 250)),
        )
        assertEquals(ScreenKind.TREE, page.kind)
    }

    @Test fun aWindowWhoseRootIsInvisibleIsBlankedLikeWeChat() {
        // WeChat: one root node with isVisibleToUser=false, so TreeCapture drops it and the
        // window is listed with root=null. The page is there, its contents are not, so it
        // must take the blanked path (auto screenshot + tap_xy) instead of unknown, which
        // used to leave the model with an empty screen and no picture to look at.
        val bounds = Box(0, 0, 400, 800)
        val screen = formatted(windows = listOf(UiWindow(1, WindowKind.APP, "com.tencent.mm", null, bounds, null)))
        assertEquals(ScreenKind.BLANKED, screen.kind)
        assertTrue(screen.text.contains(ScreenFormat.BLANKED_NOTE))
        assertFalse(screen.text.contains(ScreenFormat.POOR_TREE_NOTE))
    }

    @Test fun aHiddenWindowIsBlankedNotCanvas() {
        // Secure window: the tree is withheld, which is a different instruction from "nothing to drive".
        val screen = formatted(windows = listOf(UiWindow(1, WindowKind.APP, "example.app", null, Box(0, 0, 400, 800), null)), hidden = true)
        assertEquals(ScreenKind.BLANKED, screen.kind)
        assertTrue(screen.text.contains(ScreenFormat.BLANKED_NOTE))
    }
}
