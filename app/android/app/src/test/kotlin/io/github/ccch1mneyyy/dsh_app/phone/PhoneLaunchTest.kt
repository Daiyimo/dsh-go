package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 全屏打开应用 (设置 › 手机操控 › 进阶): `open` asks the accessibility service for the fullscreen
 * windowing mode instead of starting the app from this process, which a ROM may put in a small
 * floating window. Off — or a service that cannot do it — leaves the start to the system, because
 * the person asked for the app, not for a particular way of starting it.
 */
class PhoneLaunchTest {
    private val tasks = PhoneTasks()
    private var fullscreen = true
    private var driverUp = true
    private var driverPresent = true
    private var openOk = true
    private val opens = mutableListOf<String>()
    private val launches = mutableListOf<String>()
    private val fullscreens = mutableListOf<String>()
    private var a11y = A11yState.CONNECTED

    private val main = object : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        override fun availability() = Availability(driverUp)
        override fun foregroundPackage(): String? = null
        override fun observe(detail: Boolean, image: Boolean, region: Box?) =
            ScreenSnapshot(id = "s1", text = "Example", appLabel = "Example", packageName = "example.app")
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) =
            error("unexpected collect")
        override fun target(screenId: String, elementId: Int) = null
        override fun perform(action: PhoneAction) = error("unexpected action")
        override fun open(packageName: String): ActionResult {
            opens += packageName
            return if (openOk) {
                ActionResult(true, "打开了 Example", screen = observe(detail = false, image = false))
            } else {
                ActionResult(false, "没能打开 Example")
            }
        }
    }

    private val env = object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
            override fun handoff(reason: String, timeoutMs: Long) = HandoffResult.CONTINUE
            override fun status(text: String?) {}
        }
        override fun driver(screen: PhoneScreen): PhoneDriver? =
            if (screen == PhoneScreen.MAIN && driverPresent) main else null
        override fun a11yState() = a11y
        override fun forceFullscreen(packageName: String): Boolean { fullscreens += packageName; return true }
        override fun backgroundUnavailable() = "后台屏用不了。"
        override fun launchFullscreen() = fullscreen
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("Example", "example.app"))
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

    private fun open() = core.call(PhoneToolCatalog.OPEN, mapOf("app" to "example.app"))

    @Test fun theSwitchOnOpensThroughTheAccessibilityService() {
        val out = open()
        assertFalse(out.text, out.isError)
        assertEquals("the service was not asked for a fullscreen start", listOf("example.app"), opens)
        assertEquals("the system started it as well: two starts for one open", emptyList<String>(), launches)
    }

    @Test fun theSwitchOffLeavesTheStartToTheSystem() {
        fullscreen = false
        val out = open()
        assertFalse(out.text, out.isError)
        assertEquals(listOf("example.app"), launches)
        assertEquals("the switch was off but the service still started it", emptyList<String>(), opens)
    }

    @Test fun aServiceThatCannotStartItFallsBack() {
        driverUp = false
        val out = open()
        assertFalse("the person asked for the app, not for a way to start it: ${out.text}", out.isError)
        assertEquals(listOf("example.app"), launches)
        assertEquals(emptyList<String>(), opens)
    }

    @Test fun aStartThatFailsFallsBack() {
        openOk = false
        val out = open()
        assertFalse(out.text, out.isError)
        assertEquals("a service that answered no did not get a second try through it", listOf("example.app"), opens)
        assertEquals("the app was not started the plain way after the service failed", listOf("example.app"), launches)
    }

    @Test fun theRepairIsAskedForAfterAServiceStart() {
        open()
        assertEquals("the ROM's small window was not repaired after the service start", listOf("example.app"), fullscreens)
    }

    @Test fun theRepairDoesNotNeedTheService() {
        // 无障碍 off: the start is the system's, but the windowing mode still has to be repaired,
        // and that goes through the privileged channel, not through the service.
        a11y = A11yState.DISABLED
        driverPresent = false
        val out = open()
        assertFalse(out.text, out.isError)
        assertEquals(listOf("example.app"), launches)
        assertEquals("the repair was skipped because the service was gone", listOf("example.app"), fullscreens)
    }

    @Test fun theSwitchOffAsksForNoRepair() {
        fullscreen = false
        open()
        assertEquals(emptyList<String>(), fullscreens)
    }

    @Test fun noServiceMeansTheSystemStillOpensIt() {
        // driver() is null: 无障碍 is off. Pure GUI apps open either way.
        driverPresent = false
        val out = open()
        assertFalse(out.text, out.isError)
        assertEquals(listOf("example.app"), launches)
    }
}
