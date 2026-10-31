package io.github.ccch1mneyyy.dsh_app.phone.input

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.phone.background.Proto

/** What the injector needs from the shell-uid engine ([io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl]). */
interface TypingChannel {
    fun typeAscii(text: String): Boolean
    fun key(code: Int): Boolean
    fun ime(action: String, component: String): Boolean

    companion object {
        const val ENTER = 66
    }
}

/** What it needs from this app's own input method ([DshIme]). */
interface ImePort {
    fun component(): String
    /** The input method the person is using right now. */
    fun current(): String?
    fun ready(): Boolean
    fun commit(text: String, submit: Boolean): String?
}

/**
 * What it needs to reach a field through the clipboard: the one channel that survives a page whose
 * tree is hidden, and the only one that never takes the person's keyboard away.
 */
interface PastePort {
    /** Whether a field node with focus is there to paste into at all (微信 hides its own). */
    fun fieldReady(): Boolean

    /** Puts [text] on the system clipboard; false when nothing could write it. */
    fun clipboard(text: String): Boolean

    /** Pastes into the field node that has focus; false on a page that hides its tree (微信). */
    fun pasteField(): Boolean

    /** Presses the paste key through the shell uid; false when no privileged channel is open. */
    fun pasteKey(): Boolean
}

/**
 * 0·A: text into a page the accessibility tree cannot describe (微信, games).
 *
 * In the order of what costs the person least:
 *
 * - `input text` for ASCII: it types into whatever field has focus, so nothing moves and nothing
 *   flashes. `input keyevent 66` presses the enter a search field wants.
 * - the clipboard, when the page leaves a field node to paste into: a real `ACTION_PASTE` lands the
 *   text in the cursor and no keyboard is involved at all.
 * - an input method for everything else: `input text` cannot carry a single Chinese character (it
 *   throws on this ROM), and a page like 微信 ignores the paste key — measured on the device, the
 *   key was delivered with the text already on the clipboard and nothing appeared in the field. So
 *   the engine selects this app's input method for the length of one commit and puts the person's
 *   keyboard back right after; it is the route that ends with the text in the field.
 * - the paste key, but only when the input method route is unavailable, and then the model is told
 *   the text is merely on the clipboard instead of being told the field is filled.
 *
 * When nothing is available (no Shizuku / self-ADB), the model gets a sentence saying so rather
 * than a silently empty field.
 *
 * Tapping the field again is never part of the recipe: on 微信 the tap that opens the search box
 * already brings the keyboard up and puts the cursor in it, and a second tap dismisses the keyboard
 * and takes the focus away. So the answer to a failure says "type again", not "tap it first".
 */
class TextInjector(
    private val channel: TypingChannel,
    private val ime: ImePort,
    private val paste: PastePort? = null,
    /** Whether a keyboard is already on screen, i.e. a field has the focus right now. */
    private val keyboardUp: () -> Boolean = { false },
    /** Uptime in ms; a seam so the wait below is testable without a device. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** null when the text is in, else the sentence the model gets (// l10n:keep agent tool result). */
    fun type(text: String, submit: Boolean): String? {
        if (text.isEmpty()) return "type 需要 text。"
        if (text.length > MAX_TEXT) return "一次最多输入 $MAX_TEXT 字，分段输入。"
        if (asciiOnly(text) && channel.typeAscii(text)) {
            if (submit) channel.key(TypingChannel.ENTER)
            return null
        }
        val port = paste
        // Only touch the clipboard when a paste has somewhere to land, so the person's clipboard is
        // left alone on the pages that end up going through the input method anyway.
        if (port != null && port.fieldReady() && port.clipboard(text) && port.pasteField()) {
            if (submit) channel.key(TypingChannel.ENTER)
            return null
        }
        val borrowed = handover(text, submit)
        if (borrowed == null) return null
        if (port != null && port.clipboard(text) && port.pasteKey()) {
            if (submit) channel.key(TypingChannel.ENTER)
            return PASTE_KEY_ONLY
        }
        return borrowed
    }

    /**
     * Borrow the input method for one commit. The person's keyboard is selected again in [finally],
     * so a failed commit never leaves them without a keyboard.
     */
    private fun handover(text: String, submit: Boolean): String? {
        val ours = ime.component()
        val previous = ime.current()
        val borrowed = previous != ours
        if (borrowed) {
            if (!channel.ime(Proto.IME_ENABLE, ours)) return NO_ENGINE
            if (!channel.ime(Proto.IME_SET, ours)) {
                channel.ime(Proto.IME_DISABLE, ours)
                return NO_ENGINE
            }
        }
        try {
            val deadline = now() + WAIT_MS
            var last: String? = null
            while (now() < deadline) {
                if (ime.ready()) {
                    val err = ime.commit(text, submit)
                    if (err == null) return null
                    last = err
                }
                sleep(POLL_MS)
            }
            if (last != null) return last
            // A keyboard that is already up means the field has the focus: sending the model off to
            // tap it again is what closes the keyboard and loses the paste (measured on 微信).
            return if (keyboardUp()) FIELD_READY_NO_TAP else NO_FIELD

        } finally {
            if (borrowed) {
                if (previous != null) channel.ime(Proto.IME_SET, previous)
                channel.ime(Proto.IME_DISABLE, ours)
            }
        }
    }

    companion object {
        const val MAX_TEXT = 2_000

        /** How long the borrowed input method gets to bind and settle on the field. */
        private const val WAIT_MS = 2_500L
        private const val POLL_MS = 60L

        private const val NO_ENGINE =
            "这个页面没有输入框编号，文字要经特权通道送进去：先在 设置 › 手机操控 › 进阶 开通 不打扰模式 或 自连 ADB（也可以用 DSH 输入法 手动顶上）。现在两条都不通。"

        // l10n:keep agent tool result
        private const val NO_FIELD =
            "文字没有送进输入框：先用 tap_xy 点一下输入框（让光标在里面），再 type。"

        /** Nothing landed, but the keyboard is up already: tapping the field would close it. */
        // l10n:keep agent tool result
        const val FIELD_READY_NO_TAP =
            "文字还没有送进输入框。键盘已经弹着、光标就在里面：直接再 type 一次，不要再点输入框——再点一下键盘就收起来，反而更粘不进去。"

        /** The paste key was delivered with the text on the clipboard — nothing proves it landed. */
        // l10n:keep agent tool result
        const val PASTE_KEY_ONLY =
            "文字已放进剪贴板，也按了粘贴键：看一眼输入框里有没有它。没有就长按输入框，点「粘贴」。"

        /**
         * `input text` types keys, so only printable ASCII arrives; `%s` reaches it as a space, and
         * anything else has to go through an input method.
         */
        fun asciiOnly(text: String): Boolean =
            text.isNotEmpty() && text.length <= MAX_TEXT &&
                text.all { it.code in 0x20..0x7E } && !text.contains("%s")
    }
}

/** The real input method: this app's [DshIme]. */
class DshImePort(private val app: Context) : ImePort {
    override fun component(): String = DshIme.component(app)
    override fun current(): String? = DshIme.current(app)
    override fun ready(): Boolean = DshIme.ready()
    override fun commit(text: String, submit: Boolean): String? = DshIme.commit(text, submit)
}
