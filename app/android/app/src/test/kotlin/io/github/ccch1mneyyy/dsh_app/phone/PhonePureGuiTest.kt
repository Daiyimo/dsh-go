package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 纯 GUI: one fullscreen app, no virtual screen, nothing drawn over it.
 *
 * 微信 is driven by an outside GUI scheme that matches its own picture of the screen, and a game only
 * exists as a picture. Both are therefore worked on the main screen only: asking for the back screen
 * is refused instead of quietly bringing a virtual display up, a line the person's GUI would catch in
 * its targets is taken down, and every app outside the scheme keeps both.
 */
class PhonePureGuiTest {
    private val tasks = PhoneTasks()
    private val statuses = mutableListOf<String?>()
    private var closeCount = 0
    private var backgroundRequests = 0
    private var pkg: String? = "example.app"
    private var appLabel: String? = "Example"
    private var kind = ScreenKind.TREE

    private fun driver(screen: PhoneScreen, id: String) = object : PhoneDriver {
        override val screen = screen
        override fun availability() = Availability(true)
        override fun foregroundPackage() = pkg
        override fun screenKind() = kind
        override fun observe(detail: Boolean, image: Boolean, region: Box?) =
            ScreenSnapshot(id = id, text = "Example", appLabel = appLabel, packageName = pkg, kind = kind)
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) =
            error("unexpected collect")
        override fun target(screenId: String, elementId: Int) = null
        override fun perform(action: PhoneAction) = error("unexpected action")
        override fun open(packageName: String) = error("unexpected open")
    }

    private val main = driver(PhoneScreen.MAIN, "s1")
    private val back = driver(PhoneScreen.BACKGROUND, "b1")

    private val env = object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
            override fun handoff(reason: String, timeoutMs: Long) = HandoffResult.CONTINUE
            // Null is kept: taking a line down is observable, and the point of the change.
            override fun status(text: String?) { statuses += text }
        }
        override fun driver(screen: PhoneScreen): PhoneDriver? = when (screen) {
            PhoneScreen.MAIN -> main
            PhoneScreen.BACKGROUND -> { backgroundRequests++; back }
        }
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "后台屏用不了。"
        override fun closeBackground() { closeCount++ }
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen): String? = null
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("Example", "example.app"), AppEntry("微信", "com.tencent.mm"))
            override fun isInstalled(pkg: String) = true
            override fun launch(app: AppEntry) = error("unexpected launch")
            override fun urlTarget(url: String) = error("unexpected URL")
            override fun openUrl(url: String, target: UrlTarget) = error("unexpected URL open")
            override fun intent(spec: IntentSpec, shareTo: AppEntry?) = error("unexpected intent")
            override fun notify(title: String, text: String) = error("unexpected notification")
            override fun clipboardSet(text: String) = error("unexpected clipboard")
            override fun clipboardGet() = error("unexpected clipboard")
        }
    }

    private val core = PhoneCore(env, tasks, ActionLog())

    @After fun close() = core.worker.shutdown()

    private fun readMain() = core.call(PhoneToolCatalog.SCREEN, mapOf("screen" to "s1"))

    private fun readBack() = core.call(PhoneToolCatalog.SCREEN, mapOf("screen" to "s1", "background" to true))

    @Test fun theBackScreenIsRefusedInWeChat() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        val out = readBack()
        assertTrue("the virtual screen was brought up for an app that has to stay fullscreen", out.isError)
        assertTrue(out.text, out.text.contains("纯 GUI"))
        assertEquals("the display was asked for before the refusal", 0, backgroundRequests)
    }

    @Test fun openingInTheBackScreenIsRefusedForWeChat() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        val out = core.call(PhoneToolCatalog.OPEN, mapOf("app" to "com.tencent.mm", "background" to true))
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("纯 GUI"))
        assertEquals(0, backgroundRequests)
    }

    @Test fun aGameTurnsTheTaskPureGuiAndTakesTheLineDown() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        // The line is drawn before the picture is known, so the first read must take it back down.
        assertNull("a line was left on top of the picture the GUI matches its targets in", statuses.lastOrNull())
        assertEquals("the virtual screen was left up under a pure-GUI task", 1, closeCount)
        statuses.clear()
        backgroundRequests = 0
        val out = readBack()
        assertTrue(out.isError)
        assertEquals(0, backgroundRequests)
        assertTrue("the line came back inside a pure-GUI task", statuses.none { it != null })
    }

    @Test fun theHintKeepsTheModelOnThePicture() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        val out = readMain()
        assertTrue(out.text, out.text.contains("tap_xy"))
        assertTrue("the hint is per read, so a long task cannot drift back", out.text.contains("不要用 background"))
    }

    @Test fun anAppOutsideTheSchemeKeepsTheBackScreen() {
        val out = readBack()
        assertFalse(out.text, out.isError)
        assertEquals(1, backgroundRequests)
    }

    @Test fun aNewTaskStartsFromItsOwnPicture() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        assertEquals(1, closeCount)
        // The person is done; the next task starts while the screen cannot even be named.
        tasks.endTask()
        pkg = null
        kind = ScreenKind.TREE
        backgroundRequests = 0
        val out = readBack()
        assertFalse("the new task inherited the last task's picture: ${out.text}", out.isError)
        assertEquals(1, backgroundRequests)
    }

    @Test fun anotherAppTakingTheFrontEndsPureGui() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        statuses.clear()
        // The person swiped back to a normal app: the game latch must not follow them around.
        pkg = "com.taobao.taobao"
        appLabel = "淘宝"
        kind = ScreenKind.TREE
        readMain()
        assertEquals(listOf<String?>("DSH 正在看屏幕"), statuses)
        backgroundRequests = 0
        assertFalse(readBack().isError)
        assertEquals(1, backgroundRequests)
    }
}
