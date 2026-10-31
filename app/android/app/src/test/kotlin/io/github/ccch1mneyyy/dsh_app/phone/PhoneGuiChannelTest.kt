package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The GUI channel's two closed doors, and the number that says whether it worked.
 *
 * A canvas or blanked page has no elements, so a point tap is the only way to act on it. For a
 * model that cannot see pictures both ways in are then shut: no tree to read, no screenshot it can
 * read. The gate must stop there instead of letting the model guess coordinates until the budget
 * runs out — and it must stop *above* the waivers, or 本任务免确认 turns the stop off.
 */
class SafetyGateNoVisionTest {
    private fun target(label: String = "打开") =
        TargetInfo("s1", 1, "按钮", label, "example.app", "Example", false, false, false)

    private fun gate(canSee: Boolean, mode: ConfirmMode = ConfirmMode.ALWAYS) =
        SafetyGate({ PhonePolicies(confirmMode = mode, canSeeImages = canSee) }, PhoneTasks(), { null })

    @Test fun aBlindModelIsStoppedOnAPageOnlyThePictureCanOpen() {
        val refuse = gate(canSee = false).checkWrite(WriteKind.TAP_XY, null, "example.app", null, needsVision = true)
        assertEquals("no-vision", (refuse as SafetyGate.Verdict.Refuse).rule)
    }

    @Test fun theStopSitsAboveEveryWaiver() {
        // ALWAYS waives all soft confirmations, and 本任务免确认 waives them too: neither may open
        // this door, because the problem is not a confirmation the person already gave.
        for (mode in listOf(ConfirmMode.ASK, ConfirmMode.ALWAYS)) {
            val tasks = PhoneTasks()
            tasks.waive()
            val gate = SafetyGate({ PhonePolicies(confirmMode = mode, canSeeImages = false) }, tasks, { null })
            val refuse = gate.checkWrite(WriteKind.TAP_XY, null, "example.app", null, needsVision = true)
            assertEquals("no-vision", (refuse as SafetyGate.Verdict.Refuse).rule)
        }
    }

    @Test fun aTreePageIsStillWorkedWithoutPictures() {
        // No elements to read is what makes the page vision-only; a normal page is not that.
        val allow = gate(canSee = false).checkWrite(WriteKind.TAP_XY, null, "example.app", null, needsVision = false)
        assertTrue(allow is SafetyGate.Verdict.Allow)
    }

    @Test fun aModelThatSeesPicturesIsNotStopped() {
        val allow = gate(canSee = true).checkWrite(WriteKind.TAP_XY, null, "example.app", null, needsVision = true)
        assertTrue(allow is SafetyGate.Verdict.Allow)
    }

    @Test fun theFlagIsStoredWithTheOtherPolicies() {
        // 模型能看截图 is a setting the person makes for the model they chose, so it is stored with
        // the rest of the policies instead of being re-sent by the app on every connect.
        assertFalse(PhonePolicies.fromMap(PhonePolicies(canSeeImages = false).toMap(), PhonePolicies()).canSeeImages)
        // A single-key update (what the app sends when a model refuses a picture) keeps the rest.
        val base = PhonePolicies(confirmMode = ConfirmMode.TASK, canSeeImages = false)
        val patched = PhonePolicies.fromMap(mapOf("canSeeImages" to true), base)
        assertTrue(patched.canSeeImages)
        assertEquals(ConfirmMode.TASK, patched.confirmMode)
        // A key that is not mentioned keeps whatever the base had, in both directions: the app's
        // other single-key updates must not silently re-open the door.
        assertFalse(PhonePolicies.fromMap(emptyMap<String, Any?>(), base).canSeeImages)
        assertFalse(PhonePolicies.fromMap(mapOf("confirmMode" to "ask"), base).canSeeImages)
        assertTrue(PhonePolicies.fromMap(emptyMap<String, Any?>(), PhonePolicies()).canSeeImages)
    }

    @Test fun theStopReadsBackFromTheLog() {
        assertEquals(HandoffCause.NO_VISION, HandoffCause.ofDecision("refused:no-vision"))
        assertEquals("no-vision", HandoffCause.NO_VISION.rule)
    }
}

/**
 * The action log's change column: the pixel diff on a canvas-like page, the element-fingerprint
 * distance on a tree page. Without it the GUI channel's one real signal is computed for the model
 * and then thrown away, so nothing can be reviewed afterwards.
 */
class ActionLogChangeTest {
    @Test fun aMeasuredChangeIsKeptAndAnUnmeasuredOneStaysNull() {
        var now = 1_000L
        val log = ActionLog(clock = { now })
        log.add("tap", "example.app", "已点击「打开」", "ok", 0.31f)
        log.add("key", "example.app", "返回", "ok")
        val rows = log.toMaps(10)
        assertEquals(2, rows.size)
        // Newest first, so the unmeasured key press leads and the measured tap follows.
        assertNull(rows[0]["change"])
        assertEquals(0.31f, rows[1]["change"] as Float, 0.0001f)
        assertEquals(1_000L, rows[0]["time"])
    }

    @Test fun anEntryDefaultsToNoMeasurement() {
        assertNull(ActionLog.Entry(1L, "screen", null, "读屏", "ok").change)
    }
}

/**
 * The status line goes quiet where an outside GUI scheme aims at the screen itself.
 *
 * The line is drawn over the app in front. 微信 is driven by such a scheme, which reads its own
 * picture of the screen: a line showing up mid-flow shifts every target that picture is matched
 * against. Every other app keeps the line, and so does a screen nobody can name.
 */
class PhoneQuietStatusTest {
    private val tasks = PhoneTasks()
    private val statuses = mutableListOf<String>()
    private var pkg: String? = "example.app"
    private val driver = object : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        override fun availability() = Availability(true)
        override fun foregroundPackage() = pkg
        override fun observe(detail: Boolean, image: Boolean, region: Box?) =
            ScreenSnapshot("s1", "Example", "Example", pkg)
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) =
            error("unexpected collect")
        override fun target(screenId: String, elementId: Int) = null
        override fun perform(action: PhoneAction) = error("unexpected action")
        override fun open(packageName: String) = error("unexpected open")
    }
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
            override fun handoff(reason: String, timeoutMs: Long) = HandoffResult.CONTINUE
            override fun status(text: String?) { if (text != null) statuses += text }
        }
        override fun driver(screen: PhoneScreen) = driver
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen): String? = null
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("Example", "example.app"))
            override fun isInstalled(pkg: String) = true
            override fun launch(app: AppEntry) = error("unexpected launch")
            override fun urlTarget(url: String) = error("unexpected URL")
            override fun openUrl(url: String, target: UrlTarget) = error("unexpected URL open")
            override fun intent(spec: IntentSpec, shareTo: AppEntry?) = error("unexpected intent")
            override fun notify(title: String, text: String) = error("unexpected notification")
            override fun clipboardSet(text: String) = error("unexpected clipboard")
            override fun clipboardGet() = error("unexpected clipboard")
        }
    }, tasks, ActionLog())

    @After fun close() = core.worker.shutdown()

    private fun readScreen() = core.call(PhoneToolCatalog.SCREEN, mapOf("screen" to "s1"))

    @Test fun theLineGoesQuietInWeChat() {
        pkg = "com.tencent.mm"
        readScreen()
        assertTrue("the status line reached a screen a GUI scheme aims at", statuses.isEmpty())
    }

    @Test fun everyOtherAppStillGetsTheLine() {
        pkg = "com.taobao.taobao"
        readScreen()
        assertEquals(listOf("DSH 正在看屏幕"), statuses)
    }

    @Test fun aScreenNobodyCanNameStaysLoud() {
        pkg = null
        readScreen()
        assertEquals(listOf("DSH 正在看屏幕"), statuses)
    }
}
