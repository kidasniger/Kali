package com.kalivnc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Service au premier plan : installe Kali si besoin, lance proroot + VNC, garde le tout en vie. */
class KaliService : Service() {
    companion object {
        const val ACTION_STOP = "com.kalivnc.app.STOP"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        private const val CHANNEL = "kali"
    }

    @Volatile private var proc: Process? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            proc?.destroyForcibly()
            KaliState.setPhase(KaliState.Phase.IDLE)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (worker?.isAlive != true) {
            val w = intent?.getIntExtra(EXTRA_W, 1280) ?: 1280
            val h = intent?.getIntExtra(EXTRA_H, 720) ?: 720
            KaliState.clear()
            KaliState.setPhase(KaliState.Phase.INSTALLING)
            worker = Thread({ work(w, h) }, "kali-worker").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kali", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KaliService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Arrêter", stop
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Kali VNC")
            .setContentText("Kali tourne en arrière-plan")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", Config.VNC_PORT), 300) }
        true
    } catch (_: Exception) { false }

    private fun work(w: Int, h: Int) {
        val wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kalivnc:session")
        wl.acquire()
        try {
            Installer.ensure(this)
            KaliState.setPhase(KaliState.Phase.STARTING)
            KaliState.setProgress(-1)
            Installer.prepareSession(this, Installer.vncPassword(this), w, h)
            KaliState.log("Démarrage du bureau Kali…")

            val p = ProotRunner.builder(this, "exec /bin/sh /root/start-vnc.sh").start()
            proc = p

            // The VNC server itself is the authoritative readiness signal.
            // Waiting for an independent TCP probe caused STARTING to remain
            // stuck even though TigerVNC was already listening on 5901.
            val vncReady = CountDownLatch(1)
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        KaliState.log(line)
                        val normalized = line.lowercase()
                        if (
                            normalized.contains("listening for vnc connections") ||
                            normalized.contains("created vnc server for screen")
                        ) {
                            vncReady.countDown()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!p.isAlive) vncReady.countDown()
                }
            }.start()

            // Filet de sécurité : si le texte exact loggé par TigerVNC diffère
            // d'une version à l'autre, un port ouvert sur 127.0.0.1 prouve aussi
            // que le serveur écoute. Les deux signaux sont indépendants ;
            // le premier arrivé déclenche le passage à READY.
            Thread {
                while (p.isAlive && vncReady.count > 0) {
                    if (portOpen()) { vncReady.countDown(); break }
                    Thread.sleep(300)
                }
            }.start()

            val signaled = vncReady.await(30, TimeUnit.SECONDS)
            if (!signaled) {
                val exitCode = try { p.exitValue() } catch (_: IllegalThreadStateException) { null }
                val cause = if (exitCode != null)
                    "Le moteur PRoot a terminé avec le code $exitCode."
                else
                    "TigerVNC n'a pas signalé son écoute après 30 secondes."
                throw IOException(
                    "Le serveur VNC n'a pas confirmé son démarrage. $cause\n" +
                        ProotRunner.diagnostics(this)
                )
            }

            // Give the X session a moment to finish starting, then let the
            // embedded RFB client perform the actual connection/handshake.
            Thread.sleep(500)
            KaliState.log("VNC : serveur prêt, ouverture du client RFB…")
            KaliState.setPhase(KaliState.Phase.READY)

            val exitCode = p.waitFor()
            KaliState.log("Session Kali terminée (code $exitCode).")
            if (KaliState.phase != KaliState.Phase.ERROR) {
                KaliState.setPhase(KaliState.Phase.IDLE)
            }
        } catch (e: Exception) {
            KaliState.log("Erreur : ${e.message}")
            KaliState.setPhase(KaliState.Phase.ERROR)
        } finally {
            if (wl.isHeld) wl.release()
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        proc?.destroyForcibly()
        if (KaliState.phase != KaliState.Phase.ERROR) KaliState.setPhase(KaliState.Phase.IDLE)
        super.onDestroy()
    }
}
