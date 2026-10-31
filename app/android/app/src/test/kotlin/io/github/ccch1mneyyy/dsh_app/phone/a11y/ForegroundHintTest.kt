package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app we just opened stays named while its page hides the tree. */
class ForegroundHintTest {
    @Test
    fun theAppWeJustOpenedIsNamedWhileItStaysUnreadable() {
        val hint = ForegroundHint(keepMs = 1_000L)
        hint.opened("com.tencent.mm", 0L)
        assertEquals("com.tencent.mm", hint.seen(null, 500L))
    }

    @Test
    fun aReadableOtherAppWinsAndEndsTheGuess() {
        val hint = ForegroundHint(keepMs = 30_000L)
        hint.opened("com.tencent.mm", 0L)
        assertEquals("com.taobao.taobao", hint.seen("com.taobao.taobao", 100L))
        assertNull(hint.seen(null, 200L))
    }

    @Test
    fun aReadableWechatKeepsTheGuessAliveForTheNextBlankedPage() {
        val hint = ForegroundHint(keepMs = 30_000L)
        hint.opened("com.tencent.mm", 0L)
        assertEquals("com.tencent.mm", hint.seen("com.tencent.mm", 100L))
        assertEquals("com.tencent.mm", hint.seen(null, 200L))
    }

    @Test
    fun theGuessExpires() {
        val hint = ForegroundHint(keepMs = 1_000L)
        hint.opened("com.tencent.mm", 0L)
        assertNull(hint.seen(null, 2_000L))
    }

    @Test
    fun clearingForgetsIt() {
        val hint = ForegroundHint()
        hint.opened("com.game.example", 0L)
        hint.clear()
        assertNull(hint.seen(null, 1L))
    }
}
