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

    /** How often the driver was told to drop the screen ids the model still holds. */
    private var forgotten = 0

    /** False: the ROM refused to bring the task's app back, so it stays on the launcher. */
    private var canBringBack = true
    private val launches = mutableListOf<String>()
    private val opens = mutableListOf<String>()
    private val keys = mutableListOf<PhoneAction.GlobalKey>()
    private var fullscreen = true
    private var policies = PhonePolicies()
    private var secure = false
    private val fullscreens = mutableListOf<String>()

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
        override fun perform(action: PhoneAction): ActionResult = when (action) {
            is PhoneAction.Key -> {
                keys += action.key
                ActionResult(true, "按了${action.key}")
            }
            // A tap, a swipe or a coordinate tap after the app came back: nothing to do but say it
            // landed, because the point of these tests is which calls are refused.
            is PhoneAction.TapXY, is PhoneAction.Tap, is PhoneAction.Scroll -> ActionResult(true, "做到了", null)
            else -> error("unexpected action")
        }
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
        override fun forceFullscreen(packageName: String): Boolean {
            fullscreens += packageName
            if (!canBringBack) return false
            // The real call starts the task again, so the next read is of that app, not of DSH's own UI.
            pkg = packageName
            appLabel = if (packageName == "com.tencent.mm") "微信" else packageName
            return true
        }
        override fun ownPackage() = "io.github.ccch1mneyyy.dsh_app"
        override fun forgetScreens() { forgotten++ }
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

    @Test fun anElementIdOnAPicturePageSaysUseThePicture() {
        // Measured `tool tap error decision=stale at=点 s4#4` on a BLANKED 微信 page: the generic
        // "the screen expired" sentence sent the model hunting for a new id instead of the picture
        // it already had, and that round trip is the most expensive thing in a canvas task.
        pkg = "com.tencent.mm"
        appLabel = "微信"
        kind = ScreenKind.BLANKED
        readMain()
        val out = core.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 4))
        assertTrue(out.isError)
        assertTrue("a picture page must not be described as an expired screen: ${out.text}", out.text.contains("画面页"))
        assertTrue(out.text, out.text.contains("tap_xy"))
    }

    @Test fun anElementIdOnATreePageStillReadsAsStale() {
        pkg = "example.app"
        appLabel = "Example"
        kind = ScreenKind.TREE
        readMain()
        val out = core.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 4))
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("过期"))
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

    @Test fun aReadBringsTheTaskBackWhenThisAppItselfIsInFront() {
        // 微信 came to the front, then the person opened DSH Go: a plain read would describe DSH's
        // own UI (and is refused as the app's own screen), costing an `open`, several seconds and
        // the page the task had. Asking for the task's fullscreen again also brings it forward.
        pkg = "com.tencent.mm"
        appLabel = "微信"
        readMain()
        fullscreens.clear()
        pkg = "io.github.ccch1mneyyy.dsh_app"
        appLabel = "DSH Go"
        val out = core.call(PhoneToolCatalog.SCREEN, emptyMap())
        assertEquals("the task was not brought back: ${out.text}", listOf("com.tencent.mm"), fullscreens)
        assertFalse(out.text, out.isError)
    }

    @Test fun anOrdinaryAppDoesNotGetBroughtBack() {
        // example.app is in no scheme and drew nothing of its own: nothing is latched, so there is
        // no task to bring back — the read stays the refusal it was.
        pkg = "example.app"
        appLabel = "Example"
        readMain()
        fullscreens.clear()
        pkg = "io.github.ccch1mneyyy.dsh_app"
        readMain()
        assertEquals("a task that is not latched has nothing to bring back", emptyList<String>(), fullscreens)
    }

    @Test fun theHomeKeyIsIgnoredInsideOneAppWithoutLookingLikeAFailure() {
        // Going home is what cost the person the task: this ROM re-opens the running app in its
        // small window, and a swipe on the home screen lands on 负一屏 — the game never came back
        // to the same picture. So home does nothing inside a latched task; it is reported as
        // ignored rather than as an error, because an error costs a whole model turn to re-plan.
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        val out = core.call(PhoneToolCatalog.KEY, mapOf("key" to "home"))
        assertFalse("home must not be pressed inside a latched task: ${out.text}", out.isError)
        assertTrue(out.text, out.text.contains("当没按"))
        assertTrue("home was pressed on the phone: ${keys}", keys.isEmpty())
        assertEquals("the key was delivered anyway", emptyList<PhoneAction.GlobalKey>(), keys)
    }

    @Test fun theHomeKeyStillWorksForAnOrdinaryApp() {
        pkg = "example.app"
        appLabel = "Example"
        kind = ScreenKind.TREE
        readMain()
        val out = core.call(PhoneToolCatalog.KEY, mapOf("key" to "home"))
        assertFalse("home was refused for a task that is not latched on one app: ${out.text}", out.isError)
        assertEquals(listOf(PhoneAction.GlobalKey.HOME), keys)
    }

    @Test fun aTapOnTheHomeScreenBringsTheAppBackAndGoesOn() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        // The person (or a stray key) put the launcher in front: the task is still the game's, and
        // an icon tap would re-open it into the small window.
        home = setOf("com.android.launcher3")
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        kind = ScreenKind.TREE
        val out = core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to 500, "y" to 900))
        // Refusing made a background task do nothing at all after the person left the phone on the
        // launcher: the app is brought back and the tap goes on instead.
        assertEquals(listOf("com.game.example"), fullscreens)
        assertFalse("the tap was dropped after the app came back: ${out.text}", out.isError)
    }

    @Test fun aTapOnTheHomeScreenIsRefusedWhenTheAppCannotComeBack() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        home = setOf("com.android.launcher3")
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        kind = ScreenKind.TREE
        canBringBack = false
        val out = core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to 500, "y" to 900))
        assertTrue("a tap was delivered on the home screen: ${out.text}", out.isError)
        assertTrue(out.text, out.text.contains("桌面上"))
    }

    @Test fun theDesktopIsNotRefusedBeforeTheTaskHasGonePureGui() {
        // The task's first call may well arrive while the phone sits on the desktop (the person left
        // it there, or a start-up launch put it back). Nothing has been latched yet, so there is no
        // app to protect: refusing here threw away the first tap of a whole task (log 03:20:49
        // `tap error decision=refused:launcher task=4`).
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.TREE
        readMain()
        home = setOf("com.android.launcher3")
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        val out = core.call(PhoneToolCatalog.SCROLL, mapOf("screen" to "s1", "direction" to "down"))
        assertFalse("the desktop was refused before anything was latched: ${out.text}", out.text.contains("桌面上"))
    }

    @Test fun aLatchFromAnEarlierTaskDoesNotRefuseThisOne() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        assertTrue(tasks.finish())
        home = setOf("com.android.launcher3")
        pkg = "com.android.launcher3"
        appLabel = "桌面"
        val out = core.call(PhoneToolCatalog.SCROLL, mapOf("screen" to "s1", "direction" to "left"))
        assertFalse("an old task's latch refused the new task: ${out.text}", out.text.contains("桌面上"))
    }

    @Test fun aLauncherSwipeBringsTheAppBackInsteadOfSwipingTheDesktop() {
        pkg = "com.game.example"
        appLabel = "Game"
        kind = ScreenKind.CANVAS
        readMain()
        home = setOf("com.stepos.ailauncher")
        pkg = "com.stepos.ailauncher"
        appLabel = "桌面"
        val out = core.call(PhoneToolCatalog.SCROLL, mapOf("screen" to "s1", "direction" to "left"))
        // 负一屏 is only reachable while the launcher is in front: the app comes back first, then the
        // scroll happens inside it.
        assertEquals(listOf("com.game.example"), fullscreens)
        assertFalse("the scroll was dropped after the app came back: ${out.text}", out.isError)
    }

    @Test fun aNewTaskForgetsTheScreenIdsOfTheOldOne() {
        pkg = "com.tencent.mm"
        appLabel = "微信"
        readMain()
        assertEquals(1, forgotten)
        // A read in the middle of a task must not expire the id the model was just handed.
        readMain()
        assertEquals(1, forgotten)
        // Next task (the person pressed 结束任务 and asked something else): the old s-id is worthless.
        tasks.finish()
        pkg = "com.game.example"
        appLabel = "Game"
        readMain()
        assertEquals(2, forgotten)
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
