package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Typing on a page with no field ids (微信, games): the tree is empty there, so `type` has no
 * element to act on. The text goes through DSH 输入法 instead — and when that input method is not
 * the one in use, the model is told, instead of a write silently doing nothing.
 */
class PhoneImeTest {
    private val tasks = PhoneTasks()
    private var pkg: String? = "com.tencent.mm"
    private var appLabel: String? = "微信"
    private var kind = ScreenKind.BLANKED
    private var imeReady = true
    private var imeSecret = false
    private val typedIn = mutableListOf<Pair<String, Boolean>>()
    private var commitAnswer: String? = null

    private fun driver(screen: PhoneScreen, id: String) = object : PhoneDriver {
        override val screen = screen
        override fun availability() = Availability(true)
        override fun foregroundPackage() = pkg
        override fun screenKind() = kind
        override fun observe(detail: Boolean, image: Boolean, region: Box?) =
            ScreenSnapshot(id = id, text = "微信", appLabel = appLabel, packageName = pkg, kind = kind)
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unexpected collect")
        override fun target(screenId: String, elementId: Int) = null
        override fun perform(action: PhoneAction) = error("unexpected action")
        override fun open(packageName: String) = error("unexpected open")
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
        override fun driver(screen: PhoneScreen): PhoneDriver? = driver(screen, if (screen == PhoneScreen.MAIN) "s1" else "b1")
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "后台屏用不了。"
        override fun guiScheme() = PhoneCore.DEFAULT_GUI_SCHEME
        override fun imeReady() = imeReady
        override fun imePassword() = imeSecret
        override fun imeCommit(text: String, submit: Boolean): String? {
            typedIn += text to submit
            return commitAnswer
        }
        override fun zone() = ZoneId.of("UTC")
        override fun notifyFix(kind: String) {}
        override fun beforeHandoff(screen: PhoneScreen): String? = null
        override fun sleep(ms: Long) {}
        override fun system() = object : SystemApi {
            override fun apps() = listOf(AppEntry("微信", "com.tencent.mm"))
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

    private val core = PhoneCore(env, tasks, ActionLog()).also { tasks.begin() }

    @After fun close() = core.worker.shutdown()

    private fun type(text: String, submit: Boolean = false) =
        core.call(PhoneToolCatalog.TYPE, mapOf("screen" to "s1", "text" to text, "submit" to submit))

    @Test fun theTextGoesThroughTheInputMethodWhenThePageHasNoField() {
        // 微信 hides its tree: no field id exists, yet the person expects the search box to fill.
        val out = type("银行卡")
        assertFalse(out.isError)
        assertEquals(listOf("银行卡" to false), typedIn)
        assertTrue(out.text, out.text.contains("DSH 输入法"))
    }

    @Test fun submitTravelsWithTheText() {
        val out = type("天气", submit = true)
        assertFalse(out.isError)
        assertEquals(listOf("天气" to true), typedIn)
        assertTrue(out.text, out.text.contains("提交"))
    }

    @Test fun withoutTheInputMethodTheModelIsToldWhichOne() {
        imeReady = false
        val out = type("银行卡")
        assertTrue(out.isError)
        assertTrue(typedIn.isEmpty())
        assertTrue(out.text, out.text.contains("DSH 输入法"))
        assertTrue(out.text, out.text.contains("tap_xy"))
    }

    @Test fun aPasswordFieldIsNeverFilled() {
        imeSecret = true
        val out = type("123456")
        assertTrue(out.isError)
        assertTrue(typedIn.isEmpty())
        assertTrue(out.text, out.text.contains("密码"))
        assertEquals("refused:secret-field", core.log.last()!!.decision)
    }

    @Test fun whatTheInputMethodRefusesComesBack() {
        commitAnswer = "应用没有接受这段文字。"
        val out = type("银行卡")
        assertTrue(out.isError)
        assertTrue(out.text, out.text.contains("应用没有接受这段文字。"))
    }
}
