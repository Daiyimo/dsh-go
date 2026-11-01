package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenGeom
import java.time.ZoneId

enum class A11yState { CONNECTED, ENABLED_NOT_CONNECTED, DISABLED }

/** What the core needs from the device; [PhoneControl] on Android, a fake in the JVM tests. */
interface PhoneEnv {
    /** The master switch. */
    fun enabled(): Boolean
    fun policies(): PhonePolicies
    fun isLocked(): Boolean
    fun gate(): UserGate?
    fun driver(screen: PhoneScreen): PhoneDriver?
    fun a11yState(): A11yState
    /** Why the background display can't be used, as a sentence for the model. */
    fun backgroundUnavailable(): String
    fun system(): SystemApi
    fun uiText(): PhoneUiText = PhoneUiText.DEFAULT
    fun zone(): ZoneId
    /** Tells the person what to fix (accessibility off, …); at most once per task and kind. */
    fun notifyFix(kind: String)
    /**
     * Before `handoff` while the agent works on [screen]: for the background display, release it
     * without destroying its content so the app comes to the main screen. A sentence for the model, or null.
     */
    fun beforeHandoff(screen: PhoneScreen): String? = null
    /**
     * A pure-GUI task never looks at the virtual screen: release it (its app comes back to the
     * main screen) rather than leave a display up that this task will not use.
     */
    fun closeBackground() {}
    /**
     * The foreground package, without bringing anything up: the keeper may hold a call to
     * [driver] for seconds waiting for 自动恢复无障碍, and every tool call asks this one.
     */
    fun peekPackage(): String? = driver(PhoneScreen.MAIN)?.foregroundPackage()
    /** The apps the person listed as pure GUI (设置 › 手机操控); shipped default: 微信. */
    fun guiScheme(): Set<String> = PhoneCore.DEFAULT_GUI_SCHEME
    /**
     * The launcher, i.e. the home screen. Going home in the middle of a task is not the task
     * moving on to another app: the app under the icons is still the one being driven.
     */
    fun homePackages(): Set<String> = emptySet()
    /** One line in the device log for the decisions a support report cannot otherwise see. */
    fun log(message: String) {}
    /**
     * 全屏打开应用 (设置 › 手机操控 › 进阶, on by default): open an app through the accessibility
     * service, which can ask for the fullscreen windowing mode, instead of a plain start from this
     * process that a ROM may put in a small window. Off leaves the way the system chooses.
     */
    fun launchFullscreen(): Boolean = true
    /** The focused field is a password / code field: DSH never types there. */
    fun imePassword(): Boolean = false
    /**
     * A keyboard is on screen right now, i.e. a field already has the focus. Tapping that field
     * again is what closes the keyboard and takes the focus away, so the model is told not to.
     */
    fun keyboardUp(): Boolean = false

    /**
     * Gives the app's task back the fullscreen windowing mode through the privileged channel.
     * A start made from here is a background one, and this ROM answers those with a mini window.
     */
    fun forceFullscreen(packageName: String): Boolean = false
    /**
     * Text into a page that has no field ids at all (微信, games): `input text` over the shell-uid
     * engine for ASCII, else borrowing the person's own input method for one commit and putting it
     * back (0·A, see [io.github.ccch1mneyyy.dsh_app.phone.input.TextInjector]). Null on success,
     * else the sentence the model gets.
     */
    fun typeIntoPage(text: String, submit: Boolean): String? =
        // l10n:keep agent tool result
        "这个页面没有输入框编号，文字要经特权通道送进去：先在 设置 › 手机操控 › 进阶 开通 不打扰模式 或 自连 ADB。"
    fun sleep(ms: Long) = Thread.sleep(ms)
    /**
     * Called on every tool call: keeps this ROM's freezer from stopping the app mid-task (a frozen
     * process answers the next call tens of seconds late). See [KeepAwake].
     */
    fun keepAwake() {}
    /** This app's own package, so a task can tell "the person came back to DSH" from another app. */
    fun ownPackage(): String = ""
}

/**
 * Runs the fixed tool set: argument checks, screen-id routing (`s` main / `b` background,
 * never falling back across screens), the safety rules, one serial worker with per-call budgets,
 * the post-action screen, and the action log. [call] is what the MCP server invokes.
 */
class PhoneCore(
    private val env: PhoneEnv,
    val tasks: PhoneTasks,
    val log: ActionLog,
    val worker: SerialWorker = SerialWorker("dsh-phone"),
) {
    private val ui: PhoneUiText get() = env.uiText()
    val safety = SafetyGate(env::policies, tasks, env::gate, uiText = env.uiText())

    /** The overlay's status line; 免确认 stays visible the whole time nothing is asked. */
    private fun say(text: String) {
        if (pureGui()) {
            retractLine(text)
            return
        }
        lineUp = true
        env.log("status line: $text (waived=${safety.waived()})")
        env.gate()?.status(if (safety.waived()) ui.get("phone_waived_status", "免确认 · %1\$s", text) else text) // l10n:keep test default
    }

    /** Whether the overlay is showing a line right now (only ever drawn by [say]). */
    @Volatile private var lineUp = false

    /**
     * A line drawn before the task turned pure GUI must go — the person sees it once the app the
     * task drives comes to the front, and it sits in the picture the GUI is matching against.
     * Once per line: a pure-GUI task draws nothing, so a second retraction has nothing to do.
     */
    private fun retractLine(why: String) {
        if (!lineUp) return
        lineUp = false
        env.log("status line retracted ($why)")
        runCatching { env.gate()?.status(null) }
    }

    /**
     * The app this task has to be driven by picture instead of by tree: a game ([ScreenKind.CANVAS])
     * or a page that hides its tree ([ScreenKind.BLANKED]). Cleared when another app comes to the
     * front, so one game does not turn every later task into a pure-GUI one.
     */
    @Volatile private var pictureApp: String? = null

    /** Label of [pictureApp], for the sentences the model gets back. */
    @Volatile private var pictureLabel: String? = null

    /** The task [pictureApp] belongs to: the next task starts from its own picture, not this one. */
    @Volatile private var pictureTask = 0L

    /** The virtual screen has already been taken down for this task (once per pure-GUI run). */
    @Volatile private var pureGuiEntered = false

    /**
     * The listed app (微信) this task is quiet about, and the task it belongs to. Kept so a page
     * that belongs to the system — a permission sheet, the shade, our own confirm card — does not
     * draw the line back over the app underneath it: the app is still the one being driven.
     */
    @Volatile private var schemeApp: String? = null
    @Volatile private var schemeTask = 0L

    /**
     * True while the task belongs to an app that is driven pure GUI: 微信 (an outside GUI scheme
     * reads and drives it on its own) and anything that draws its own picture. Those tasks stay on
     * the one fullscreen app — the virtual screen is refused — and nothing is drawn over it: the
     * line lands inside the picture the GUI is matching its targets against, and a line showing up
     * mid-flow moves every target it looks for.
     */
    private fun pureGui(): Boolean {
        val pkg = mainPackage()
        val task = tasks.snapshot().id
        if (pkg != null && pkg in env.guiScheme()) {
            schemeApp = pkg
            schemeTask = task
            return true
        }
        val scheme = schemeApp
        if (scheme != null) {
            if (task == schemeTask) {
                if (neutral(pkg)) return true
            } else if (pkg == scheme) {
                // The task boundary was only the idle clock, or the engine's turn end: the listed
                // app is still in front, so the task is still the listed app's task.
                schemeTask = task
                env.log("pure gui: $scheme is still in front, keeping the quiet task")
                return true
            } else {
                schemeApp = null
                schemeTask = 0L
            }
        }
        val latched = pictureApp ?: return false
        // A latch is that task's picture. A new task that opened another app must not inherit it —
        // but a task boundary while the same app is still in front is only the idle clock expiring
        // between two calls, and dropping the latch there re-opens the virtual screen mid-run.
        if (task != pictureTask) {
            if (pkg != null && pkg != UNKNOWN_PACKAGE && pkg == latched) {
                pictureTask = task
                env.log("pure gui: $latched is still in front, keeping the picture latch")
            } else {
                clearLatch()
                return false
            }
        }
        return neutral(pkg) || pkg == latched
    }

    /**
     * A surface that is not the task moving on to another app: nobody can name the screen at all,
     * a system page (a permission sheet, the shade, our own card), or the home screen the model
     * just pressed home to open the app from.
     */
    private fun neutral(pkg: String?): Boolean =
        pkg == null || SafetyRules.isSystemPackage(pkg) || pkg in env.homePackages()

    private fun clearLatch() {
        if (pictureApp != null || schemeApp != null) env.log("pure gui off: another app took the front")
        pictureApp = null
        pictureLabel = null
        pictureTask = 0L
        pureGuiEntered = false
        schemeApp = null
        schemeTask = 0L
    }

    private fun mainPackage(): String? = try {
        env.peekPackage()
    } catch (_: Throwable) {
        null
    }

    /**
     * A pure-GUI task starts here: the virtual screen goes, and so does a line already drawn.
     *
     * [pkg] is null when the page hides which app it belongs to. The latch is written anyway: the
     * display has just been taken down, and without a latch the next call would be allowed to bring
     * it straight back up.
     */
    private fun enterPureGui(pkg: String?, label: String? = null) {
        pictureApp = pkg ?: UNKNOWN_PACKAGE
        if (pkg != null && pkg in env.guiScheme()) {
            schemeApp = pkg
            schemeTask = tasks.snapshot().id
        }
        if (label != null) pictureLabel = label
        pictureTask = tasks.snapshot().id
        env.log("pure gui on: ${pkg ?: "unknown page"} (task $pictureTask)")
        if (!pureGuiEntered) {
            pureGuiEntered = true
            lastScreen = PhoneScreen.MAIN
            runCatching { env.closeBackground() }
        }
        // Every call, not just the first: a line can be drawn again while the task walks there
        // (home screen, the launcher, another app's icon) and would then sit over the app.
        retractLine("pure gui: ${pkg ?: "unknown page"}")
    }

    /** Remembers a main-screen picture after the fact, so every later call in the task knows. */
    private fun rememberPicture(screen: PhoneScreen, snap: ScreenSnapshot) {
        if (screen != PhoneScreen.MAIN) return
        val pkg = snap.packageName
        val scheme = pkg != null && pkg in env.guiScheme()
        if (snap.kind == ScreenKind.CANVAS || snap.kind == ScreenKind.BLANKED || scheme) {
            enterPureGui(pkg, snap.appLabel)
        } else if (pkg != null && pkg != pictureApp && !neutral(pkg)) {
            // Another app in front ends the picture; a system page or the home screen on top of it
            // does not — it is the permission sheet, the shade, or the launcher the model just
            // pressed home to, and the app under it is still the one being worked on.
            clearLatch()
        }
    }

    /**
     * The one line a pure-GUI task carries on every main-screen read, so the model does not drift.
     *
     * The wording follows the page: 微信 reads as a tree and its writes belong on the element
     * numbers, while only a page with no numbers is tapped by picture coordinates. Telling both to
     * use `tap_xy` sent the model to coordinates on pages that refuse them.
     */
    private fun pureGuiHint(appLabel: String?, kind: ScreenKind): String =
        // l10n:keep agent tool result
        if (kind == ScreenKind.CANVAS || kind == ScreenKind.BLANKED)
            "「${appLabel ?: pictureLabel ?: "这个 App"}」走纯 GUI：不要用 background，用 tap_xy 点截图上的位置。"
        else
            // l10n:keep agent tool result
            "「${appLabel ?: pictureLabel ?: "这个 App"}」走纯 GUI：不要用 background，用 tap / scroll 点元素编号。"

    /** What the model is told when it asks for the virtual screen inside a pure-GUI task. */
    private fun pureGuiSentence(appLabel: String?): String =
        // l10n:keep agent tool result
        "「${appLabel ?: "这个 App"}」走纯 GUI：只在全屏 App 里操作，不用后台屏（虚拟屏）。不带 background 打开应用，用 screen 看屏幕：能读到控件编号就点编号，只有画面的页面用 tap_xy。"

    /** The screen of the last screen-based call, for `handoff`. */
    @Volatile private var lastScreen: PhoneScreen = PhoneScreen.MAIN

    /**
     * The task's last hard refusal (密码框, 系统设置, 不碰名单), for the `handoff` that usually
     * follows it: the card says which rule it was. Cleared by a write that went through, a new
     * task, or the handoff it named.
     */
    @Volatile private var refusal: Pair<PhoneTasks.Owner, HandoffCause>? = null

    private class Out(
        val text: String,
        val error: Boolean = false,
        val screen: ScreenSnapshot? = null,
        val app: String? = null,
        val summary: String? = null,
        val decision: String? = null,
        val change: Float? = null,
    )

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        // In flight from here until the result goes back: no turn ends while a call runs (PhoneTasks.finish).
        tasks.callStarted()
        try {
            return runCall(name, args)
        } finally {
            tasks.callEnded()
        }
    }

    private fun runCall(name: String, args: Map<String, Any?>): ToolOutput {
        if (name !in PhoneToolCatalog.names) {
            // l10n:keep agent tool result
            return ToolOutput("没有这个手机工具：$name。可用：${PhoneToolCatalog.names.joinToString("、")}", isError = true)
        }
        if (!env.enabled()) {
            // l10n:keep agent tool result
            return ToolOutput("手机操控已在 DSH 设置里关闭。请用户在 设置 → 手机操控 里打开后重启引擎。", isError = true)
        }
        // The task is alive from here on: keep the system's freezer off the app (it stops on its own
        // when the calls stop coming).
        runCatching { env.keepAwake() }
        val outcome = worker.run(budgetMs(name), MAX_TOTAL_MS) { scope -> execute(name, Args(args), scope) }
        tasks.touch()
        val out = when (outcome) {
            is SerialWorker.Outcome.Done -> outcome.value
            is SerialWorker.Outcome.TimedOut -> {
                val text = if (outcome.queued) {
                    // l10n:keep agent tool result
                    "前面的手机操作还没结束，这一步没有执行。稍后再试。"
                } else {
                    // l10n:keep agent tool result
                    "这一步超时了，可能执行了也可能没有。不要自动重试同一个动作：先用 screen 看看现在的屏幕。"
                }
                Out(text, error = true, summary = name, decision = "timeout")
            }
            SerialWorker.Outcome.Interrupted -> Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = name, decision = "stopped")
            is SerialWorker.Outcome.Failed -> Out(
                // l10n:keep agent tool result
                "手机操作出错：${outcome.error.javaClass.simpleName}${outcome.error.message?.let { "：$it" } ?: ""}",
                error = true,
                summary = name,
                decision = "error",
            )
        }
        out.summary?.let { log.add(name, out.app, it, out.decision ?: if (out.error) "error" else "ok", out.change) }
        env.log(
            "tool $name ${if (out.error) "error" else "ok"}" +
                (out.decision?.let { " decision=$it" } ?: "") +
                (out.app?.let { " app=$it" } ?: "") +
                (out.summary?.let { " at=$it" } ?: "") +
                " task=${tasks.snapshot().id}",
        )
        noteRefusal(name, out.decision)
        val interjections = tasks.drainInterjections()
        val text = buildString {
            // l10n:keep agent tool result
            for (line in interjections) append("用户插话：「").append(line).append("」\n\n")
            append(out.text)
            out.screen?.let { if (out.text.isNotEmpty()) append("\n\n"); append(it.text) }
        }
        val image = out.screen?.image
        return ToolOutput(text, out.error, image, if (image != null) out.screen.imageMime else null)
    }

    private fun noteRefusal(tool: String, decision: String?) {
        val owner = tasks.activeOwner() ?: run { refusal = null; return }
        val cause = HandoffCause.ofDecision(decision)
        if (cause != null) {
            refusal = owner to cause
        } else if (tool in WRITES && decision in DONE) {
            refusal = null
        }
    }

    private fun execute(name: String, a: Args, scope: SerialWorker.Scope): Out {
        when (val b = tasks.begin()) {
            is PhoneTasks.Begin.Refused -> return Out(b.message + "。", error = true, summary = name, decision = "stopped")
            is PhoneTasks.Begin.Go -> Unit
        }
        if (tasks.isPaused) {
            val go = scope.userWait { tasks.awaitResume(PAUSE_WAIT_MS) }
            if (!go) {
                // Stopped, or the task ended under the wait (the engine went away): never go on.
                return if (tasks.isStopped() || !tasks.isActive) {
                    Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = name, decision = "stopped")
                } else {
                    // l10n:keep agent tool result
                    Out("用户暂停了手机操作，超过 10 分钟没有恢复。停下来，告诉用户可以恢复后再继续。", error = true, summary = name, decision = "paused")
                }
            }
        }
        return when (name) {
            PhoneToolCatalog.APPS -> apps(a)
            PhoneToolCatalog.OPEN -> open(a, scope)
            PhoneToolCatalog.INTENT -> intent(a)
            PhoneToolCatalog.NOTIFY -> notify(a)
            PhoneToolCatalog.CLIPBOARD -> clipboard(a)
            PhoneToolCatalog.SCREEN -> screen(a)
            PhoneToolCatalog.COLLECT -> collect(a)
            PhoneToolCatalog.TAP, PhoneToolCatalog.TYPE, PhoneToolCatalog.SCROLL, PhoneToolCatalog.TAP_XY -> act(name, a, scope)
            PhoneToolCatalog.KEY -> key(a)
            PhoneToolCatalog.WAIT -> wait(a, scope)
            PhoneToolCatalog.HANDOFF -> handoff(a, scope)
            // l10n:keep agent tool result
            else -> Out("没有这个手机工具：$name", error = true)
        }
    }

    // ── System actions ──────────────────────────────────────────────────────────────

    private fun apps(a: Args): Out {
        val q = a.str("query")
        val all = env.system().apps().filter { it.packageName != SafetyRules.OWN_PACKAGE }.sortedBy { it.label }
        val hits = AppMatch.filter(all, q)
        if (hits.isEmpty()) return Out(if (q == null) "没读到应用列表。" else "没有找到名称或包名含「$q」的应用。", summary = ui.get("phone_log_apps", "列出应用")) // l10n:keep test default
        val shown = hits.take(MAX_APPS)
        val text = buildString {
            // l10n:keep agent tool result
            append(if (q == null) "共 ${hits.size} 个应用" else "匹配「$q」的应用 ${hits.size} 个").append("（名称 · 包名）：\n")
            shown.forEach { append(it.label).append(" · ").append(it.packageName).append('\n') }
            // l10n:keep agent tool result
            if (hits.size > shown.size) append("…另有 ${hits.size - shown.size} 个，用 query 缩小范围\n")
        }.trimEnd()
        return Out(text, summary = if (q == null) ui.get("phone_log_apps", "列出应用") else ui.get("phone_log_apps_filtered", "列出应用（筛选）")) // l10n:keep test default
    }

    private fun resolveApp(query: String): Either<String, AppEntry> =
        when (val r = AppMatch.resolve(env.system().apps(), query)) {
            is AppMatch.Result.Found -> Either.Right(r.app)
            is AppMatch.Result.Ambiguous -> Either.Left(
                // l10n:keep agent tool result
                "「$query」对应多个应用，请用包名指定：" + r.candidates.joinToString("；") { "${it.label} · ${it.packageName}" },
            )
            // l10n:keep agent tool result
            AppMatch.Result.NotFound -> Either.Left("没找到应用「$query」。用 apps 查一下名称或包名。")
        }

    private fun open(a: Args, scope: SerialWorker.Scope): Out {
        val owner = tasks.activeOwner() ?: return stoppedOut("open")
        val appQ = a.str("app")
        val url = a.str("url")
        val background = a.bool("background") == true
        // l10n:keep agent tool result
        if ((appQ == null) == (url == null)) return Out("open 需要 app 或 url，二选一。", error = true)
        if (env.isLocked()) return locked("open")
        if (url != null) {
            // l10n:keep agent tool result
            if (background) return Out("后台屏只能用 app 打开应用，不支持链接。", error = true)
            UrlPolicy.check(url)?.let { return Out(it, error = true, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:url") } // l10n:keep test default
            val target = env.system().urlTarget(url)
            target.error?.let { return Out(it, error = true, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:url") } // l10n:keep test default
            target.packageName?.let { pkg ->
                safety.checkOpen(pkg, target.appLabel)?.let { return Out(it.message, error = true, app = target.appLabel ?: pkg, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:${it.rule}") } // l10n:keep test default
            }
            val r = env.system().openUrl(url, target)
            return afterSystem(r, ui.get("phone_log_open_url_scheme", "打开链接 %1\$s:", url.substringBefore(':')), target.appLabel ?: target.packageName) // l10n:keep test default
        }
        val app = when (val r = resolveApp(appQ!!)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        safety.checkOpen(app.packageName, app.label)?.let {
            return Out(it.message, error = true, app = app.label, summary = ui.get("phone_log_open_app", "打开 %1\$s", app.packageName), decision = "refused:${it.rule}") // l10n:keep test default
        }
        val schemeApp = app.packageName in env.guiScheme() || pureGui()
        env.log(
            "open ${app.packageName} background=$background scheme=$schemeApp " +
                "listed=${env.guiScheme().joinToString(",")} fullscreen=${env.launchFullscreen()} a11y=${env.a11yState()}",
        )
        if (app.packageName in env.guiScheme()) {
            // A virtual screen from an earlier task may still show this app. Take it down before
            // the first launch: the task runs in one fullscreen app, and leaving the display up
            // means the person sees 微信 appear on it and vanish again when the first read
            // latches pure GUI. enterPureGui closes it once for the whole task.
            enterPureGui(app.packageName, app.label)
        } else if (pureGui()) {
            // A picture page latched the task already; a display left over from before still goes.
            runCatching { env.closeBackground() }
        }
        if (!background) {
            // 全屏打开应用: the accessibility service may ask for the fullscreen windowing mode,
            // this process may not (a background start from an app lands wherever the ROM puts it —
            // on some it is a small floating window with the app under SystemUI decorations, which
            // is not where taps and screenshots are meant to run). A driver that is not there, or
            // a start that fails, falls through to the plain way: the person still gets the app.
            // Only when the service is bound: asking for the driver otherwise waits for
            // 自动恢复无障碍 to bring it back, which is seconds spent on a start the system can do.
            if (env.launchFullscreen() && env.a11yState() == A11yState.CONNECTED) {
                val driver = runCatching { env.driver(PhoneScreen.MAIN) }.getOrNull()
                if (driver != null && driver.availability().ok) {
                    val r = driver.open(app.packageName)
                    env.log("open ${app.packageName} through the service: ok=${r.ok} kind=${r.screen?.kind}")
                    if (r.ok) {
                        tasks.setApp(app.label)
                        lastScreen = PhoneScreen.MAIN
                        // A start made from here is a background one, and this ROM answers those
                        // with a small floating window (measured: `WindowMode:mini … fullscreen ->
                        // stepos-mini-window` two milliseconds after the start). The shell can put
                        // the task back (`am start --windowingMode 1`), so ask for that as well — a
                        // task the person has to open by hand is not what 全屏打开应用 promises.
                        if (env.launchFullscreen()) {
                            val full = runCatching { env.forceFullscreen(app.packageName) }.getOrDefault(false)
                            env.log("fullscreen after open ${app.packageName}: ok=$full")
                        }
                        // The start already settled on its own page: that page is the task's
                        // picture from here, exactly as if it had been read.
                        r.screen?.let { rememberPicture(PhoneScreen.MAIN, it) }
                        return Out(
                            r.message,
                            app = r.screen?.appLabel ?: app.label,
                            screen = r.screen?.let { safety.filterScreen(it) },
                            summary = ui.get("phone_log_open_app", "打开 %1\$s", app.packageName), // l10n:keep test default
                            decision = "ok",
                        )
                    }
                }
            }
            env.log("open ${app.packageName} left to the system")
            val launched = afterSystem(env.system().launch(app), ui.get("phone_log_open_app", "打开 %1\$s", app.packageName), app.label) // l10n:keep test default
            // The repair goes through the privileged channel, not through the accessibility
            // service, so a bound service is not a condition for asking for it: a task that came up
            // in the ROM's small window is unworkable however the start happened.
            if (env.launchFullscreen()) {
                val full = runCatching { env.forceFullscreen(app.packageName) }.getOrDefault(false)
                env.log("fullscreen after system launch ${app.packageName}: ok=$full")
            }
            return launched
        }
        if (schemeApp) {
            env.log("refused the back screen for ${app.packageName}: pure gui")
            return Out(pureGuiSentence(app.label), error = true, app = app.label,
                summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = "refused:pure-gui") // l10n:keep test default
        }
        val driver = env.driver(PhoneScreen.BACKGROUND)
            ?: return Out(env.backgroundUnavailable(), error = true, app = app.label, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = "unavailable") // l10n:keep test default
        driver.availability().let { if (!it.ok) return Out(it.reason ?: "后台屏现在不可用。", error = true, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = "unavailable") } // l10n:keep test default
        val mainFront = runCatching { env.driver(PhoneScreen.MAIN)?.foregroundPackage() }.getOrNull()
        var decision = "allowed"
        if (mainFront == app.packageName) {
            val v = safety.confirmation(ui.get("phone_move_background", "这会把「%1\$s」从你的屏幕挪到后台屏，让 DSH 在那里操作。", app.label), app.packageName, app.label, "move-to-background") // l10n:keep test default
            when (val r = safety.resolve(v, scope)) {
                is SafetyGate.Result.No -> return Out(r.message, error = true, app = app.label, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = r.decision) // l10n:keep test default
                is SafetyGate.Result.Go -> decision = r.decision
            }
        }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut("open")
        lastScreen = PhoneScreen.BACKGROUND
        say(ui.get("phone_opening_background", "DSH 正在后台屏打开「%1\$s」", app.label)) // l10n:keep test default
        val res = driver.open(app.packageName)
        tasks.setApp(app.label)
        return Out(res.message, error = !res.ok, screen = res.screen?.let { safety.filterScreen(it) }, app = app.label,
            summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = if (res.ok) decision else "failed") // l10n:keep test default
    }

    /** A system action that brought up another app's UI: attach the new main screen when we can read it. */
    private fun afterSystem(r: SysResult, summary: String, app: String?): Out {
        if (r.ok) tasks.setApp(r.appLabel ?: app)
        val screen = if (r.ok) mainScreenAfter(SETTLE_AFTER_LAUNCH_MS) else null
        return Out(r.message, error = !r.ok, screen = screen, app = r.appLabel ?: app, summary = summary, decision = if (r.ok) "ok" else "failed")
    }

    private fun mainScreenAfter(settleMs: Long): ScreenSnapshot? {
        val d = env.driver(PhoneScreen.MAIN) ?: return null
        if (!d.availability().ok) return null
        return try {
            env.sleep(settleMs)
            safety.filterScreen(d.observe(detail = false, image = false)).also { rememberPicture(PhoneScreen.MAIN, it) }
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun intent(a: Args): Out {
        val spec = when (val p = IntentSpec.parse(a, env.zone())) {
            is Either.Left -> return Out(p.value, error = true)
            is Either.Right -> p.value
        }
        val kind = a.str("kind") ?: ""
        if (env.isLocked()) return locked("intent $kind")
        var shareTo: AppEntry? = null
        val appQuery = when (spec) {
            is IntentSpec.Share -> spec.app
            is IntentSpec.Settings -> spec.app
            else -> null
        }
        if (appQuery != null) {
            shareTo = when (val r = resolveApp(appQuery)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
            if (spec is IntentSpec.Share) {
                safety.checkOpen(shareTo.packageName, shareTo.label)?.let {
                    return Out(it.message, error = true, app = shareTo.label, summary = "intent $kind", decision = "refused:${it.rule}")
                }
            }
        }
        val r = env.system().intent(spec, shareTo)
        return afterSystem(r, "intent $kind", r.appLabel ?: shareTo?.label)
    }

    private fun notify(a: Args): Out {
        // l10n:keep agent tool result
        val title = a.str("title") ?: return Out("notify 需要 title 和 text。", error = true)
        // l10n:keep agent tool result
        val text = a.str("text") ?: return Out("notify 需要 title 和 text。", error = true)
        val r = env.system().notify(title, text)
        return Out(r.message, error = !r.ok, summary = ui.get("phone_log_notify", "通知"), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
    }

    private fun clipboard(a: Args): Out {
        val set = a.text("set")
        val get = a.bool("get") == true
        // l10n:keep agent tool result
        if (set != null && get) return Out("clipboard 的 set 和 get 一次只能用一个。", error = true)
        if (set != null) {
            val r = env.system().clipboardSet(set)
            return Out(r.message, error = !r.ok, summary = ui.get("phone_log_clipboard_write", "写剪贴板（%1\$d 字）", set.length), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
        }
        // l10n:keep agent tool result
        if (!get) return Out("clipboard 需要 set（写入文字）或 get:true（读取）。", error = true)
        val r = env.system().clipboardGet()
        return Out(r.message, error = !r.ok, summary = ui.get("phone_log_clipboard_read", "读剪贴板"), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
    }

    // ── Screens ─────────────────────────────────────────────────────────────────────

    private fun unavailable(screen: PhoneScreen): String = when (screen) {
        PhoneScreen.BACKGROUND -> env.backgroundUnavailable()
        PhoneScreen.MAIN -> when (env.a11yState()) {
            A11yState.ENABLED_NOT_CONNECTED -> {
                env.notifyFix("a11y")
                // l10n:keep agent tool result
                "DSH 的无障碍服务被系统关掉了或还没连上，现在看不了屏幕。已发通知提醒用户到系统设置里重新打开。在那之前只能用 apps、open、intent、notify、clipboard。"
            }
            else -> {
                env.notifyFix("a11y")
                // l10n:keep agent tool result
                "看屏幕和操作界面需要无障碍权限，用户还没开。已发通知提醒用户到 DSH 设置 → 手机操控 里开启。在那之前只能用 apps、open、intent、notify、clipboard。"
            }
        }
    }

    /** The driver for [screen], or the sentence explaining why there is none. */
    private fun driverFor(screen: PhoneScreen): Either<String, PhoneDriver> {
        // Pure GUI: one fullscreen app, no second screen. Checked before env.driver so a refused
        // call never brings the virtual display up (phone_control) or waits on it.
        if (screen == PhoneScreen.BACKGROUND && pureGui()) {
            env.log("refused the back screen: pure gui")
            return Either.Left(pureGuiSentence(pictureLabel))
        }
        val d = env.driver(screen) ?: return Either.Left(unavailable(screen))
        val av = try {
            d.availability()
        } catch (e: Exception) {
            Availability(false, null)
        }
        if (!av.ok) {
            if (screen == PhoneScreen.MAIN) env.notifyFix("a11y")
            return Either.Left(av.reason ?: unavailable(screen))
        }
        return Either.Right(d)
    }

    private fun routeScreenId(id: String?): Either<String, PhoneScreen> {
        // l10n:keep agent tool result
        if (id == null) return Either.Left("缺少 screen（屏幕号，如 s7）。先调用 screen 拿到屏幕号。")
        return PhoneScreen.ofScreenId(id)?.let { Either.Right(it) }
            // l10n:keep agent tool result
            ?: Either.Left("屏幕号「$id」不对：主屏以 s 开头（如 s7），后台屏以 b 开头（如 b3）。先调用 screen 拿到屏幕号。")
    }

    private fun locked(summary: String) =
        // l10n:keep agent tool result
        Out("手机锁着屏，DSH 不在锁屏状态下操作。请用户解锁后再说一声。", error = true, summary = summary, decision = "refused:locked")

    /**
     * A latched task whose app lost the front to this app itself (the person came back to DSH, a
     * notification opened it): asking for the app's fullscreen again also brings it to the front, so
     * the read describes the task instead of DSH Go — and the model does not spend an `open` (3-5 s
     * and the page it had) getting back.
     */
    private fun returnToTask() {
        val front = runCatching { env.peekPackage() }.getOrNull() ?: return
        val own = env.ownPackage()
        if (own.isEmpty() || front != own) return
        // The latch itself, not pureGui(): once DSH Go is in front the package belongs to no scheme
        // and pureGui() is false — which is exactly how a read ends up describing DSH's own screen.
        val task = tasks.snapshot().id
        val back = when {
            pictureApp != null && pictureTask == task -> pictureApp
            schemeApp != null && schemeTask == task -> schemeApp
            else -> null
        } ?: return
        val ok = runCatching { env.forceFullscreen(back) }.getOrDefault(false)
        env.log("own app in front: brought $back back ok=$ok")
        if (ok) env.sleep(600)
    }

    private fun screen(a: Args): Out {
        val which = if (a.bool("background") == true) PhoneScreen.BACKGROUND else PhoneScreen.MAIN
        val summary = if (which == PhoneScreen.BACKGROUND) ui.get("phone_log_background_read", "读后台屏") else ui.get("phone_log_screen_read", "读屏") // l10n:keep test default
        val region = when (val r = regionOf(a)) {
            is Either.Left -> return Out(r.value, error = true, summary = summary, decision = "invalid")
            is Either.Right -> r.value
        }
        if (which == PhoneScreen.MAIN) returnToTask()
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        val snap = driver.observe(detail = a.bool("detail") == true, image = a.bool("image") == true, region = region)
        env.log(
            "read ${snap.id} ${snap.packageName} kind=${snap.kind} secure=${snap.secure} poorTree=${snap.poorTree} " +
                "image=${snap.image != null} task=${tasks.snapshot().id}",
        )
        tasks.setApp(snap.appLabel)
        // After the page is known, not before: a picture page (a game, 微信 under an outside GUI
        // scheme) must never have the line on it, not even for the second the read takes — the
        // GUI matches its targets against what is on the screen right then.
        rememberPicture(which, snap)
        say(if (which == PhoneScreen.BACKGROUND) ui.get("phone_reading_background", "DSH 正在看后台屏") else ui.get("phone_reading_screen", "DSH 正在看屏幕")) // l10n:keep test default
        val shown = safety.filterScreen(snap)
        val refused = shown !== snap
        val blind = !refused && !env.policies().canSeeImages &&
            (snap.kind == ScreenKind.CANVAS || snap.kind == ScreenKind.BLANKED)
        return Out(
            when {
                // l10n:keep agent tool result
                refused -> shown.text
                // A picture page for a model that cannot read pictures: say it here rather than
                // let it guess coordinates (the gate would refuse the tap anyway, one step later).
                blind -> "屏幕 ${snap.id}：这个页面只有画面，当前模型看不懂图片（设置 → 手机操控 → 进阶 → 模型能看截图）。不要猜坐标，调用 handoff 把手机交还给用户。" // l10n:keep agent tool result
                which == PhoneScreen.MAIN && pureGui() -> pureGuiHint(snap.appLabel, snap.kind)
                else -> ""
            },
            error = refused,
            // A picture the model cannot read is not sent again: the page text is enough to know
            // what is there, and another refusal costs a turn.
            screen = if (refused) null else if (blind) shown.copy(image = null, imageMime = null) else shown,
            app = snap.appLabel ?: snap.packageName,
            summary = "$summary ${snap.id}",
            decision = if (refused) "refused:read" else "ok",
        )
    }

    /**
     * `region` ("x1,y1,x2,y2" in 0-1000) as a screen box. A model writes coordinates the way it
     * writes everything else, so a malformed one gets the format back instead of a silent
     * full-frame read it did not ask for.
     */
    private fun regionOf(a: Args): Either<String, Box?> {
        val raw = a.str("region")?.trim()?.takeIf { it.isNotEmpty() } ?: return Either.Right(null)
        val parts = raw.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return Either.Left("region 要填 4 个 0-1000 的数字，用逗号分开：x1,y1,x2,y2（如 200,300,800,900）。")
        return Either.Right(Box(parts[0], parts[1], parts[2], parts[3]))
    }

    /** Waits, then reads the screen: the only way to let a slow page finish without guessing. */
    private fun wait(a: Args, scope: SerialWorker.Scope): Out {
        val ms = a.int("ms")?.toLong() ?: WAIT_DEFAULT_MS
        if (ms < 1 || ms > WAIT_MAX_MS) {
            // l10n:keep agent tool result
            return Out("wait 的 ms 要在 1 到 5000 毫秒之间。", error = true, summary = PhoneToolCatalog.WAIT, decision = "invalid")
        }
        val which = if (a.bool("background") == true) PhoneScreen.BACKGROUND else PhoneScreen.MAIN
        val summary = if (which == PhoneScreen.BACKGROUND) ui.get("phone_log_background_read", "读后台屏") else ui.get("phone_log_wait", "等 %1\$d 毫秒", ms.toInt()) // l10n:keep test default
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        env.sleep(ms)
        val snap = driver.observe(detail = a.bool("detail") == true, image = a.bool("image") == true)
        tasks.setApp(snap.appLabel)
        rememberPicture(which, snap)
        say(if (which == PhoneScreen.BACKGROUND) ui.get("phone_reading_background", "DSH 正在看后台屏") else ui.get("phone_reading_screen", "DSH 正在看屏幕")) // l10n:keep test default
        val shown = safety.filterScreen(snap)
        val refused = shown !== snap
        return Out(
            // l10n:keep agent tool result
            if (refused) "没有读取这个屏幕的内容。"
            else if (which == PhoneScreen.MAIN && pureGui()) "等了 ${ms} 毫秒。 " + pureGuiHint(snap.appLabel, snap.kind)
            else "等了 ${ms} 毫秒。",
            error = refused,
            screen = shown,
            app = snap.appLabel ?: snap.packageName,
            summary = summary,
            decision = if (refused) "refused:read" else "ok",
        )
    }

    private fun collect(a: Args): Out {
        val sid = a.str("screen")
        val which = when (val r = routeScreenId(sid)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        // l10n:keep agent tool result
        val list = a.int("list") ?: return Out("collect 需要 list（列表编号）。", error = true)
        val pages = (a.int("max_pages") ?: 10).coerceIn(1, 30)
        val summary = ui.get("phone_log_collect", "收集 %1\$s#%2\$d", sid.toString(), list) // l10n:keep test default
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        val pkg = driver.foregroundPackage()
        safety.checkRead(pkg, null)?.let { return Out(it.message, error = true, app = pkg, summary = summary, decision = "refused:${it.rule}") }
        say(ui.get("phone_reading_list", "DSH 正在滚动读取列表")) // l10n:keep test default
        val r = driver.collect(sid!!, list, pages, a.str("until"))
        val screen = r.screen?.let { safety.filterScreen(it) }
        val text = buildString {
            // l10n:keep agent tool result
            append("收集到 ${r.items.size} 条（翻了 ${r.pages} 页，停下原因：${r.stopReason}）：\n")
            var size = 0
            for ((i, item) in r.items.withIndex()) {
                val line = "${i + 1}. $item\n"
                size += line.length
                if (size > MAX_COLLECT_CHARS) {
                    // l10n:keep agent tool result
                    append("…其余 ${r.items.size - i} 条超出长度上限，没有列出\n")
                    break
                }
                append(line)
            }
        }.trimEnd()
        return Out(text, screen = screen, app = screen?.appLabel ?: pkg, summary = summary, decision = "ok")
    }

    /** tap / type / scroll / tap_xy: route, resolve, gate, perform. */
    private fun act(name: String, a: Args, scope: SerialWorker.Scope): Out {
        val sid = (if (name == PhoneToolCatalog.TAP_XY) a.str("observation") else a.str("screen"))
        if (stuckOnLauncher()) {
            // l10n:keep agent tool result
            return Out(
                "任务的应用没能拉回前台，现在还在桌面上：在桌面上点击、滑动会把任务带偏（还会滑到负一屏）。要看当前页用 screen，要回到应用用 open。",
                error = true,
                decision = "refused:launcher",
            )
        }
        val which = when (val r = routeScreenId(sid)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        sid!!
        val id = a.int("id")
        // null = a type on a page with no field ids: the text goes through DSH 输入法 instead.
        var action: PhoneAction? = null
        val kind: WriteKind
        var typed: String? = null
        var submit = false
        var point: Pair<Int, Int>? = null
        var summary: String
        when (name) {
            PhoneToolCatalog.TAP -> {
                // l10n:keep agent tool result
                id ?: return Out("tap 需要 id（元素编号）。", error = true)
                val long = a.bool("long") == true
                action = PhoneAction.Tap(sid, id, long)
                kind = if (long) WriteKind.LONG_TAP else WriteKind.TAP
                summary = if (long) ui.get("phone_log_long_tap", "长按 %1\$s#%2\$d", sid.toString(), id) else ui.get("phone_log_tap", "点 %1\$s#%2\$d", sid.toString(), id) // l10n:keep test default
            }
            PhoneToolCatalog.TYPE -> {
                // l10n:keep agent tool result
                typed = a.text("text") ?: return Out("type 需要 text。", error = true)
                submit = a.bool("submit") == true
                kind = WriteKind.TYPE
                if (id != null) {
                    action = PhoneAction.Type(sid, id, typed, submit)
                    // Masked until the input is known not to be a password or code field.
                    summary = secretSummary(sid, id, submit)
                } else {
                    // l10n:keep test default
                    summary = ui.get("phone_log_type_ime", "输入（%1\$d 字%2\$s，画面页）", typed.length, if (submit) ui.get("phone_log_submit", "，提交") else "")
                }
            }
            PhoneToolCatalog.SCROLL -> {
                val dir = when (a.str("direction")?.lowercase()) {
                    "up" -> PhoneAction.Direction.UP
                    "down" -> PhoneAction.Direction.DOWN
                    "left" -> PhoneAction.Direction.LEFT
                    "right" -> PhoneAction.Direction.RIGHT
                    // l10n:keep agent tool result
                    else -> return Out("scroll 需要 direction：up / down / left / right。", error = true)
                }
                action = PhoneAction.Scroll(sid, id, dir)
                kind = WriteKind.SCROLL
                summary = ui.get("phone_log_scroll", "滚动 %1\$s %2\$s", sid.toString(), dir.name.lowercase()) // l10n:keep test default
            }
            else -> {
                val x = a.int("x")
                val y = a.int("y")
                // 0-1000 归一化契约: 越界在这里就拦掉, 并顺手把单位说清楚, 别让像素坐标一路点到
                // 屏幕外(那是坐标点击最贵的故障: 不报错, 只是点不到)。
                // l10n:keep agent tool result
                if (x == null || y == null || x !in 0..ScreenGeom.NORM || y !in 0..ScreenGeom.NORM) {
                    return Out("tap_xy 的 x/y 是 0-1000 归一化坐标（0,0=截图左上角，1000,1000=截图右下角），收到 ($x, $y) 不对：不要用像素，不要用百分数。", error = true)
                }
                point = x to y
                action = PhoneAction.TapXY(sid, x, y)
                kind = WriteKind.TAP_XY
                summary = ui.get("phone_log_tap_xy", "点坐标 %1\$s (%2\$d,%3\$d)", sid.toString(), x, y) // l10n:keep test default
            }
        }
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        if (action == null) {
            if (env.imePassword()) {
                // l10n:keep agent tool result
                return Out("这是密码框，DSH 不代填。调用 handoff，让用户自己输入。", error = true, summary = summary, decision = "refused:secret-field")
            }
        }
        lastScreen = which
        val target: TargetInfo? = if (id != null && kind != WriteKind.TAP_XY) {
            driver.target(sid, id) ?: return stale(driver, sid, summary)
        } else {
            null
        }
        val secret = kind == WriteKind.TYPE && SafetyRules.isSecretTarget(target)
        if (action != null && kind == WriteKind.TYPE && !secret) {
            summary = ui.get("phone_log_type", "输入 %1\$s#%2\$d（%3\$d 字%4\$s）", sid, id!!, typed!!.length, if (submit) ui.get("phone_log_submit", "，提交") else "") // l10n:keep test default
        }
        val pkg = target?.packageName ?: driver.foregroundPackage()
        val appLabel = target?.appLabel
        val app = appLabel ?: pkg
        val owner = tasks.activeOwner() ?: return stoppedOut(summary)
        // canvas 类页面没有元素可点, 坐标是唯一操作方式; 告诉门, 它在任务模式里可以不再逐次问。
        val kind0 = driver.screenKind()
        // Acting on a page nobody can read from the tree: from here on the task is pure GUI.
        if (which == PhoneScreen.MAIN && (kind0 == ScreenKind.CANVAS || kind0 == ScreenKind.BLANKED)) enterPureGui(pkg)
        val needsVision = kind == WriteKind.TAP_XY && (kind0 == ScreenKind.CANVAS || kind0 == ScreenKind.BLANKED)
        val verdict = safety.checkWrite(kind, target, pkg, appLabel, typed, submit, point, needsVision)
        val decision = when (val r = safety.resolve(verdict, scope)) {
            is SafetyGate.Result.No -> return Out(r.message, error = true, app = app, summary = summary, decision = r.decision)
            is SafetyGate.Result.Go -> r.decision
        }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut(summary)
        tasks.setApp(appLabel ?: pkg)
        say(ui.get("phone_acting", "DSH 正在操作 · %1\$s", statusLine(kind, target, if (secret) SafetyRules.MASK else typed))) // l10n:keep test default
        if (action == null) {
            val text = typed!!
            val err = env.typeIntoPage(text, submit)
            val after = try {
                safety.filterScreen(driver.observe(detail = false, image = kind0 != ScreenKind.TREE))
            } catch (e: InterruptedException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (err != null) {
                return Out(err, error = true, screen = after, app = app, summary = summary, decision = "failed")
            }
            // l10n:keep test default
            val said = if (submit) ui.get("phone_ime_typed_submit", "已把 %1\$d 字送进输入框并提交。", text.length) else ui.get("phone_ime_typed", "已把 %1\$d 字送进输入框。", text.length)
            return Out(said, screen = after, app = app, summary = summary, decision = decision)
        }
        val res = driver.perform(action!!)
        // A tap that popped the keyboard up leaves the field focused: telling the model so keeps it
        // from tapping the field a second time, which is exactly what dismisses the keyboard.
        val tapped = res.ok && (kind == WriteKind.TAP || kind == WriteKind.TAP_XY) && env.keyboardUp()
        // l10n:keep agent tool result
        val hint = if (tapped) "输入法已经弹出来了，输入框就在光标里：直接 type 送文字，不要再点输入框——再点一次会把键盘收起来。" else ""
        return Out(
            if (hint.isEmpty()) res.message else res.message + "\n" + hint,
            error = !res.ok,
            screen = res.screen?.let { safety.filterScreen(it) },
            app = app,
            summary = summary,
            decision = if (res.ok) decision else "failed",
            change = res.change,
        )
    }

    /** A `type` in the log without its length or text: 「输入 s3#5（••••）」. */
    private fun secretSummary(sid: String, id: Int, submit: Boolean): String =
        ui.get("phone_log_type_secret", "输入 %1\$s#%2\$d（%3\$s%4\$s）", sid, id, SafetyRules.MASK, if (submit) ui.get("phone_log_submit", "，提交") else "") // l10n:keep test default

    private fun statusLine(kind: WriteKind, t: TargetInfo?, typed: String?): String {
        val label = t?.label?.takeIf { it.isNotBlank() }?.let { SafetyRules.quoteText(it, 16) } ?: t?.role?.let(ui::role) ?: ""
        return when (kind) {
            WriteKind.TAP -> ui.get("phone_status_tap", "点「%1\$s」", label) // l10n:keep test default
            WriteKind.LONG_TAP -> ui.get("phone_status_long_tap", "长按「%1\$s」", label) // l10n:keep test default
            WriteKind.TYPE -> ui.get("phone_status_type", "输入「%1\$s」", if (typed == SafetyRules.MASK) typed else SafetyRules.quoteText(typed ?: "", 16)) // l10n:keep test default
            WriteKind.SCROLL -> ui.get("phone_status_scroll", "滚动") // l10n:keep test default
            WriteKind.TAP_XY -> ui.get("phone_status_tap_xy", "点截图上的位置") // l10n:keep test default
            WriteKind.KEY -> ui.get("phone_status_key", "按键") // l10n:keep test default
        }
    }

    /** The element no longer matches its snapshot: refuse and show the screen as it is now. */
    private fun stale(driver: PhoneDriver, sid: String, summary: String): Out {
        val now = try {
            safety.filterScreen(driver.observe(detail = false, image = true))
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Out(
            // l10n:keep agent tool result
            "屏幕 $sid 已经过期，或者这个元素变了，没有执行。按下面的新屏幕重新选。",
            error = true,
            screen = now,
            summary = summary,
            decision = "stale",
        )
    }

    /**
     * True when the page the model is working on belongs to a launcher while the task is latched on
     * one app: an icon tap re-opens the running app (into a small window on this ROM), and a sideways
     * swipe on the home screen lands on the ROM's 负一屏 assistant page — either way the task loses
     * the app it was working in and there is no picture of it left to work from.
     */
    private fun stuckOnLauncher(): Boolean {
        // Only after this task has actually gone pure GUI, and only while the latch belongs to this
        // task: [pureGui] also consults a cached foreground package, and on the first call of a task
        // that cache could still name 微信 while the phone itself was sitting on the launcher — the
        // task's very first tap was then refused as "now on the desktop" (log 03:20:49
        // `tap error decision=refused:launcher task=4`).
        //
        // It refuses only when the repair failed. Refusing whenever the home screen was in front made
        // a background task do nothing at all after the person simply left the phone on the launcher
        // ("切到后台就执行不了"), so the app is brought back and the call goes on.
        val task = tasks.snapshot().id
        val latched = (pictureApp != null && pictureTask == task) || (schemeApp != null && schemeTask == task)
        if (!pureGuiEntered || !latched) return false
        val app = when {
            pictureApp != null && pictureTask == task -> pictureApp
            schemeApp != null && schemeTask == task -> schemeApp
            else -> null
        } ?: return false
        val pkg = runCatching { env.peekPackage() }.getOrNull() ?: return false
        val home = env.homePackages()
        if (pkg !in home) return false
        val ok = runCatching { env.forceFullscreen(app) }.getOrDefault(false)
        env.log("launcher in front while latched ($pkg): brought $app back ok=$ok")
        if (!ok) return true
        env.sleep(600)
        val again = runCatching { env.peekPackage() }.getOrNull()
        return again != null && again in home
    }

    private fun key(a: Args): Out {
        val key = when (a.str("key")?.lowercase()) {
            "back" -> PhoneAction.GlobalKey.BACK
            "home" -> PhoneAction.GlobalKey.HOME
            "recents" -> PhoneAction.GlobalKey.RECENTS
            "notifications" -> PhoneAction.GlobalKey.NOTIFICATIONS
            // l10n:keep agent tool result
            else -> return Out("key 只能是 back / home / recents / notifications。", error = true)
        }
        val sid = a.str("screen")
        val which = if (sid == null) {
            PhoneScreen.MAIN
        } else {
            when (val r = routeScreenId(sid)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
        }
        val summary = ui.get("phone_log_key", "按键 %1\$s", key.name.lowercase()) // l10n:keep test default
        if (key == PhoneAction.GlobalKey.HOME && pureGui()) {
            // l10n:keep agent tool result
            return Out(
                "任务正在一个应用里跑，不要按桌面：看当前页用 screen，换应用用 open。这台机型从桌面重开会把全屏 App 挤成小窗，在桌面上滑动还会滑到负一屏，任务就回不去了。",
                error = true,
                summary = summary,
                decision = "refused:leave-app",
            )
        }
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        say(ui.get("phone_status_press", "DSH 正在操作 · 按%1\$s", when (key) { // l10n:keep test default
            PhoneAction.GlobalKey.BACK -> ui.get("phone_key_back", "返回") // l10n:keep test default
            PhoneAction.GlobalKey.HOME -> ui.get("phone_key_home", "桌面") // l10n:keep test default
            PhoneAction.GlobalKey.RECENTS -> ui.get("phone_key_recents", "最近任务") // l10n:keep test default
            PhoneAction.GlobalKey.NOTIFICATIONS -> ui.get("phone_key_notifications", "通知栏") // l10n:keep test default
        }))
        val res = driver.perform(PhoneAction.Key(sid ?: "${which.prefix}0", key))
        return Out(res.message, error = !res.ok, screen = res.screen?.let { safety.filterScreen(it) }, summary = summary,
            decision = if (res.ok) "ok" else "failed", change = res.change)
    }

    private fun handoff(a: Args, scope: SerialWorker.Scope): Out {
        val owner = tasks.activeOwner() ?: return stoppedOut("handoff")
        // l10n:keep agent tool result
        val reason = a.str("reason") ?: return Out("handoff 需要 reason：一句话告诉用户要做什么。", error = true)
        val gate = env.gate() ?: return Out(
            // l10n:keep agent tool result
            "现在没办法把手机交给用户（DSH 不能发通知，悬浮条也没开）。直接在回复里告诉用户要做什么。",
            error = true,
            summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
            decision = "refused:no-gate",
        )
        val cause = refusal?.takeIf { (who, _) -> tasks.isCurrent(who) && who.taskId == owner.taskId }?.second
        refusal = null
        val note = env.beforeHandoff(lastScreen)
        if (!tasks.isCurrent(owner)) return stoppedOut("handoff")
        val result = scope.userWait { gate.handoff(reason, HANDOFF_MS, cause) }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut("handoff")
        val prefix = note?.let { "$it\n" } ?: ""
        return when (result) {
            HandoffResult.CONTINUE -> {
                lastScreen = PhoneScreen.MAIN
                Out(prefix + "用户点了「继续」。下面是现在的屏幕；接着做之前先确认状态。", screen = mainScreenAfter(0), summary = ui.get("phone_log_handoff", "交还用户"), decision = "continue") // l10n:keep test default
            }
            HandoffResult.STOP -> {
                // l10n:keep agent tool result
                tasks.stopIfCurrent(owner, "用户在交还时选择了停止")
                Out(prefix + PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = ui.get("phone_log_handoff", "交还用户"), decision = "stopped") // l10n:keep test default
            }
            HandoffResult.TIMEOUT -> Out(
                // l10n:keep agent tool result
                prefix + "等了 10 分钟用户没有回应。停下来，告诉用户回来后可以接着做。",
                error = true,
                summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
                decision = "timeout",
            )
        }
    }

    private fun stoppedOut(summary: String) = Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true,
        summary = summary, decision = "stopped")

    companion object {
        /** Under DSH's 660 s MCP timeout, so the bridge always answers first. */
        const val MAX_TOTAL_MS = 640_000L
        const val PAUSE_WAIT_MS = 600_000L
        const val HANDOFF_MS = 600_000L

        /** Tools whose success means the agent got past whatever refused it before. */
        private val WRITES = setOf(PhoneToolCatalog.TAP, PhoneToolCatalog.TYPE, PhoneToolCatalog.TAP_XY, PhoneToolCatalog.OPEN)
        private val DONE = setOf("ok", "allowed", "confirmed", "confirmed-app", "confirmed-task")
        const val SETTLE_AFTER_LAUNCH_MS = 1200L
        const val MAX_APPS = 150
        const val MAX_COLLECT_CHARS = 16_000
        const val WAIT_DEFAULT_MS = 2_000L
        /** 5 s, not 30: a task that needs longer is a task whose page has not come up, and a page
         *  that has not come up in five seconds is not helped by waiting thirty. */
        const val WAIT_MAX_MS = 5_000L

        /**
         * Apps whose screens an outside GUI scheme reads and drives itself (微信 today): the
         * overlay's status line would land inside that picture and shift the targets it looks for.
         */
        /**
         * The apps shipped as pure GUI: a task on one of them is one fullscreen app — no virtual
         * screen, nothing drawn over it. The person edits the list in 设置 › 手机操控, and while
         * they haven't, this is it (微信 is read and driven by an outside GUI scheme of its own).
         */
        val DEFAULT_GUI_SCHEME = setOf("com.tencent.mm")

        /** No package is ever empty: the latch of a page that hides the app it belongs to. */
        private const val UNKNOWN_PACKAGE = ""

        /** The call's own time, not counting waits for the person. */
        fun budgetMs(tool: String): Long = when (tool) {
            PhoneToolCatalog.APPS -> 15_000L
            PhoneToolCatalog.NOTIFY, PhoneToolCatalog.CLIPBOARD -> 10_000L
            PhoneToolCatalog.OPEN, PhoneToolCatalog.INTENT -> 25_000L
            PhoneToolCatalog.SCREEN -> 12_000L
            PhoneToolCatalog.COLLECT -> 120_000L
            PhoneToolCatalog.WAIT -> 35_000L
            PhoneToolCatalog.HANDOFF -> 30_000L
            else -> 30_000L
        }
    }
}
