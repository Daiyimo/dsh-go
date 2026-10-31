// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: the screen text format instead of JSON; fingerprints re-checked against a fresh
// capture; password fields marked rather than failing the screen; collect, open, numbered
// screenshots, tap_xy; blocking calls with time budgets.

package io.github.ccch1mneyyy.dsh_app.phone.a11y

import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.R
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.ccch1mneyyy.dsh_app.phone.ActionResult
import io.github.ccch1mneyyy.dsh_app.phone.Availability
import io.github.ccch1mneyyy.dsh_app.phone.CollectResult
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry
import io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.SafetyRules
import io.github.ccch1mneyyy.dsh_app.phone.ScreenKind
import io.github.ccch1mneyyy.dsh_app.phone.ScreenSnapshot
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * The main screen, read and driven through [PhoneA11yService]. Calls block (bridge worker
 * thread); observe stays under ~5 s, actions under ~15 s, collect under ~100 s. Only the
 * latest snapshot's ids are valid; every action re-reads the tree and refuses when the
 * element's fingerprint no longer matches.
 */
internal class MainScreenDriver(private val service: PhoneA11yService, private val overlay: ControlOverlay) : PhoneDriver {
    override val screen = PhoneScreen.MAIN

    private val ownPackage: String = service.packageName

    /** 解除限制, read at each check: the person may lift or restore a rule between calls. */
    private val lifted: PhoneUnlocks get() = PhoneRegistry.unlocks()
    private val clock get() = service.clock
    private val actuator = Actuator(service, overlay)
    private val shooter = ScreenShooter(service, overlay)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val labels = ConcurrentHashMap<String, String>()
    private val lock = Any()
    private var latest: Snapshot? = null

    /** The app this task launched last: a page with no tree cannot name itself. */
    private val hint = ForegroundHint()

    /**
     * Consecutive no-change hits on one target, kept across `screen` calls: a target the model
     * keeps re-aiming at without any reaction is reported once with a checklist and then stopped,
     * instead of being retried until the budget runs out. [verifyPointTap] counts point taps,
     * [changeNote] counts element taps; a change, a different target or a different app clears it.
     */
    private val missLock = Any()
    private var missKey: String? = null
    private var missCount = 0

    /** Bumped by [abort] (stop, screen off): loops and waits started before it give up. */
    @Volatile private var abortGen = 0L

    private class Snapshot(
        val id: String,
        val screen: FormattedScreen,
        val packageName: String?,
        val appLabel: String?,
        val protectedPage: Boolean,
        val ownApp: Boolean,
        val mainWindowId: Int?,
        /** The front window hides its content from us (data-sensitive page): reported as secure. */
        val hidden: Boolean,
        /** Display bounds at capture time: an orientation change makes old pixel maths wrong. */
        val display: Box,
    ) {
        @Volatile var image: ImageGeometry? = null

        /** The screenshot attached to this snapshot (canvas pages), for post-tap verification. */
        @Volatile var bytes: ByteArray? = null
        val fingerprints: Set<String> by lazy { screen.elements.map { it.fingerprint }.toSet() }
    }

    fun close() {
        actuator.close()
        shooter.close()
    }

    fun abort() { abortGen++ }

    // ---- PhoneDriver ------------------------------------------------------------------------------

    override fun availability(): Availability = when {
        PhoneA11yService.instance == null ->
            // l10n:keep agent screen result
            Availability(false, "DSH 的无障碍服务没有连接。请用户到 系统设置 → 无障碍 里重新打开「DSH 手机操控」。", AppStrings.get(service, R.string.phone_availability_disconnected))
        // l10n:keep agent screen result
        !power.isInteractive -> Availability(false, "屏幕已关闭。DSH 只在亮屏、解锁时操作手机，请用户点亮并解锁。", AppStrings.get(service, R.string.phone_availability_screen_off))
        // l10n:keep agent screen result
        keyguard.isKeyguardLocked -> Availability(false, "手机已锁屏。请用户解锁后再继续。", AppStrings.get(service, R.string.phone_availability_locked))
        else -> Availability(true)
    }

    override fun screenKind(): ScreenKind = latest?.screen?.kind ?: ScreenKind.TREE

    override fun foregroundPackage(): String? =
        hint.seen(TreeCapture.foregroundPackage(service, screenBox()), SystemClock.uptimeMillis())

    /**
     * 微信's window shows nothing to the accessibility tree, so [pasteField] has nothing to act on;
     * the text still has to reach the field, and the clipboard is the one channel that survives a
     * hidden tree. This app's own process is refused ("Denying clipboard access … not in focus"),
     * an accessibility service is not.
     */
    override fun setClipboard(text: String): Boolean = runCatching {
        val manager = service.getSystemService(android.content.ClipboardManager::class.java)
            ?: return false
        manager.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text))
        true
    }.getOrDefault(false)

    override fun imeShown(): Boolean = runCatching {
        service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    override fun fieldReady(): Boolean =
        runCatching { focusedField(service.rootInActiveWindow ?: return false) != null }.getOrDefault(false)

    override fun pasteField(): Boolean = runCatching {
        val root = service.rootInActiveWindow ?: return false
        val field = focusedField(root) ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)

    /** The editable node the cursor is in, else the only editable one on the page. */
    private fun focusedField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val edits = ArrayList<AccessibilityNodeInfo>(4)
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            if (node.isEditable) edits += node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return edits.firstOrNull { it.isFocused } ?: edits.singleOrNull()
    }

    override fun observe(detail: Boolean, image: Boolean, region: Box?): ScreenSnapshot = synchronized(lock) {
        val start = SystemClock.uptimeMillis()
        availability().let { if (!it.ok) return blank(it.reason!!) }
        val cap = capture()
        try {
            val snap = publish(cap, detail)
            val text = StringBuilder(snap.screen.text)
            var bytes: ByteArray? = null
            var mime: String? = null
            var secure = false
            // A canvas / blanked page has no element numbers to read, so the screenshot is not an
            // optional extra: it is the only channel the model has. Attaching it without the model
            // having to ask for image:true is what keeps those pages operable at all - the model
            // that forgets the flag used to get a bare "文字信息不足" and nothing to look at.
            val wantImage = image || snap.screen.kind == ScreenKind.CANVAS || snap.screen.kind == ScreenKind.BLANKED
            if (wantImage) {
                val left = OBSERVE_MS - (SystemClock.uptimeMillis() - start)
                when {
                    // l10n:keep agent screen result
                    snap.ownApp && !lifted.ownApp -> text.append("\n提示：DSH 自己的界面不截图。")
                    // l10n:keep agent screen result
                    ScreenFormat.index(cap.windows).any { it.fullText.contains("«银行卡") || it.fullText.contains("«身份证") } ->
                        // l10n:keep agent screen result
                        text.append("\n提示：本屏有银行卡号或身份证号，这次没有附截图。")
                    // l10n:keep agent screen result
                    left < 900 -> text.append("\n提示：读屏用时过长，这次没有附截图。")
                    else -> {
                        val window = if (cap.windows.size == 1) cap.windows[0] else null
                        val secrets = ScreenShooter.sensitiveBounds(cap.windows)
                        // A zoomed crop is for reading small print, not for matching ids: no numbers on it, so the
                        // note that says "no element numbers, read the 10x10 grid" stays true.
                        val shot = shooter.shoot(window, cap.screen, if (region == null) snap.screen.elements else emptyList(), secrets, minOf(left - 300, 3_000), region)
                        secure = shot.secure
                        if (shot.bytes != null) {
                            bytes = shot.bytes
                            mime = shot.mime
                            snap.image = shot.geometry
                            // l10n:keep agent screen result
                            snap.bytes = shot.bytes
                            // l10n:keep agent screen result
                            if (region == null && snap.screen.kind == ScreenKind.TREE && snap.screen.elements.isNotEmpty()) {
                                // l10n:keep agent screen result
                            text.append("\n截图上的数字就是上面的元素编号；tap_xy 的坐标按 0-1000 归一化（0,0=截图左上角，1000,1000=截图右下角），不要用像素。")
                            } else {
                                // l10n:keep agent screen result
                            text.append("\n已附图：本页没有控件编号，请直接看画面；点击用 tap_xy 的 0-1000 归一化坐标（0,0=截图左上角，1000,1000=截图右下角），图上有 10×10 刻度，每格 100。")
                            }
                        // l10n:keep agent screen result
                        } else text.append("\n提示：${shot.error ?: "截图失败"}。")
                    }
                }
            }
            if (wantImage && bytes == null) {
                // The kind note promised the screenshot channel; when it did not open, say so
                // plainly instead of letting the model hunt for a picture that is not there.
                // l10n:keep agent screen result
                text.append("\n提示：这次没有拿到截图（原因见上）。这类页面只能看图，请重新 screen 再试；反复拿不到就 handoff 交给用户。")
            }
            ScreenSnapshot(
                snap.id, text.toString(), snap.appLabel, snap.packageName,
                // A hidden tree is a blanked page, not a secure window: the picture is the
                // channel for it (kind is already BLANKED, and the app is readable). Reporting it
                // as secure made the core refuse the very page the screenshot exists for.
                secure = secure, poorTree = snap.screen.poorTree,
                kind = snap.screen.kind, image = bytes, imageMime = mime,
            )
        } finally { cap.recycle() }
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? = synchronized(lock) {
        val snap = latest ?: return null
        if (snap.id != screenId) return null
        val el = snap.screen.element(elementId) ?: return null
        if (!availability().ok) return null
        val cap = capture()
        try { if (locate(cap, el) == null) return null } finally { cap.recycle() }
        info(screenId, el)
    }

    /** [el] as the core and the gate see it. */
    private fun info(screenId: String, el: ScreenElement) = TargetInfo(
        screenId = screenId, elementId = el.id, role = el.role, label = el.label,
        packageName = el.packageName, appLabel = label(el.packageName), isPassword = el.password,
        isSecretField = el.secret, isEditable = el.editable, viewId = el.viewId,
    )

    override fun perform(action: PhoneAction): ActionResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper()) return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (overlay.paused) return ActionResult(false, "用户在悬浮条上按了暂停。等用户点「继续」后再操作，不要换别的方式绕过。")
            // l10n:keep agent screen result
            if (!awaitInputIdle()) return ActionResult(false, "用户正在悬浮条上输入插话，稍等再操作。")
            val gen = abortGen
            return when (action) {
                is PhoneAction.Key -> key(action, gen)
                is PhoneAction.Tap -> tap(action, gen)
                is PhoneAction.Type -> type(action, gen)
                is PhoneAction.Scroll -> scroll(action, gen)
                is PhoneAction.TapXY -> tapXY(action, gen)
            }
        }
    }

    override fun open(packageName: String): ActionResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper()) return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (packageName == ownPackage && !lifted.ownApp) return ActionResult(false, "DSH 自己不需要打开。")
            val name = label(packageName) ?: packageName
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                // l10n:keep agent screen result
                ?: return ActionResult(false, "没找到「$name」，或它没有可以打开的界面。可以先用 apps 查一下包名。")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val gen = abortGen
            // This service owns no activity, so the system treats the start as a background one.
            // StepX answers that by dropping the app into the multi-window container: a small
            // floating window with two SystemUI decorations stacked above it. The app is then not
            // the top-most window, the tree reads the decorations instead of the app, and the
            // screenshot the GUI fallback needs never shows the page it was opened for. Asking
            // for the fullscreen windowing mode (plus the whole display as the launch bounds) keeps
            // the window filling the screen, which is where tap_xy is meant to run; a ROM that
            // refuses the bundle still gets the plain start.
            // The same box taps and screenshots use: it follows rotation, where the service's
            // own metrics can still describe the display the service was configured on.
            val box = screenBox()
            val full = Rect(box.left, box.top, box.right, box.bottom)
            try {
                val options = ActivityOptions.makeBasic().setLaunchBounds(full).toBundle()
                // WindowConfiguration.WINDOWING_MODE_FULLSCREEN: hidden from the SDK, but the
                // framework still reads this key when it picks the windowing mode to launch into.
                options.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FULLSCREEN)
                service.startActivity(intent, options)
            } catch (e: Exception) {
                try { service.startActivity(intent) } catch (e2: Exception) {
                    // l10n:keep agent screen result
                    return ActionResult(false, "打开「$name」失败：${e2.message ?: e2.javaClass.simpleName}")
                }
            }
            // A page with no tree cannot say who it is; this keeps `open` answerable for it.
            hint.opened(packageName, SystemClock.uptimeMillis())
            val deadline = SystemClock.uptimeMillis() + 6_000
            var front: String? = null
            while (SystemClock.uptimeMillis() < deadline && !cancelled(gen)) {
                front = foregroundPackage()
                if (front == packageName) break
                clock.pause(150) { cancelled(gen) }
            }
            if (front == packageName) clock.settle(clock.count, 800, 400, 3_000) { cancelled(gen) }
            val snap = if (front == packageName && availability().ok) {
                val cap = settledCapture(null, gen)
                try { toSnapshot(publish(cap, false)) } finally { cap.recycle() }
            } else observeQuiet()
            // l10n:keep agent screen result
            val opened = when (snap?.kind) {
                null, ScreenKind.CANVAS, ScreenKind.BLANKED -> ActionResult(true, "已打开「$name」。", snap)
                // l10n:keep agent screen result
                else -> ActionResult(
                    true,
                    "已打开「$name」。页面可能还在加载：直接再 screen 读一次，不要回桌面找图标。",
                    snap,
                )
            }
            return if (front == packageName) opened
            // l10n:keep agent screen result
            else ActionResult(false, "已请求打开「$name」，但 6 秒内它没有到前台（现在是「${label(front) ?: front ?: "未知"}」）。", snap)
        }
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper()) return CollectResult(emptyList(), 0, "内部错误：不能在主线程执行", null)
        synchronized(lock) {
            availability().let { if (!it.ok) return CollectResult(emptyList(), 0, it.reason!!, null) }
            val snap = latest
            if (snap == null || snap.id != screenId) {
                // l10n:keep agent screen result
                return CollectResult(emptyList(), 0, "屏幕编号 $screenId 已过期，请先看新屏幕再 collect", observeQuiet())
            }
            // l10n:keep agent screen result
            if (snap.ownApp && !lifted.ownApp) return CollectResult(emptyList(), 0, "这是 DSH 自己的界面，不读取", null)
            var el = snap.screen.element(listElementId)
                // l10n:keep agent screen result
                ?: return CollectResult(emptyList(), 0, "屏幕 $screenId 里没有编号 $listElementId", null)
            if (el.kind != ElementKind.LIST) {
                el = el.listId?.let { snap.screen.element(it) }
                    // l10n:keep agent screen result
                    ?: return CollectResult(emptyList(), 0, "[$listElementId] 不是列表，也不在列表里", null)
            }
            val pages = maxPages.coerceIn(1, 30)
            val wanted = until?.trim()?.takeIf { it.isNotEmpty() }
            val gen = abortGen
            val deadline = SystemClock.uptimeMillis() + COLLECT_MS
            val items = CollectedItems()
            // l10n:keep agent screen result
            var stop = "到底了"
            var read = 0
            var misses = 0
            val direction = if (el.horizontal) PhoneAction.Direction.RIGHT else PhoneAction.Direction.DOWN
            var lastBounds = el.bounds
            while (true) {
                val cap = capture()
                try {
                    val list = ScreenFormat.followList(ScreenFormat.index(cap.windows), el.fingerprint, lastBounds)
                        // l10n:keep agent screen result
                        ?: run { stop = "列表不见了（界面变了）"; null } ?: break
                    lastBounds = list.bounds
                    read++
                    var added = 0
                    var found = false
                    for (t in list.itemTexts) {
                        if (items.add(t)) {
                            added++
                            if (wanted != null && t.contains(wanted, ignoreCase = true)) found = true
                        }
                    }
                    // l10n:keep agent screen result
                    if (found) { stop = "找到了「$wanted」"; break }
                    // l10n:keep agent screen result
                    if (items.chars > COLLECT_CHARS) { stop = "内容太多，先停在这里"; break }
                    // l10n:keep agent screen result
                    if (read > 1 && added == 0) { if (++misses >= 2) { stop = "到底了"; break } } else misses = 0
                    // l10n:keep agent screen result
                    if (read >= pages) { stop = "达到页数上限（$pages 页）"; break }
                    // l10n:keep agent screen result
                    if (cancelled(gen)) { stop = "已停止"; break }
                    // l10n:keep agent screen result
                    if (overlay.paused) { stop = "用户暂停了"; break }
                    // l10n:keep agent screen result
                    if (SystemClock.uptimeMillis() > deadline) { stop = "超时"; break }
                    // l10n:keep agent screen result
                    val node = cap.native(list.ref) ?: run { stop = "列表不见了（界面变了）"; null } ?: break
                    val before = clock.count
                    val out = actuator.scroll(node, el.scroll, el.horizontal, list.bounds, cap.screen, direction)
                    // l10n:keep agent screen result
                    if (!out.accepted) { stop = "到底了"; break }
                    clock.settle(before, 1_200, 350, 3_000) { cancelled(gen) }
                    if (misses > 0) clock.pause(800) { cancelled(gen) } // lists that load more on demand
                } finally { cap.recycle() }
            }
            return CollectResult(items.list(), read, stop, observeQuiet())
        }
    }

    // ---- actions ----------------------------------------------------------------------------------

    private class Resolved(val snap: Snapshot, val el: ScreenElement, val cap: Capture, val node: AccessibilityNodeInfo, val found: IndexedElement)

    /** The element of the latest snapshot, re-verified against a fresh capture; or the refusal. */
    private fun resolve(screenId: String, elementId: Int): Any {
        val snap = latest
        if (snap == null || snap.id != screenId) {
            val now = observeQuiet()
            // l10n:keep agent screen result
            val msg = if (snap == null) "还没有读过屏幕，先用 screen 看一下。"
            // l10n:keep agent screen result
            else "屏幕编号 $screenId 已过期（最新是 ${now?.id ?: snap.id}），没有执行。请按新屏幕的编号操作。"
            return ActionResult(false, msg, now)
        }
        val el = snap.screen.element(elementId)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "屏幕 $screenId 里没有编号 $elementId（共 ${snap.screen.elements.size} 项）。")
        val cap = capture()
        val hit = locate(cap, el)
        val node = hit?.let { cap.native(it.ref) }
        if (hit == null || node == null) {
            val now = try { publish(cap, false).let { toSnapshot(it) } } finally { cap.recycle() }
            // l10n:keep agent screen result
            return ActionResult(false, "目标 [$elementId]「${short(el.label.ifEmpty { el.role })}」已经变了（界面有变化），没有执行。请看新屏幕再操作。", now)
        }
        return Resolved(snap, el, cap, node, hit)
    }

    private fun refuseWrite(r: Resolved): ActionResult? {
        val lifted = lifted
        return when {
            // l10n:keep agent screen result
            (r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp -> ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            r.snap.protectedPage && !lifted.systemPages -> ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击或输入。需要的话请用 handoff 交给用户。")
            // l10n:keep agent screen result
            (r.el.password || r.el.secret) && !lifted.secretFields -> ActionResult(false, "这是密码 / 验证码一类的输入框，DSH 不替用户点或填。请用 handoff 让用户自己来。")
            else -> null
        }
    }

    private fun tap(a: PhoneAction.Tap, gen: Long): ActionResult {
        val res = resolve(a.screenId, a.elementId)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            refuseWrite(r)?.let { return it }
            if (r.el.kind == ElementKind.LIST) {
                // l10n:keep agent screen result
                return ActionResult(false, "[${a.elementId}] 是列表本身，不能点。看更多用 scroll，整理全部条目用 collect。")
            }
            val before = clock.count
            val out = actuator.click(r.node, r.el.bounds, r.cap.screen, a.long)
            val what = short(r.el.label.ifEmpty { r.el.role })
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "系统没有接受这次${if (a.long) "长按" else "点击"}「$what」。", observeQuiet())
            // l10n:keep agent screen result
            return after(before, r.snap, gen, "已${if (a.long) "长按" else "点击"}「$what」。", track = "p|${r.snap.packageName}|el:${r.el.fingerprint}")
        } finally { r.cap.recycle() }
    }

    private fun type(a: PhoneAction.Type, gen: Long): ActionResult {
        val res = resolve(a.screenId, a.elementId)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            refuseWrite(r)?.let { return it }
            if (r.el.kind != ElementKind.INPUT || !r.node.isEditable) {
                // l10n:keep agent screen result
                return ActionResult(false, "[${a.elementId}]「${short(r.el.label)}」不是输入框。可以先点它，等输入框出现后再输入。")
            }
            // l10n:keep agent screen result
            if (r.node.isPassword && !lifted.secretFields) return ActionResult(false, "这是密码框，DSH 不代填。请用 handoff 让用户自己输入。")
            // l10n:keep agent screen result
            if (a.text.length > 2_000) return ActionResult(false, "一次最多输入 2000 字。")
            val before = clock.count
            if (!actuator.setText(r.node, a.text)) {
                // l10n:keep agent screen result
                return ActionResult(false, "这个输入框不接受代填（系统拒绝了输入）。可以先点一下它再试，或请用户来输入。", observeQuiet())
            }
            val got = readBack(r.node, a.text, gen)
            // l10n:keep agent screen result
            val name = short(r.el.label.ifEmpty { "输入框" })
            if (!sameText(got, a.text)) {
                return ActionResult(false, readBackMismatch(got, info(r.snap.id, r.el)), settledSnapshot(before, gen))
            }
            // l10n:keep agent screen result
            var message = "已在「$name」输入，回读一致。"
            if (a.submit) {
                // l10n:keep agent screen result
                if (actuator.imeEnter(r.node)) message += "已按键盘上的发送 / 搜索键。"
                // l10n:keep agent screen result
                else return ActionResult(false, "$message 但这个输入框不支持代按回车，没有提交：请点页面上的「发送」或「搜索」按钮。", settledSnapshot(before, gen))
            }
            return after(before, r.snap, gen, message)
        } finally { r.cap.recycle() }
    }

    private fun scroll(a: PhoneAction.Scroll, gen: Long): ActionResult {
        val snap = latest
        val dirWord = when (a.direction) {
            // l10n:keep agent screen result
            PhoneAction.Direction.DOWN -> "向下"; PhoneAction.Direction.UP -> "向上"
            // l10n:keep agent screen result
            PhoneAction.Direction.LEFT -> "向左"; PhoneAction.Direction.RIGHT -> "向右"
        }
        // The element to scroll: the given one (or its list), else the biggest list on screen.
        var targetId: Int? = a.elementId
        if (targetId != null && snap != null && snap.id == a.screenId) {
            val el = snap.screen.element(targetId)
            if (el != null && el.kind != ElementKind.LIST) targetId = el.listId ?: targetId
        } else if (targetId == null && snap != null && snap.id == a.screenId) {
            targetId = snap.screen.elements.filter { it.kind == ElementKind.LIST }.maxByOrNull { it.bounds.area }?.id
        }
        if (targetId == null) {
            // l10n:keep agent screen result
            if (snap?.ownApp == true && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            val screen = screenBox()
            val before = clock.count
            val out = actuator.scroll(null, 0, a.direction == PhoneAction.Direction.LEFT || a.direction == PhoneAction.Direction.RIGHT,
                Box(screen.left, screen.top + screen.height / 5, screen.right, screen.bottom - screen.height / 6), screen, a.direction)
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "没能滚动。", observeQuiet())
            // l10n:keep agent screen result
            return after(before, snap, gen, "已${dirWord}滑动屏幕。", scrolled = true)
        }
        val res = resolve(a.screenId, targetId)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            // l10n:keep agent screen result
            if ((r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            val before = clock.count
            val out = actuator.scroll(r.node, r.el.scroll, r.el.horizontal, r.el.bounds, r.cap.screen, a.direction)
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "没能${dirWord}滚动，可能已经到头了。", observeQuiet())
            // l10n:keep agent screen result
            return after(before, r.snap, gen, "已${dirWord}滚动${if (r.el.kind == ElementKind.LIST) "列表 [${r.el.id}]" else ""}。", scrolled = true)
        } finally { r.cap.recycle() }
    }

    private fun key(a: PhoneAction.Key, gen: Long): ActionResult {
        val before = clock.count
        // l10n:keep agent screen result
        if (!actuator.global(a.key)) return ActionResult(false, "系统没有执行这个按键。")
        val what = when (a.key) {
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.BACK -> "已按返回键。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.HOME -> "已回到桌面。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.RECENTS -> "已打开最近任务。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.NOTIFICATIONS -> "已拉下通知栏。"
        }
        return after(before, latest, gen, what)
    }

    private fun tapXY(a: PhoneAction.TapXY, gen: Long): ActionResult {
        val snap = latest
        if (snap == null || snap.id != a.screenId) {
            val now = observeQuiet()
            // l10n:keep agent screen result
            return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，坐标对不上了。请重新 screen 看屏再点。", now)
        }
        // l10n:keep agent screen result
        // A tree page needs no coordinates: every element has an id, and a point tap there would
        // bypass the element checks (secret words, target re-verification) for no gain. Only a
        // picture page — where `observe` attaches the screenshot — has a point to tap.
        val geometry = snap.image ?: return ActionResult(false, if (snap.screen.kind == ScreenKind.TREE) {
            // l10n:keep agent screen result
            "屏幕 ${a.screenId} 能读到控件：用 tap / type / scroll 点元素编号，不要用 tap_xy。坐标点击只给 canvas 这类只有画面的页面用。"
        } else {
            // l10n:keep agent screen result
            "屏幕 ${a.screenId} 没有截图。坐标点击要先用 screen 看屏；canvas 页面 DSH 会自动附图。"
        })
        // 0-1000 归一化 → 截图像素 → 屏幕像素。截图和屏幕之间的比例由 ImageGeometry 管, 这里只负责
        // 模型说的那一套单位: 单位混用是坐标点击最贵的故障(像素当归一化点偏几百像素且不报错)。
        val point = geometry.toScreen(ScreenGeom.normToPx(a.x, geometry.width), ScreenGeom.normToPx(a.y, geometry.height))
            // l10n:keep agent screen result
            ?: return ActionResult(false, "坐标 (${a.x}, ${a.y}) 换算后不在截图范围内（截图是 ${geometry.width}×${geometry.height}）。请重新 screen 看屏再点。")
        // l10n:keep agent screen result
        if (snap.ownApp && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
        // l10n:keep agent screen result
        if (snap.protectedPage && !lifted.systemPages) return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击。需要的话请用 handoff 交给用户。")
        val cap = capture()
        val same = try { cap.packageName == snap.packageName && cap.windows.lastOrNull()?.id == snap.mainWindowId } finally { cap.recycle() }
        // l10n:keep agent screen result
        if (!same) return ActionResult(false, "截图之后界面已经换了，没有点击。请重新看屏幕。", observeQuiet())
        val before = clock.count
        // l10n:keep agent screen result
        if (!actuator.tap(point.first, point.second)) return ActionResult(false, "系统没有接受这次点击。", observeQuiet())
        // l10n:keep agent screen result
        val track = "p|${snap.packageName}|xy:${a.x},${a.y}"
        val out = after(before, snap, gen, "已点击截图上的 (${a.x}, ${a.y})（0-1000 归一化坐标）。", verifyPixels = false)
        val verified = verifyPointTap(snap, out.message, track)
        // A tree page was already measured by [changeNote]; a canvas page only here.
        return out.copy(message = verified.note, change = verified.amount ?: out.change)
    }

    /**
     * Post-tap verification for point taps on canvas-like pages: did the picture actually change?
     *
     * A control tree answers this for free (the element list diffs, so [after] already says
     * 界面没有明显变化). A canvas page has no elements, so without this the model gets the same
     * "已点击" whether the tap landed on the button or on empty sky, and the field pattern repeats
     * the same coordinate until the budget runs out. So: screenshot before (kept on the snapshot)
     * and after, pixel-diff them, and when nothing changed hand the model the unit checklist
     * instead of silence.
     *
     * Best-effort by design: a screenshot that cannot be taken after the tap is not a failure, and
     * a screen that changed size (rotation, app launch) is not a failure either — both only cost
     * the verification, never the tap.
     *
     * Returns the note together with the measured diff, so the one signal the GUI channel really
     * has (did the picture move?) reaches the action log as well as the model.
     */
    private fun verifyPointTap(snap: Snapshot, message: String, track: String): Change {
        if (snap.screen.kind == ScreenKind.TREE) return Change(message, null)
        val before = snap.bytes?.let { ScreenShooter.thumbnail(it) } ?: return Change(message, null)
        val cap = try { capture() } catch (_: Exception) { return Change(message, null) }
        try {
            if (cap.screen.width != snap.display.width || cap.screen.height != snap.display.height) {
                // l10n:keep agent screen result
                return Change(message + "\n" + "注意：截图之后屏幕尺寸或方向变了（截图 ${snap.display.width}×${snap.display.height}，现在 ${cap.screen.width}×${cap.screen.height}）。0-1000 归一化坐标按比例仍然有效，但画面已经不是原来那张：如果是小程序 / 游戏启动造成的跳转，请重新 screen 看新画面再继续，不要因为坐标失效就结束任务。", null)
            }
            val window = if (cap.windows.size == 1) cap.windows[0] else null
            val shot = try { shooter.shoot(window, cap.screen, emptyList(), emptyList(), 1_500) } catch (_: Exception) { return Change(message, null) }
            val after = shot.bytes?.let { ScreenShooter.thumbnail(it) } ?: return Change(message, null)
            val change = PixelDiff.diff(before, after)
            if (change >= CHANGED) {
                clearMiss()
                return Change(message, change)
            }
            val hits = bumpMiss(track)
            val amount = String.format(java.util.Locale.ROOT, "%.2f", change)
            if (hits >= 2) {
                // l10n:keep agent screen result
                return Change(message + "\n" + "点击后画面几乎没有变化（差异 $amount），这已经是同一目标连续第 $hits 次没有反应：停止重复点击。改试相邻的目标，或 handoff 交给用户；不要继续猜坐标。", change)
            }
            // l10n:keep agent screen result
            return Change(message + "\n" + "点击后画面几乎没有变化（差异 $amount）。先核对你手上的坐标：tap_xy 的 x/y 是 0-1000 归一化坐标（0,0=截图左上角，1000,1000=截图右下角），不是像素、不是百分数，从图上量出来的像素要换算。再确认目标真的在这一屏：状态栏、输入法键盘下方、刚加载出来的空白页都可能让点击落空。同一位置第二次仍然没有变化就停手，改 handoff 交给用户。", change)
        } catch (_: Exception) {
            return Change(message, null)
        } finally {
            cap.recycle()
        }
    }

    /** Waits for the UI to settle, reads the new screen (`+` marks) and says whether anything changed. */
    private fun after(
        before: Long,
        previous: Snapshot?,
        gen: Long,
        message: String,
        scrolled: Boolean = false,
        track: String? = null,
        verifyPixels: Boolean = true,
    ): ActionResult {
        clock.settle(before, 1_500, 300, 3_000) { cancelled(gen) }
        val cap = try { settledCapture(previous, gen) } catch (e: InterruptedException) { throw e } catch (e: Exception) {
            Log.w(TAG, "observation after an accepted action failed", e)
            // l10n:keep agent screen result
            return ActionResult(true, "$message（动作已执行，但没能读到新屏幕。）")
        }
        try {
            val snap = publish(cap, false)
            val change = changeNote(previous, snap, scrolled, track, verifyPixels)
            return ActionResult(true, message + change.note, toSnapshot(snap), change.amount)
        } finally { cap.recycle() }

    }

    /**
     * Whether anything changed after an accepted action, as a note for the model.
     *
     * The control tree answers this for free: the element list *is* the fingerprint set, and a
     * page that did anything changes it. A canvas or blanked page has no elements at all, so both
     * sides of that comparison are the empty set and it always answers 界面没有明显变化 — including
     * after a tap that worked, and for `back` on a page whose pixels did change. On those pages
     * pixels are the only evidence, so compare thumbnails ([pixelChange]); when no verdict is
     * possible (no before-shot, screenshot refused, size changed) say nothing rather than invent
     * one.
     *
     * [track] names the target the model aimed at. It survives `screen` calls, so a target that
     * keeps not reacting is stopped after the second miss instead of being retried until the
     * budget runs out; a change, a different target or a different app clears the count.
     *
     * [verifyPixels] is false when the caller verifies the pixels itself ([verifyPointTap]): then
     * no second screenshot is taken here, and an element-less page stays silent rather than
     * getting a second, contradicting verdict.
     */
    private fun changeNote(previous: Snapshot?, snap: Snapshot, scrolled: Boolean, track: String?, verifyPixels: Boolean): Change {
        if (previous == null || previous.packageName != snap.packageName) {
            clearMiss()
            return Change("", null)
        }
        val noElements = snap.screen.elements.isEmpty() && previous.screen.elements.isEmpty()
        if (noElements && !verifyPixels) return Change("", null)
        val amount = when {
            !noElements -> fingerprintDistance(previous.fingerprints, snap.fingerprints)
            else -> pixelChange(previous, snap) ?: return Change("", null)
        }
        if (amount > 0f) {
            clearMiss()
            return Change("", amount)
        }
        if (track != null) {
            val hits = bumpMiss(track)
            if (hits >= 2) {
                // l10n:keep agent screen result
                return Change(
                    "这已经是同一目标连续第 $hits 次没有反应：停止重复点击。改试相邻的目标，或 handoff 交给用户；不要继续对着同一个目标点。",
                    amount,
                )
            }
            // l10n:keep agent screen result
            return Change(
                "界面没有明显变化。先确认这一下点对了地方：状态栏、输入法键盘下方、刚加载出来的空白页和页面边缘的装饰都容易让点击落空。同一目标第二次仍然没有变化就停手，改 handoff 交给用户。",
                amount,
            )
        }
        // l10n:keep agent screen result
        return Change(if (scrolled) "内容没有变化，可能已经到头了。" else "界面没有明显变化。", amount)
    }

    /** What an action did to the screen, as reported to the model ([note]) and to the log ([amount]). */
    private class Change(val note: String, val amount: Float?)

    /**
     * Pixel verdict for a page with no elements (canvas, blanked): screenshot now and diff against
     * the thumbnail kept on [previous]. Null when no verdict is possible — no before-shot, the
     * screenshot was refused, or the screen changed size — which costs the verdict, never the
     * action. The fresh pixels are kept on [snap] so the next action on this page can be verified
     * the same way.
     */
    private fun pixelChange(previous: Snapshot, snap: Snapshot): Float? {
        val before = previous.bytes?.let { ScreenShooter.thumbnail(it) } ?: return null
        val cap = try { capture() } catch (e: Exception) { return null }
        try {
            if (cap.packageName != snap.packageName) return null
            if (cap.screen.width != snap.display.width || cap.screen.height != snap.display.height) return null
            val window = if (cap.windows.size == 1) cap.windows[0] else null
            val shot = try { shooter.shoot(window, cap.screen, emptyList(), emptyList(), 1_500) } catch (e: Exception) { return null }
            val after = shot.bytes?.let { ScreenShooter.thumbnail(it) } ?: return null
            snap.bytes = shot.bytes
            return PixelDiff.diff(before, after)
        } finally {
            cap.recycle()
        }
    }

    /**
     * How far two element sets are apart, 0f..1f (Jaccard distance): the fraction of the union of
     * fingerprints that is not shared. Zero means the same controls are still there, which is what
     * makes the log's `change` column readable across both channels — pixels on a canvas-like page,
     * this on a tree page.
     */
    private fun fingerprintDistance(before: Set<String>, after: Set<String>): Float {
        if (before.isEmpty() && after.isEmpty()) return 0f
        val shared = before.count { it in after }
        val union = before.size + after.size - shared
        return if (union == 0) 0f else (union - shared).toFloat() / union
    }

    /** Records a no-change hit on [key] (app + target); returns how many in a row. */
    private fun bumpMiss(key: String): Int = synchronized(missLock) {
        if (missKey != key) {
            missKey = key
            missCount = 0
        }
        missCount++
        missCount
    }

    private fun clearMiss() = synchronized(missLock) {
        missKey = null
        missCount = 0
    }

    /**
     * A capture after an action. Right after a transition the new window may have no root yet
     * or an empty tree (Chrome, Settings sub-pages): retry a few times while that lasts.
     */
    private fun settledCapture(previous: Snapshot?, gen: Long): Capture {
        var cap = capture()
        var tries = 0
        while (tries++ < 4 && !cancelled(gen) && looksUnfinished(cap, previous)) {
            cap.recycle()
            clock.pause(400) { cancelled(gen) }
            cap = capture()
        }
        return cap
    }

    /**
     * True while the screen we just read may still be settling (no root yet, or a nearly empty
     * tree right after a transition). A canvas or blanked page is never unfinished this way: it
     * has no text to wait for, and retrying it four times only burns the action budget on a page
     * that will read exactly the same.
     */
    private fun looksUnfinished(cap: Capture, previous: Snapshot?): Boolean {
        if (cap.hiddenWindowTitle != null) return false
        if (ScreenFormat.classify(cap.windows, false) != ScreenKind.TREE) return false
        return cap.windows.isEmpty() ||
            (ScreenFormat.textCount(cap.windows) < ScreenFormat.POOR_TREE && previous?.screen?.poorTree != true)
    }

    private fun settledSnapshot(before: Long, gen: Long): ScreenSnapshot? {
        clock.settle(before, 800, 250, 2_000) { cancelled(gen) }
        return observeQuiet()
    }

    /** Polls the field until it shows [expected] (or 1.5 s pass); returns what it shows. */
    private fun readBack(node: AccessibilityNodeInfo, expected: String, gen: Long): String? {
        val deadline = SystemClock.uptimeMillis() + 1_500
        var got: String? = null
        while (true) {
            node.refresh()
            got = if (node.isShowingHintTextCompat()) "" else node.text?.toString()
            if (sameText(got, expected) || SystemClock.uptimeMillis() > deadline || cancelled(gen)) return got
            clock.pause(100) { cancelled(gen) }
        }
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat() = android.os.Build.VERSION.SDK_INT >= 26 && isShowingHintText

    /** Equal, or equal once the field's own formatting (spaces, dashes) is ignored. */
    private fun sameText(got: String?, expected: String): Boolean {
        if (got == expected) return true
        if (got == null) return false
        fun norm(s: String) = s.filterNot { it.isWhitespace() || it == '-' || it == '​' }
        return norm(got) == norm(expected)
    }

    private fun awaitInputIdle(): Boolean {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (overlay.editing) {
            if (SystemClock.uptimeMillis() > deadline) return false
            clock.pause(150)
        }
        return true
    }

    // ---- snapshots --------------------------------------------------------------------------------

    private fun capture(): Capture = TreeCapture.capture(service, screenBox(), ownPackage, CAPTURE_MS)

    private fun locate(cap: Capture, el: ScreenElement): IndexedElement? =
        ScreenFormat.index(cap.windows).firstOrNull { it.fingerprint == el.fingerprint }

    /** Formats [cap] as the next snapshot (`s<n>`) and makes it the latest. */
    private fun publish(cap: Capture, detail: Boolean): Snapshot {
        val id = "${PhoneScreen.MAIN.prefix}${counter.incrementAndGet()}"
        val pkg = cap.packageName
        val appLabel = label(pkg)
        val titles = cap.windows.mapNotNull { it.title } + ProtectedPages.titleTexts(cap.windows.firstOrNull()?.root, cap.screen.height)
        val protectedPage = ProtectedPages.isProtected(pkg, titles)
        val notes = mutableListOf<String>()
        val lifted = lifted
        // l10n:keep agent screen result
        if (cap.ownAppInFront && !lifted.ownApp) notes += "提示：这是 DSH 自己的界面，DSH 不操作它。"
        // l10n:keep agent screen result
        if (protectedPage && !lifted.systemPages) notes += "提示：这是系统权限 / 安全相关页面，DSH 只看不操作；需要的话用 handoff 交给用户。"
        // l10n:keep agent screen result
        if (cap.truncated) notes += "提示：页面元素太多，只读了一部分。"
        if (cap.hiddenWindowTitle != null) {
            val name = cap.hiddenWindowTitle.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
            // l10n:keep agent screen result
            notes += "提示：前台页面${name}不向 DSH 开放内容（应用把它标成了敏感页面，常见于密码、支付、Wi‑Fi 等）。DSH 读不到也点不了，需要的话请用 handoff 交给用户。"
        // l10n:keep agent screen result
        } else if (cap.windows.isEmpty()) notes += "提示：读不到前台界面（可能是锁屏、安全页面或系统界面）。"
        val previous = latest?.takeIf { it.packageName == pkg }?.screen?.keys
        fun format(notes: List<String>) = ScreenFormat.format(
            ScreenInput(
                screenId = id, appLabel = appLabel, packageName = pkg,
                screenWidth = cap.screen.width, screenHeight = cap.screen.height, keyboardShown = cap.keyboardShown,
                windows = cap.windows, detail = detail, previousKeys = previous, notes = notes,
                hidden = cap.hiddenWindowTitle != null,
            ),
        )
        var formatted = format(notes)
        // Such inputs still read 「不可代填」 (their fingerprints must not change with the setting):
        // say once, on the screens that have one, that the person allowed filling them.
        if (lifted.secretFields && formatted.elements.any { it.password || it.secret }) {
            // l10n:keep agent screen result
            formatted = format(notes + "提示：用户已允许 DSH 填写密码和验证码，标着「不可代填」的输入框也可以输入。")
        }
        val snap = Snapshot(id, formatted, pkg, appLabel, protectedPage, cap.ownAppInFront, cap.windows.lastOrNull()?.id, cap.hiddenWindowTitle != null, cap.screen)
        latest = snap
        return snap
    }

    private fun toSnapshot(s: Snapshot) = ScreenSnapshot(s.id, s.screen.text, s.appLabel, s.packageName, secure = s.hidden, poorTree = s.screen.poorTree, kind = s.screen.kind)

    /** A fresh text snapshot for an action result; null when the screen can't be read now. */
    private fun observeQuiet(): ScreenSnapshot? {
        if (!availability().ok) return null
        return try {
            val cap = capture()
            try { toSnapshot(publish(cap, false)) } finally { cap.recycle() }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "observe failed", e)
            null
        }
    }

    // l10n:keep agent screen result
    private fun blank(reason: String) = ScreenSnapshot("${PhoneScreen.MAIN.prefix}0", "屏幕读不到：$reason", null, null)

    private fun screenBox(): Box {
        val p = Point()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        return Box(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
    }

    private fun label(pkg: String?): String? {
        if (pkg == null) return null
        labels[pkg]?.let { return it }
        val name = try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { return null }
        labels[pkg] = name
        return name
    }

    private fun cancelled(gen: Long) = abortGen != gen || Thread.currentThread().isInterrupted

    private fun short(s: String, max: Int = 24) = ScreenFormat.clip(s, max)

    companion object {
        private const val TAG = "DshA11y"

        /**
         * A `type` whose read-back differs from what was typed. An ordinary input says what it
         * holds now; a password or code field ([SafetyRules.isSecretTarget], as for the log's ••••)
         * never does: the tool result goes to the model.
         */
        internal fun readBackMismatch(got: String?, target: TargetInfo): String {
            // l10n:keep agent screen result
            val now = if (SafetyRules.isSecretTarget(target)) "这是密码 / 验证码框，框里的内容不回传"
            // l10n:keep agent screen result
            else "框里现在是" + ScreenMask.mask(got.orEmpty()).let { if (it.isEmpty()) "空" else "「${ScreenFormat.clip(it, 40)}」" }
            // l10n:keep agent screen result
            return "已输入，但回读不一致：$now。没有重试，也没有提交；请看新屏幕决定下一步。"
        }

        /** Process-wide: a reconnected service must never hand out an id the model still holds. */
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        /** ActivityOptions key for the windowing mode a launch should use (hidden from the SDK). */
        private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
        /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN. */
        private const val WINDOWING_MODE_FULLSCREEN = 1
        private const val OBSERVE_MS = 4_800L
        private const val CAPTURE_MS = 2_000L
        /**
         * Mean pixel change below this counts as "the tap did nothing" (a scroll or a page open
         * changes far more; a tap that only moves a focus ring changes less).
         */
        private const val CHANGED = 0.05f
        private const val COLLECT_MS = 100_000L
        private const val COLLECT_CHARS = 60_000
    }
}
