package com.kalivnc.app

/** État partagé entre le service (installation/démarrage) et l'écran. */
object KaliState {
    enum class Phase { IDLE, INSTALLING, STARTING, READY, ERROR }

    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var progress: Int = -1
        private set
    @Volatile var listener: (() -> Unit)? = null

    private val lines = ArrayDeque<String>()

    fun setPhase(p: Phase) { phase = p; listener?.invoke() }
    fun setProgress(p: Int) { progress = p; listener?.invoke() }

    fun log(s: String) {
        synchronized(lines) {
            lines.addLast(s)
            while (lines.size > 300) lines.removeFirst()
        }
        listener?.invoke()
    }

    fun clear() = synchronized(lines) { lines.clear() }

    fun text(): String = synchronized(lines) { lines.takeLast(60).joinToString("\n") }
}
