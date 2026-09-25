package com.kalivnc.app

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Client VNC (protocole RFB 3.8) minimal, intégré à l'APK : authentification VNC,
 * encodage Raw, redimensionnement du bureau. Le serveur ne tourne qu'en local (127.0.0.1).
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val cb: Callback
) {
    interface Callback {
        fun onReady(c: RfbClient, w: Int, h: Int)
        fun onUpdate(c: RfbClient)
        fun onClosed(c: RfbClient, reason: String)
    }

    val lock = Any()
    @Volatile var bitmap: Bitmap? = null
    @Volatile var fbW = 0
    @Volatile var fbH = 0
    @Volatile private var hasFrame = false
    private var emptyUpdates = 0

    @Volatile private var closedByUs = false
    private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val outputLock = Any()

    fun start() {
        Thread({ runLoop() }, "rfb-reader").start()
    }

    fun close() {
        closedByUs = true
        try { socket?.close() } catch (_: Exception) { }
    }

    // ------------------------------------------------------------- envoi (sérialisé)

    private fun send(b: ByteArray) {
        if (closedByUs) return
        val stream = out ?: return
        synchronized(outputLock) {
            try {
                stream.write(b)
                stream.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendDirect(stream: OutputStream, b: ByteArray) {
        synchronized(outputLock) {
            stream.write(b)
            stream.flush()
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val b = ByteBuffer.allocate(8)
        b.put(4.toByte()); b.put((if (down) 1 else 0).toByte()); b.putShort(0); b.putInt(keysym)
        send(b.array())
    }

    fun sendPointer(x: Int, y: Int, mask: Int) {
        val b = ByteBuffer.allocate(6)
        b.put(5.toByte()); b.put(mask.toByte()); b.putShort(x.toShort()); b.putShort(y.toShort())
        send(b.array())
    }

    private fun requestUpdate(incremental: Boolean) {
        val b = ByteBuffer.allocate(10)
        b.put(3.toByte()); b.put((if (incremental) 1 else 0).toByte())
        b.putShort(0); b.putShort(0); b.putShort(fbW.toShort()); b.putShort(fbH.toShort())
        send(b.array())
    }

    // ------------------------------------------------------------- boucle de lecture

    private fun runLoop() {
        var reason = "Connexion fermée"
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)
            socket = s
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val o = BufferedOutputStream(s.getOutputStream())

            // Version
            inp.readFully(ByteArray(12))
            o.write("RFB 003.008\n".toByteArray()); o.flush()

            // Le serveur embarqué est strictement local et utilise SecurityTypes None.
            // On conserve la négociation RFB 3.8 mais sans mot de passe, afin d'éviter
            // les échecs VNC Auth et la blacklist de 127.0.0.1 par TigerVNC.
            val n = inp.readUnsignedByte()
            if (n == 0) {
                val m = ByteArray(inp.readInt()); inp.readFully(m)
                throw IOException(String(m))
            }
            val types = ByteArray(n); inp.readFully(types)
            if (!types.contains(1.toByte())) {
                throw IOException("Le serveur VNC embarqué ne propose pas l'authentification locale sans mot de passe")
            }
            o.write(1); o.flush()

            // SecurityResult (RFB 3.8) : 4 octets, 0 = OK. Obligatoire même pour le type "None".
            val secResult = inp.readInt()
            if (secResult != 0) {
                val m = ByteArray(inp.readInt()); inp.readFully(m)
                throw IOException("Échec de sécurité VNC : ${String(m)}")
            }

            // ClientInit (partagé) puis ServerInit
            o.write(1); o.flush()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            if (w <= 0 || h <= 0) {
                throw IOException("Dimensions d'écran VNC invalides ($w x $h) : le flux RFB est désynchronisé")
            }
            inp.readFully(ByteArray(16))                 // format de pixels du serveur (ignoré)
            inp.readFully(ByteArray(inp.readInt()))      // nom du bureau
            resize(w, h)

            // À partir d'ici, toutes les écritures sont synchronisées sur la socket.
            out = o

            // Format de pixels imposé : 32 bits, true-color, little-endian.
            val pf = ByteBuffer.allocate(20)
            pf.put(0.toByte()); pf.put(ByteArray(3))
            pf.put(32.toByte()); pf.put(24.toByte()); pf.put(0.toByte()); pf.put(1.toByte())
            pf.putShort(255); pf.putShort(255); pf.putShort(255)
            pf.put(16.toByte()); pf.put(8.toByte()); pf.put(0.toByte()); pf.put(ByteArray(3))
            sendDirect(o, pf.array())

            // La géométrie du bureau est fixe côté serveur : demander uniquement Raw.
            // Cela évite que TigerVNC renvoie en boucle des pseudo-frames de taille.
            val enc = ByteBuffer.allocate(8)
            enc.put(2.toByte()); enc.put(0.toByte()); enc.putShort(1)
            enc.putInt(0) // Raw
            sendDirect(o, enc.array())

            // Première demande d'image : envoi synchrone avant le callback UI.
            val first = ByteBuffer.allocate(10)
            first.put(3.toByte()); first.put(0.toByte())
            first.putShort(0); first.putShort(0)
            first.putShort(w.toShort()); first.putShort(h.toShort())
            sendDirect(o, first.array())

            hasFrame = false
            emptyUpdates = 0
            KaliState.log("VNC : première demande d'image envoyée (" + w + "x" + h + "). En attente des pixels…")

            while (!closedByUs) {
                when (val t = inp.readUnsignedByte()) {
                    0 -> {
                        inp.readUnsignedByte()
                        val rects = inp.readUnsignedShort()
                        var gotPixels = false

                        repeat(rects) {
                            val x = inp.readUnsignedShort()
                            val y = inp.readUnsignedShort()
                            val rw = inp.readUnsignedShort()
                            val rh = inp.readUnsignedShort()
                            when (val encoding = inp.readInt()) {
                                0 -> {
                                    readRaw(inp, x, y, rw, rh)
                                    gotPixels = true
                                }
                                else -> throw IOException("Encodage VNC inattendu : " + encoding)
                            }
                        }

                        if (gotPixels) {
                            emptyUpdates = 0
                            if (!hasFrame) {
                                hasFrame = true
                                KaliState.log("VNC : première image reçue.")
                                cb.onReady(this, fbW, fbH)
                            }
                            cb.onUpdate(this)
                            requestUpdate(true)
                        } else {
                            // Une réponse vide est normale en mode incrémental.
                            // Ne jamais revenir à une demande complète : cela recréait
                            // la boucle ExtendedDesktopSize observée sur l'appareil.
                            emptyUpdates++
                            if (emptyUpdates <= 3) {
                                KaliState.log("VNC : aucune nouvelle image, attente incrémentale…")
                            }
                            Thread.sleep(120)
                            requestUpdate(true)
                        }
                    }
                    1 -> { inp.readUnsignedByte(); inp.readUnsignedShort(); val nc = inp.readUnsignedShort(); skip(inp, nc * 6L) }
                    2 -> { /* bell */ }
                    3 -> { inp.readFully(ByteArray(3)); skip(inp, inp.readInt().toLong() and 0xFFFFFFFFL) }
                    else -> throw IOException("Message serveur inconnu : $t")
                }
            }
        } catch (e: Exception) {
            if (!closedByUs) reason = e.message ?: e.javaClass.simpleName
        } finally {
            try { socket?.close() } catch (_: Exception) { }
            if (!closedByUs) cb.onClosed(this, reason)
        }
    }

    private fun skip(inp: DataInputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("Flux interrompu")
            left -= r
        }
    }

    private fun resize(w: Int, h: Int) {
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        synchronized(lock) { bitmap = nb }
        fbW = w
        fbH = h
    }

    private fun readRaw(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val bmp = bitmap ?: throw IOException("Pas de framebuffer")
        if (x + w > bmp.width || y + h > bmp.height) throw IOException("Rectangle hors de l'écran")
        val buf = ByteArray(w * h * 4)
        inp.readFully(buf)
        val px = IntArray(w * h)
        var p = 0
        for (i in px.indices) {
            px[i] = -0x1000000 or
                ((buf[p + 2].toInt() and 0xFF) shl 16) or
                ((buf[p + 1].toInt() and 0xFF) shl 8) or
                (buf[p].toInt() and 0xFF)
            p += 4
        }
        synchronized(lock) { bmp.setPixels(px, 0, w, x, y, w, h) }
    }

    // ------------------------------------------------------------- authentification DES

    private fun reverseBits(b: Int): Byte {
        var x = b and 0xFF
        var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
        return r.toByte()
    }

    private fun desResponse(challenge: ByteArray): ByteArray {
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8) { i -> if (i < pw.size) reverseBits(pw[i].toInt()) else 0 }
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return c.doFinal(challenge)
    }
}
