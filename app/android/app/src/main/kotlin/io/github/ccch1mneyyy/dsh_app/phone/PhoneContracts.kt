package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenGeom

// The contract between the phone-control modules.
// Each module owns its side:
//
//   PhoneBridge (MCP over a unix socket) ── SafetyGate ──┬── main screen:  PhoneA11yService  (accessibility)
//                                                        └── background:   BackgroundDisplay (virtual display
//                                                                          via Shizuku or self-ADB)
//   UserGate: who asks the person (overlay on the main screen, heads-up notification otherwise).
//
// Everything here is plain Kotlin with blocking calls: the bridge runs every
// action on its own single worker thread (no coroutines in this app), and each
// call has a timeout of its own.

/** Which screen a snapshot or action belongs to; the prefix of every screen id. */
enum class PhoneScreen(val prefix: Char) {
    /** What the person sees, read and driven through the accessibility service. */
    MAIN('s'),

    /** 不打扰模式: a virtual display the person doesn't see. */
    BACKGROUND('b');

    companion object {
        /** `"s7"` → MAIN, `"b3"` → BACKGROUND, anything else → null. Never falls back across screens. */
        fun ofScreenId(id: String): PhoneScreen? = entries.firstOrNull { id.length > 1 && id[0] == it.prefix }
    }
}

/**
 * What kind of page the last observation saw, from the tree's shape (not from a screenshot):
 * a normal control tree, a page that hides its tree, a canvas, or nothing readable.
 *
 * It decides the observation channel: TREE is read as text (element ids), the other three need
 * the screenshot. A canvas page (game, self-drawn UI, obfuscated page) has no usable elements, so
 * the model can only locate things by looking — that is the GUI path, and it is normal, not a fault.
 */
enum class ScreenKind {
    /** A usable control tree: element ids and the numbered screenshot both work. */
    TREE,

    /** The tree is present but blanked (sensitive/encrypted page, or the app hid its content). */
    BLANKED,

    /** No usable controls: game, self-drawn canvas, obfuscated page. Locate by looking. */
    CANVAS,

    /** Nothing readable at all (no windows): treat as a transient, re-observe. */
    UNKNOWN,
}

/**
 * One observation of a screen, rendered for a text model: one line per element,
 * Chinese role words, element ids valid only for this snapshot's [id].
 */
data class ScreenSnapshot(
    /** e.g. `s7`: prefix + a counter that increases with every snapshot of that screen. */
    val id: String,
    /** The compact text form, header line included. Never contains password contents. */
    val text: String,
    /** Foreground app label and package, when known. */
    val appLabel: String?,
    val packageName: String?,
    /** The window refused capture (FLAG_SECURE) or the tree came back empty for a secure window. */
    val secure: Boolean = false,
    /** Fewer than five text nodes: games, canvases, some mini-programs. */
    val poorTree: Boolean = false,
    /** Page shape of this observation; drives the screenshot channel and tap_xy confirmation. */
    val kind: ScreenKind = ScreenKind.TREE,
    /** Optional numbered screenshot (same element ids drawn on it), PNG or JPEG bytes. */
    val image: ByteArray? = null,
    val imageMime: String? = null,
)

/** What an element looks like to the safety rules, resolved from a snapshot id + element id. */
data class TargetInfo(
    val screenId: String,
    val elementId: Int,
    /** Chinese role word as rendered (按钮, 输入框, …). */
    val role: String,
    /** Visible text / content description / hint, already length-capped. */
    val label: String,
    val packageName: String?,
    val appLabel: String?,
    val isPassword: Boolean,
    /** Hint mentions 密码 / 验证码 / 支付密码 / CVV / 安全码. */
    val isSecretField: Boolean,
    val isEditable: Boolean,
    /**
     * Resource id (`com.tencent.mm:id/btn_send`), when known. Added by the core so the gate's
     * check `SensitiveWords.confirmHit(label, viewId)` is the same one the screen reader uses
     * for `⚠`: an icon-only send button whose id says "send" is marked AND confirmed.
     * When a driver leaves it null, only the label counts.
     */
    val viewId: String? = null,
)

sealed interface PhoneAction {
    val screenId: String

    data class Tap(override val screenId: String, val elementId: Int, val long: Boolean = false) : PhoneAction

    data class Type(
        override val screenId: String,
        val elementId: Int,
        val text: String,
        /** Press the IME action / Enter after typing. */
        val submit: Boolean = false,
    ) : PhoneAction

    data class Scroll(override val screenId: String, val elementId: Int?, val direction: Direction) : PhoneAction

    data class Key(override val screenId: String, val key: GlobalKey) : PhoneAction

    /** Flash only: a point on a numbered screenshot ([screenId] is the observation it came from). Always confirmed. */
    data class TapXY(override val screenId: String, val x: Int, val y: Int) : PhoneAction

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    enum class GlobalKey { BACK, HOME, RECENTS, NOTIFICATIONS }
}

data class ActionResult(
    val ok: Boolean,
    /** One short Chinese sentence for the model: what happened, or why not. */
    val message: String,
    /** The screen after the action settled (new lines marked `+`), when it could be read. */
    val screen: ScreenSnapshot? = null,
    /**
     * How much the screen changed, 0f..1f, when it was measured: the pixel diff on a canvas-like
     * page, or the share of elements whose fingerprint moved on a tree page. Null when nothing was
     * measured. Logged per action ([PhoneTasks.ActionLog]) so the GUI channel's effectiveness is
     * visible after the fact instead of being computed and thrown away.
     */
    val change: Float? = null,
)

data class CollectResult(
    /** De-duplicated items, one per line, in on-screen order. */
    val items: List<String>,
    val pages: Int,
    /** Stopped because: end of list / `until` matched / page limit / list lost. */
    val stopReason: String,
    val screen: ScreenSnapshot?,
)

/** Whether a driver can work right now, and if not, a sentence the model can relay. */
data class Availability(val ok: Boolean, val reason: String? = null, val userReason: String? = null)

/**
 * A screen the agent can read and drive. Implemented by the accessibility service
 * (MAIN) and by the virtual-display module (BACKGROUND). All calls block and must
 * return within their own timeout (observe ≤ 5 s, actions ≤ 15 s incl. settling).
 */
interface PhoneDriver {
    val screen: PhoneScreen

    fun availability(): Availability

    /**
     * Reads the screen. [region] (0-1000 over the screen, see [ScreenGeom.NORM]) asks for a
     * zoomed crop of the screenshot instead of the whole picture: a driver that cannot crop it
     * ignores it and returns the full frame.
     */
    fun observe(detail: Boolean, image: Boolean, region: Box? = null): ScreenSnapshot

    /**
     * Page shape of the latest observation, for the safety gate: on a canvas-like page
     * ([ScreenKind.CANVAS] / [ScreenKind.BLANKED]) a point tap is the only way to act, so the gate
     * may skip the per-tap confirmation inside a task that already approved this app. Drivers that
     * cannot tell return TREE (the gate then keeps asking, as before).
     */
    fun screenKind(): ScreenKind = ScreenKind.TREE

    fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult

    /**
     * Resolves an element of a recent snapshot for the safety rules. Null when the
     * snapshot is stale or the element no longer matches its fingerprint
     * (window, bounds, text); the bridge then refuses and re-observes.
     */
    fun target(screenId: String, elementId: Int): TargetInfo?

    fun perform(action: PhoneAction): ActionResult

    /** Launches [packageName] on this screen (BACKGROUND moves the task to the virtual display). */
    fun open(packageName: String): ActionResult

    /** Package of the app in front on this screen, if known (for per-app approval and deny lists). */
    fun foregroundPackage(): String?

    /** A keyboard window is on screen, i.e. some field already has the focus. */
    fun imeShown(): Boolean = false

    /**
     * Drops the screen the model was last given.
     *
     * Screen ids (`s7`) are only meaningful up to the next read, and a model that carries them into
     * the next task taps an id that belongs to a page the previous task showed: every such call
     * answered `stale` (log 15:33:37 `tool tap error decision=stale at=点 s13#2 task=2`).
     */
    fun forgetScreens() {}

    /**
     * The element numbers of the screen the model was last given, as one line ("#3 搜索、#5 返回").
     * A `tap` with a number that no longer exists answers with this list, so a wrong number costs
     * one call instead of a `screen` plus a guess (measured four times in one 炉石 task).
     */
    fun numbered(): String = ""

    /** Whether a focused editable field exists to paste into (微信 hides its tree, so: no). */
    fun fieldReady(): Boolean = false

    /**
     * Puts [text] on the system clipboard. An accessibility service is allowed to write it where
     * this app's own process is refused ("Denying clipboard access … not in focus"), which is what
     * the paste route needs. False when nothing could be written.
     */
    fun setClipboard(text: String): Boolean = false

    /**
     * Pastes the clipboard into the field that has focus, when the page exposes one. False on a
     * page whose tree is hidden (微信, games): there the paste has to go through the paste key.
     */
    fun pasteField(): Boolean = false
}

data class ConfirmRequest(
    /** The full plain sentence shown to the person, e.g. 在「微信」给 张三 发送："晚上见". */
    val sentence: String,
    val appLabel: String?,
    val packageName: String?,
    /** First action in this app during this task: approval covers the rest of the task. */
    val firstInApp: Boolean,
    val timeoutMs: Long = 30_000,
    /**
     * 本任务免确认: offer [Decision.ALLOW_ALL_FOR_TASK] as the main choice (see ConfirmMode); a
     * gate that ignores it still answers correctly, just asks more.
     */
    val offerTaskWaiver: Boolean = false,
)

/** [ALLOW_ALL_FOR_TASK]: nothing else in this task is asked (only offered with [ConfirmRequest.offerTaskWaiver]). */
enum class Decision { ALLOW_ONCE, ALLOW_APP_FOR_TASK, ALLOW_ALL_FOR_TASK, DENY, STOP, UNAVAILABLE }

enum class HandoffResult { CONTINUE, STOP, TIMEOUT }

/**
 * Why a `handoff` came: the hard rule that refused the task's last write, when there was one.
 * The card names it in its title (「密码需要你来输入」) so the person knows it is a rule, not a
 * fault; the model's own sentence stays the body. Null: the model handed off on its own.
 */
enum class HandoffCause(val rule: String) {
    /** A password, verification-code or payment-code input. */
    SECRET("secret-field"),

    /** System settings, permissions, installers, security centres, SystemUI. */
    SYSTEM("system-page"),

    /** An app on the person's 不碰 list. */
    DENY_LIST("deny-list"),

    /**
     * A page that can only be worked through the picture (canvas, blanked) while the model cannot
     * see pictures. Both channels are then closed: there is no tree to read and the screenshot is
     * unreadable, so the right move is to stop and ask the person, not to keep guessing coordinates.
     */
    NO_VISION("no-vision");

    companion object {
        /** From an action-log decision (`refused:<rule>`); null for anything else. */
        fun ofDecision(decision: String?): HandoffCause? {
            if (decision == null || !decision.startsWith("refused:")) return null
            val rule = decision.removePrefix("refused:")
            return entries.firstOrNull { it.rule == rule }
        }
    }
}

/**
 * Asks the person. The main-screen implementation is the accessibility overlay
 * (ControlOverlay); without it, a heads-up notification. Confirm buttons must not be
 * reachable by injected input.
 */
interface UserGate {
    fun confirm(request: ConfirmRequest): Decision

    /** Blocks until the person taps 继续 / 停止, up to [timeoutMs] (≤ 10 min). */
    fun handoff(reason: String, timeoutMs: Long): HandoffResult

    /** [handoff] with the rule behind it, for the card's title; gates that can't show it ignore it. */
    fun handoff(reason: String, timeoutMs: Long, cause: HandoffCause?): HandoffResult = handoff(reason, timeoutMs)

    /** The one-line "DSH 正在操作 · 点「路线」" status; null clears it. */
    fun status(text: String?)

    /** Run [ifCurrent] at cleanup time; a newer task must keep its windows and pending questions. */
    fun close(ifCurrent: (() -> Unit) -> Unit = { it() }) = ifCurrent { status(null) }
}

/**
 * What the person does on the overlay outside a confirm / handoff, and the stops the
 * accessibility service itself sees. Implemented by the core (task owner), set in
 * [PhoneRegistry.controlListener]. Called on the main thread; must return quickly.
 *
 * The status bar's 暂停 / 停止 / 插话 and the stop on screen off, lock or service loss
 * reach whoever owns the task through here. While paused, MAIN actions are refused by the
 * driver as well (defence in depth).
 */
interface ControlListener {
    /** 暂停 (true) / 继续 (false) on the status bar. */
    fun onPauseChanged(paused: Boolean) {}

    /** 停止 on the status bar, screen off, keyguard, or the service going away. [reason] is Chinese. */
    fun onStopRequested(reason: String) {}

    /** Text the person typed into 插话 and sent; meant for the agent as a user message. */
    fun onInterject(text: String) {}

    /** The accessibility service connected or went away (for the `status` event). */
    fun onAccessibilityChanged(connected: Boolean) {}
}

/**
 * Where the modules meet at run time. Services register themselves when they
 * connect and clear on disconnect; the bridge reads these on every call.
 */
object PhoneRegistry {
    @Volatile var mainDriver: PhoneDriver? = null
    @Volatile var backgroundDriver: PhoneDriver? = null

    /** Overlay gate when the accessibility service runs, else the notification gate. */
    @Volatile var overlayGate: UserGate? = null
    @Volatile var notificationGate: UserGate? = null

    /** Process-wide arbiter for native and engine questions; presenters do not call one another. */
    @Volatile var promptGate: UserGate? = null

    /** Set by the core; see [ControlListener]. */
    @Volatile var controlListener: ControlListener? = null

    /**
     * The hard rules the person lifted (解除限制), for the drivers' own second line of refusals and
     * the overlay's 「已解除限制」 tag. Nothing lifted until the core says otherwise.
     */
    @Volatile var unlocks: () -> PhoneUnlocks = { PhoneUnlocks() }

    fun driver(screen: PhoneScreen): PhoneDriver? = when (screen) {
        PhoneScreen.MAIN -> mainDriver
        PhoneScreen.BACKGROUND -> backgroundDriver
    }

    fun gate(): UserGate? = overlayGate ?: notificationGate
}

/** Optional native UI copy for pure phone-control code. */
fun interface PhoneUiText {
    fun resolve(key: String, args: List<Any>): String?

    fun get(key: String, fallback: String, vararg args: Any): String =
        resolve(key, args.toList()) ?: String.format(java.util.Locale.ROOT, fallback, *args)

    fun role(value: String): String = when (value) {
        "按钮" -> get("phone_role_button", value) // l10n:keep model role key
        "输入框" -> get("phone_role_input", value) // l10n:keep model role key
        "列表" -> get("phone_role_list", value) // l10n:keep model role key
        "开关" -> get("phone_role_switch", value) // l10n:keep model role key
        "勾选框" -> get("phone_role_checkbox", value) // l10n:keep model role key
        "单选" -> get("phone_role_radio", value) // l10n:keep model role key
        "滑块" -> get("phone_role_slider", value) // l10n:keep model role key
        "下拉框" -> get("phone_role_dropdown", value) // l10n:keep model role key
        "链接" -> get("phone_role_link", value) // l10n:keep model role key
        "标签" -> get("phone_role_tab", value) // l10n:keep model role key
        "图片" -> get("phone_role_image", value) // l10n:keep model role key
        "文本" -> get("phone_role_text", value) // l10n:keep model role key
        else -> value
    }

    companion object {
        val DEFAULT = PhoneUiText { _, _ -> null }
    }
}
