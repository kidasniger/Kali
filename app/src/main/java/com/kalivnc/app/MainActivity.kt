package com.kalivnc.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Écran unique : pendant l'installation/le démarrage on voit un journal ; ensuite
 * seul le bureau Kali (VNC embarqué) est affiché en plein écran.
 */
class MainActivity : Activity(), RfbClient.Callback {
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var setupBox: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var retry: Button

    private lateinit var vncBox: LinearLayout
    private lateinit var vnc: VncView
    private lateinit var toolbar: LinearLayout
    private lateinit var ctrlBtn: Button
    private lateinit var altBtn: Button

    private var client: RfbClient? = null
    private var kb = false
    private var retries = 0
    private var renderPending = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        buildUi()
        KaliState.listener = { ui.post { scheduleRender() } }
    }

    // ------------------------------------------------------------- interface

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // --- écran d'installation / journal
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F1A.toInt())
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            text = "Kali NetHunter"
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        logView = TextView(this).apply {
            setTextColor(0xFF9FE870.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(logView) }
        retry = Button(this).apply {
            text = "Relancer"; isAllCaps = false; visibility = View.GONE
            setOnClickListener { onRetry() }
        }
        setupBox.addView(titleView)
        setupBox.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setupBox.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setupBox.addView(retry)

        // --- bureau VNC + barre de touches spéciales
        vnc = VncView(this)
        vnc.onToggleKeyboard = { toggleKeyboard() }
        vnc.onModifiersChanged = { ui.post { refreshMods() } }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1B2130.toInt())
            visibility = View.GONE
        }
        ctrlBtn = tbtn("Ctrl") { vnc.ctrl = !vnc.ctrl; refreshMods() }
        altBtn = tbtn("Alt") { vnc.alt = !vnc.alt; refreshMods() }
        toolbar.addView(tbtn("Esc") { vnc.press(Keys.ESC) })
        toolbar.addView(tbtn("Tab") { vnc.press(Keys.TAB) })
        toolbar.addView(ctrlBtn)
        toolbar.addView(altBtn)
        toolbar.addView(tbtn("←") { vnc.press(Keys.LEFT) })
        toolbar.addView(tbtn("↑") { vnc.press(Keys.UP) })
        toolbar.addView(tbtn("↓") { vnc.press(Keys.DOWN) })
        toolbar.addView(tbtn("→") { vnc.press(Keys.RIGHT) })
        toolbar.addView(tbtn("⌨✕") { toggleKeyboard() })

        vncBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        vncBox.addView(vnc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        vncBox.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(setupBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(vncBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun tbtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
    }

    private fun refreshMods() {
        ctrlBtn.text = if (vnc.ctrl) "[Ctrl]" else "Ctrl"
        altBtn.text = if (vnc.alt) "[Alt]" else "Alt"
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        kb = !kb
        toolbar.visibility = if (kb) View.VISIBLE else View.GONE
        vnc.requestFocus()
        if (kb) imm.showSoftInput(vnc, InputMethodManager.SHOW_FORCED)
        else imm.hideSoftInputFromWindow(vnc.windowToken, 0)
    }

    // ------------------------------------------------------------- cycle de vie

    override fun onStart() {
        super.onStart()
        ensureService()
        scheduleRender()
    }

    override fun onDestroy() {
        KaliState.listener = null
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (vncBox.visibility == View.VISIBLE) toggleKeyboard() else moveTaskToBack(true)
    }

    // ------------------------------------------------------------- service

    private fun ensureService() {
        val ph = KaliState.phase
        if (ph != KaliState.Phase.IDLE && ph != KaliState.Phase.ERROR) return
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(m)
        val longSide = max(m.widthPixels, m.heightPixels)
        val shortSide = min(m.widthPixels, m.heightPixels)
        val s = if (longSide > Config.MAX_DESKTOP_WIDTH) Config.MAX_DESKTOP_WIDTH.toFloat() / longSide else 1f
        val w = ((longSide * s).toInt() / 2) * 2
        val h = ((shortSide * s).toInt() / 2) * 2
        KaliState.setPhase(KaliState.Phase.INSTALLING)
        startForegroundService(
            Intent(this, KaliService::class.java)
                .putExtra(KaliService.EXTRA_W, w)
                .putExtra(KaliService.EXTRA_H, h)
        )
    }

    private fun onRetry() {
        retries = 0
        if (KaliState.phase == KaliState.Phase.READY) {
            scheduleRender()
        } else {
            KaliState.setPhase(KaliState.Phase.IDLE)
            ensureService()
        }
    }

    // ------------------------------------------------------------- rendu

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        ui.postDelayed({ renderPending = false; render() }, 150)
    }

    private fun showVnc(show: Boolean) {
        vncBox.visibility = if (show) View.VISIBLE else View.GONE
        setupBox.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun render() {
        val ph = KaliState.phase
        if (ph == KaliState.Phase.READY) {
            if (client == null && retries < 4) connect()
        } else if (client != null) {
            client?.close(); client = null; vnc.client = null
            showVnc(false)
        }
        if (vncBox.visibility == View.VISIBLE) return

        titleView.text = when (ph) {
            KaliState.Phase.INSTALLING -> "Installation de Kali (premier lancement)…"
            KaliState.Phase.STARTING -> "Démarrage de Kali…"
            KaliState.Phase.READY -> "Connexion au bureau…"
            KaliState.Phase.ERROR -> "Erreur"
            KaliState.Phase.IDLE -> "Kali est arrêté"
        }
        val p = KaliState.progress
        bar.isIndeterminate = p < 0
        if (p >= 0) bar.progress = p
        bar.visibility = if (ph == KaliState.Phase.ERROR || ph == KaliState.Phase.IDLE) View.GONE else View.VISIBLE
        logView.text = KaliState.text()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        retry.visibility = if (ph == KaliState.Phase.INSTALLING || ph == KaliState.Phase.STARTING) View.GONE else View.VISIBLE
    }

    private fun connect() {
        val c = RfbClient("127.0.0.1", Config.VNC_PORT, Installer.vncPassword(this), this)
        client = c
        vnc.client = c
        c.start()
    }

    // ------------------------------------------------------------- callbacks VNC

    override fun onReady(c: RfbClient, w: Int, h: Int) {
        ui.post {
            if (client !== c) return@post
            retries = 0
            showVnc(true)
            hideSystemUi()
            vnc.invalidate()
        }
    }

    override fun onUpdate(c: RfbClient) { vnc.postInvalidateOnAnimation() }

    override fun onClosed(c: RfbClient, reason: String) {
        ui.post {
            if (client !== c) return@post
            client = null
            vnc.client = null
            showVnc(false)
            KaliState.log("VNC : $reason")
            if (KaliState.phase == KaliState.Phase.READY && retries < 3) {
                retries++
                ui.postDelayed({ scheduleRender() }, 1500)
            }
            scheduleRender()
        }
    }
}
