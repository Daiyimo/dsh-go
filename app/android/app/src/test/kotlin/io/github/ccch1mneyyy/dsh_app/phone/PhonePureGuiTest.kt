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
    private var scheme = PhoneCore.DEFAULT_GUI_SCHEME
    private var home: Set<String> = emptySet()
    private val launches = mutableListOf<String>()
    private val opens = mutableListOf<String>()
    private var fullscreen = true
    private var policies = PhonePolicies()
    private var secure = false

    private fun driver(screen: PhoneScreen, id: String) = object : PhoneDriver {
        override val screen = screen
        override fun availability() = Availability(true)
        override fun foregroundPackage() = pkg
        override fun screenKind() = kind
        override fun observe(detail: Boolean, image: Boolean, region: Box?) =
            ScreenSnapshot(id = id, text = "Example", appLabel = appLabel, packageName = pkg, secure = secure, kind = kind)
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) =
            error("unexpected collect")
        override fun target(screenId: String, elementId: Int) = null
        override fun perform(action: PhoneAction) = error("unexpected action")
        override fun open(packageName: String): ActionResult {
            opens += packageName
            return ActionResult(true, "打开了 $appLabel", screen = ScreenSnapshot(id = id, text = "Example", appLabel = appLabel, packageName = pkg, kind = kind))
        }
    }

    private val main = driver(PhoneScreen.MAIN, "s1")
    private val back = driver(PhoneScreen.BACKGROUND, "b1")

    private val env = object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = policies
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
        override fun guiScheme() = scheme
        override fun homePackages() = home
        override fun launchFullscreen() = fullscreen
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen): String? = null
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("Example", "example.app"), AppEntry("微信", "com.tencent.mm"))
            override fun isInstalled(pkg: String) = true
            override fun launch(app: AppEntry): SysResult { launches += app.packageName; return SysResult(true, "打开了 ${app.label}") }
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

    @Test fun aLineDrawnOnTheWayToWeChatIsTakenDown() {
        // The task walks to 微信 through the launcher: the line goes up there, and it has to go
        // before the app is on screen — a line inside the picture costs the GUI its targets.
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        kind = ScreenKind.TREE
        readMain()
        assertEquals(listOf<String?>("DSH 正在看屏幕"), statuses)
        pkg = "com.tencent.mm"
        appLabel = "微信"
        readMain()
        assertNull("the line stayed over 微信: $statuses", statuses.lastOrNull())
    }

    @Test fun theHomeScreenDoesNotEndTheTask() {
        // 第二次 was "去桌面打开的微信": pressing home and tapping the icon is not the task moving
        // on to another app, so the line must not come back and the display must stay refused.
        home = setOf("com.android.launcher3")
        pkg = "com.tencent.mm"
        appLabel = "微信"
        readMain()
        statuses.clear()
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        readMain()
        assertTrue("the line came back on the home screen: $statuses", statuses.none { it != null })
        backgroundRequests = 0
        assertTrue(readBack().isError)
        assertEquals(0, backgroundRequests)
    }

    @Test fun anIdleBoundaryKeepsAPictureTask() {
        // The task id moves on when the phone sits idle between two calls. The game is still in
        // front, so the task is still the game's: dropping the latch there re-opened the display.
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        statuses.clear()
        tasks.endTask()
        readMain()
        assertTrue("the line came back after the idle clock expired: $statuses", statuses.none { it != null })
        backgroundRequests = 0
        assertTrue(readBack().isError)
        assertEquals(0, backgroundRequests)
    }

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
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        val out = readMain()
        assertTrue(out.text, out.text.contains("tap_xy"))
        assertTrue("the hint is per read, so a long task cannot drift back", out.text.contains("不要用 background"))
    }

    @Test fun aReadableAppIsNotSentToCoordinates() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        kind = ScreenKind.TREE
        val out = readMain()
        assertFalse("微信 reads as a tree: its writes belong on the element numbers", out.text.contains("tap_xy 点截图上的位置"))
        assertTrue(out.text, out.text.contains("点元素编号"))
    }

    @Test fun aBackScreenIdIsRefusedToo() {
        // The virtual screen can be named by its id (`b3`), not only by background:true.
        pkg = "com.tencent.mm"
        appLabel = "微信"
        val out = core.call(PhoneToolCatalog.TAP, mapOf("screen" to "b1", "id" to 3))
        assertTrue(out.text, out.isError)
        assertTrue(out.text, out.text.contains("纯 GUI"))
        assertEquals("the display was asked for before the refusal", 0, backgroundRequests)
    }

    @Test fun theDisplayIsHandedBackOncePerTask() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        readMain()
        readMain()
        assertEquals("every read tore the display down again", 1, closeCount)
    }

    @Test fun anotherAppStillOpensInFrontWhileLatched() {
        // Pure GUI is about the virtual screen, not about the task: opening a normal app on the
        // person's own screen is exactly what is left.
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        val out = core.call(PhoneToolCatalog.OPEN, mapOf("app" to "example.app"))
        assertFalse("a front-screen launch was refused inside a pure-GUI task: ${out.text}", out.isError)
        assertEquals(listOf("example.app"), opens)
        assertEquals("the system was asked to start it as well", emptyList<String>(), launches)
    }

    @Test fun aSystemPageOnTopOfWeChatDrawsNothing() {
        // A permission sheet or the shade over 微信: the app underneath is still the one being
        // driven, and a line drawn there sits in the middle of the picture the GUI matches on.
        pkg = "com.tencent.mm"
        appLabel = "微信"
        readMain()
        statuses.clear()
        pkg = "com.android.permissioncontroller"
        appLabel = "权限控制器"
        kind = ScreenKind.TREE
        readMain()
        assertTrue("the line came back over a system page on top of 微信: $statuses", statuses.none { it != null })
    }

    @Test fun theFirstReadOfAGameNeverDrawsTheLine() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        // Not "drawn and taken back": the page is known before anything is drawn, so the GUI never
        // sees a line appear mid-flow.
        assertTrue("the line was drawn on a picture page: $statuses", statuses.none { it != null })
    }

    @Test fun aWeChatTaskTakesDownALeftoverDisplayBeforeItLaunches() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        val out = core.call(PhoneToolCatalog.OPEN, mapOf("app" to "com.tencent.mm"))
        assertFalse(out.text, out.isError)
        assertEquals("a virtual screen from an earlier task was left up", 1, closeCount)
        assertEquals(listOf("com.tencent.mm"), opens)
        assertTrue("the start fell back to the plain way", launches.isEmpty())
    }

    @Test fun aBlankedWeChatPageIsStillReadable() {
        // 微信 hides its tree: the page is BLANKED, not a secure window, and the picture is how it
        // is worked. Refusing it as "安全窗口" was what made the GUI scheme unusable.
        pkg = "com.tencent.mm"
        appLabel = "微信"
        kind = ScreenKind.BLANKED
        val out = readMain()
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.contains("纯 GUI"))
    }

    @Test fun aRefusedReadSaysWhy() {
        pkg = "com.icbc"
        appLabel = "ICBC"
        val out = readMain()
        assertTrue(out.isError)
        assertTrue("the reason was swallowed: ${out.text}", out.text.contains("默认不碰名单"))
        assertFalse("a generic line still leads the answer", out.text.contains("没有读取这个屏幕的内容"))
    }

    @Test fun aPicturePageSaysTheModelCannotSeeIt() {
        policies = PhonePolicies(canSeeImages = false)
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        val out = readMain()
        assertTrue(out.text, out.text.contains("模型能看截图"))
        assertTrue(out.text, out.text.contains("handoff"))
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

    @Test fun aListedAppIsPureGuiBeforeAnythingIsRead() {
        // 设置 › 手机操控 › 纯 GUI 应用 wrote this one; nothing has been read yet.
        pkg = "com.example.foo"
        appLabel = "Foo"
        scheme = setOf("com.tencent.mm", "com.example.foo")
        val out = readBack()
        assertTrue(out.text, out.isError)
        assertTrue(out.text, out.text.contains("纯 GUI"))
        assertEquals("the display was asked for before the refusal", 0, backgroundRequests)
    }

    @Test fun anEmptiedListLetsWeChatBackOntoTheVirtualScreen() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        scheme = emptySet()
        val out = readBack()
        assertFalse("every app was removed from the list and 微信 was still refused: ${out.text}", out.isError)
        assertEquals(1, backgroundRequests)
    }
}
