package com.kalivnc.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.hypot
import kotlin.math.min

/** Touches X11 (keysyms) utilisées par le clavier. */
object Keys {
    const val ESC = 0xff1b
    const val TAB = 0xff09
    const val ENTER = 0xff0d
    const val BACKSPACE = 0xff08
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PGUP = 0xff55
    const val PGDN = 0xff56
    const val END = 0xff57
    const val SHIFT = 0xffe1
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val SUPER = 0xffeb

    fun uni(cp: Int): Int = if (cp < 0x100) cp else 0x01000000 + cp

    fun fromKeyEvent(ev: KeyEvent): Int {
        val kc = ev.keyCode
        when (kc) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return ENTER
            KeyEvent.KEYCODE_DEL -> return BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> return DELETE
            KeyEvent.KEYCODE_TAB -> return TAB
            KeyEvent.KEYCODE_ESCAPE -> return ESC
            KeyEvent.KEYCODE_DPAD_LEFT -> return LEFT
            KeyEvent.KEYCODE_DPAD_UP -> return UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> return RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> return DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> return HOME
            KeyEvent.KEYCODE_MOVE_END -> return END
            KeyEvent.KEYCODE_PAGE_UP -> return PGUP
            KeyEvent.KEYCODE_PAGE_DOWN -> return PGDN
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> return SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> return CTRL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> return ALT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> return SUPER
        }
        if (kc in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 0xffbe + (kc - KeyEvent.KEYCODE_F1)
        val mask = (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val u = ev.getUnicodeChar(ev.metaState and mask)
        if (u != 0 && (u and KeyCharacterMap.COMBINING_ACCENT) == 0) return uni(u)
        return 0
    }
}

/** Vue plein écran : affiche le bureau Kali, convertit toucher/clavier en événements VNC. */
class VncView(ctx: Context) : View(ctx) {
    var client: RfbClient? = null
    var onToggleKeyboard: (() -> Unit)? = null
    var onModifiersChanged: (() -> Unit)? = null
    var ctrl = false
    var alt = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val scrollStep = 40f * ctx.resources.displayMetrics.density / 2f
    private val ui = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var dragging = false
    private var longPressed = false
    private var scrolled = false
    private var maxPointers = 0
    private var lastAvgY = 0f
    private var scrollAcc = 0f
    private var swallowUp = -1

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------- affichage

    private fun updateMap(): Boolean {
        val bmp = client?.bitmap ?: return false
        if (width == 0 || height == 0) return false
        scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        ox = (width - bmp.width * scale) / 2f
        oy = (height - bmp.height * scale) / 2f
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val cl = client ?: return
        val bmp = cl.bitmap ?: return
        if (!updateMap()) return
        synchronized(cl.lock) {
            c.save()
            c.translate(ox, oy)
            c.scale(scale, scale)
            c.drawBitmap(bmp, 0f, 0f, paint)
            c.restore()
        }
    }

    private fun fbX(sx: Float): Int {
        val bmp = client?.bitmap ?: return 0
        return ((sx - ox) / scale).toInt().coerceIn(0, bmp.width - 1)
    }

    private fun fbY(sy: Float): Int {
        val bmp = client?.bitmap ?: return 0
        return ((sy - oy) / scale).toInt().coerceIn(0, bmp.height - 1)
    }

    // ------------------------------------------------------------- toucher

    private fun move(x: Float, y: Float, mask: Int) { client?.sendPointer(fbX(x), fbY(y), mask) }
    private fun click(x: Float, y: Float, mask: Int) { move(x, y, mask); move(x, y, 0) }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private val longPressRunnable = Runnable {
        if (!moved && maxPointers == 1) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            click(downX, downY, 4) // clic droit
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (client == null) return false
        updateMap()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; dragging = false; longPressed = false; scrolled = false
                maxPointers = 1
                move(e.x, e.y, 0)
                ui.postDelayed(longPressRunnable, 500)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                ui.removeCallbacks(longPressRunnable)
                if (dragging) { move(lastX, lastY, 0); dragging = false }
                lastAvgY = avgY(e); scrollAcc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val a = avgY(e)
                    scrollAcc += a - lastAvgY
                    lastAvgY = a
                    while (scrollAcc >= scrollStep) { click(e.getX(0), e.getY(0), 8); scrollAcc -= scrollStep; scrolled = true }
                    while (scrollAcc <= -scrollStep) { click(e.getX(0), e.getY(0), 16); scrollAcc += scrollStep; scrolled = true }
                } else if (maxPointers == 1) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        ui.removeCallbacks(longPressRunnable)
                        if (!longPressed) { dragging = true; move(downX, downY, 1) }
                    }
                    if (dragging) move(e.x, e.y, 1)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(longPressRunnable)
                when {
                    dragging -> { move(e.x, e.y, 0); dragging = false }
                    maxPointers == 1 && !moved && !longPressed -> click(e.x, e.y, 1)
                    maxPointers == 2 && !scrolled -> click(downX, downY, 4)
                    maxPointers >= 3 && !scrolled -> onToggleKeyboard?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(longPressRunnable)
                if (dragging) { move(lastX, lastY, 0); dragging = false }
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val c = client ?: return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            updateMap()
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> c.sendPointer(fbX(e.x), fbY(e.y), 0)
                MotionEvent.ACTION_SCROLL -> {
                    val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) click(e.x, e.y, if (v > 0) 8 else 16)
                }
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ------------------------------------------------------------- clavier

    fun press(ks: Int) {
        val c = client ?: return
        if (ctrl) c.sendKey(Keys.CTRL, true)
        if (alt) c.sendKey(Keys.ALT, true)
        c.sendKey(ks, true)
        c.sendKey(ks, false)
        if (alt) c.sendKey(Keys.ALT, false)
        if (ctrl) c.sendKey(Keys.CTRL, false)
        if (ctrl || alt) { ctrl = false; alt = false; onModifiersChanged?.invoke() }
    }

    fun typeText(s: CharSequence) {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            i += Character.charCount(cp)
            press(
                when (cp) {
                    '\n'.code -> Keys.ENTER
                    '\t'.code -> Keys.TAB
                    else -> Keys.uni(cp)
                }
            )
        }
    }

    private fun handleKey(ev: KeyEvent): Boolean {
        val c = client ?: return false
        val ks = Keys.fromKeyEvent(ev)
        if (ks == 0) return false
        val isMod = ks == Keys.SHIFT || ks == Keys.CTRL || ks == Keys.ALT || ks == Keys.SUPER
        when (ev.action) {
            KeyEvent.ACTION_DOWN ->
                if ((ctrl || alt) && !isMod) { press(ks); swallowUp = ev.keyCode } else c.sendKey(ks, true)
            KeyEvent.ACTION_UP ->
                if (swallowUp == ev.keyCode) swallowUp = -1 else c.sendKey(ks, false)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyUp(keyCode, event)

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val view = this
        return object : BaseInputConnection(view, false) {
            private var composing = ""

            private fun backspaces(n: Int) { repeat(n) { view.press(Keys.BACKSPACE) } }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                backspaces(composing.length); composing = ""
                view.typeText(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                backspaces(composing.length)
                view.typeText(text)
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                backspaces(maxOf(beforeLength, 1)); composing = ""
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = view.handleKey(event)

            override fun performEditorAction(actionCode: Int): Boolean { view.press(Keys.ENTER); return true }
        }
    }
}
