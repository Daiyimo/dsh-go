package io.github.ccch1mneyyy.dsh_app.phone.input

import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 画面页面的输入通道 (设置 › 手机操控 › 进阶 › DSH 输入法).
 *
 * 微信、游戏这类页面把无障碍树藏起来，DSH 只看得到截图，也就拿不到输入框的编号 —— `type`
 * 的原路（对节点做 ACTION_SET_TEXT）在那里走不通。输入法的 InputConnection 与界面树无关，
 * 所以只要系统当前用的是这个输入法，DSH 就能把文字直接送进前台应用正在编辑的输入框里。
 *
 * 它不画键盘：只有一条细条，写着 DSH 正在输入，外加一个「切回我的键盘」的按钮 —— 否则用户
 * 自己的键盘就被顶掉了。文字只由手机操控模块送进来（[commit]），这个输入法自己不接收按键。
 */
class DshImeService : InputMethodService() {
    private val lock = Any()
    private var conn: InputConnection? = null
    private var editor: EditorInfo? = null

    override fun onCreateInputView(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(8f), dp(8f))
            setBackgroundColor(Color.parseColor("#F2202020"))
        }
        val label = TextView(this).apply {
            text = AppStrings.get(this@DshImeService, R.string.phone_ime_bar)
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val back = Button(this).apply {
            text = AppStrings.get(this@DshImeService, R.string.phone_ime_back)
            isAllCaps = false
            setOnClickListener { switchToNextInputMethod(false) }
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return bar
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        synchronized(lock) {
            conn = currentInputConnection
            editor = attribute
        }
        DshIme.attach(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        synchronized(lock) {
            conn = currentInputConnection
            editor = info
        }
    }

    override fun onFinishInput() {
        synchronized(lock) {
            conn = null
            editor = null
        }
        DshIme.detach(this)
        super.onFinishInput()
    }

    override fun onDestroy() {
        DshIme.detach(this)
        synchronized(lock) {
            conn = null
            editor = null
        }
        super.onDestroy()
    }

    /** Types [text] into the field that has the focus right now; [submit] fires the editor's action key. */
    internal fun typeInto(text: String, submit: Boolean): String? {
        val c = synchronized(lock) { conn } ?: return AppStrings.get(this, R.string.phone_ime_no_field)
        val info = synchronized(lock) { editor }
        val ok = runCatching { c.commitText(text, 1) }.getOrDefault(false)
        if (!ok) return AppStrings.get(this, R.string.phone_ime_rejected)
        if (submit) {
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_SEARCH
            if (runCatching { c.performEditorAction(action) }.getOrDefault(false).not()) {
                runCatching { c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) }
                runCatching { c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
            }
        }
        return null
    }

    /** True when the focused field is a password / verification-code style input. */
    internal fun focusedIsSecret(): Boolean {
        val info = synchronized(lock) { editor } ?: return false
        val type = info.inputType and InputType.TYPE_MASK_CLASS
        if (type != InputType.TYPE_CLASS_TEXT && type != InputType.TYPE_CLASS_NUMBER) return false
        if (info.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) return true
        if (info.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0) return true
        if (info.inputType and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0) return true
        if (info.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0) return true
        return false
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}

/**
 * The bridge between 手机操控 (worker thread) and the input method (main thread).
 *
 * Only one instance exists in the process; it is attached while a field is being edited. All
 * calls are safe to make when no IME is selected or no field has the focus: they answer
 * "not ready" instead of throwing, because every tool call asks [ready].
 */
object DshIme {
    @Volatile private var live: DshImeService? = null
    @Volatile private var since: Long = 0

    internal fun attach(service: DshImeService) {
        live = service
        if (since == 0L) since = android.os.SystemClock.uptimeMillis()
    }

    internal fun detach(service: DshImeService) {
        if (live === service) {
            live = null
            since = 0
        }
    }

    /** A field is being edited by this input method right now. */
    fun ready(): Boolean = live != null

    /** The input method this app ships, for the status map and the settings page. */
    fun component(context: Context): String = context.packageName + "/" + DshImeService::class.java.name

    fun enabled(context: Context): Boolean = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.enabledInputMethodList.any { it.packageName == context.packageName && it.serviceName == DshImeService::class.java.name }
    }.getOrDefault(false)

    fun selected(context: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) == component(context)
    }.getOrDefault(false)

    fun password(): Boolean = live?.focusedIsSecret() ?: false

    /** Null on success, else the sentence the model gets back. */
    fun commit(text: String, submit: Boolean): String? {
        val service = live ?: return null
        return service.typeInto(text, submit)
    }
}
