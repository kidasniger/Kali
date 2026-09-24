package com.kalivnc.app

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

object Installer {
    fun rootfs(ctx: Context) = File(ctx.filesDir, "rootfs")
    private fun prootBin(ctx: Context) = File(ctx.filesDir, "bin/proot")
    private fun marker(ctx: Context, n: String) = File(ctx.filesDir, "markers/$n")

    fun vncPassword(ctx: Context): String {
        val sp = ctx.getSharedPreferences("kali", Context.MODE_PRIVATE)
        var pw = sp.getString("vnc_pw", null)
        if (pw == null) {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val r = SecureRandom()
            pw = (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
            sp.edit().putString("vnc_pw", pw).apply()
        }
        return pw!!
    }

    /** Installe tout ce qui manque (idempotent : chaque étape a un marqueur). */
    fun ensure(ctx: Context) {
        for (d in listOf("bin", "tmp", "markers", "dl")) File(ctx.filesDir, d).mkdirs()
        ensureProot(ctx)
        ensureRootfs(ctx)
        configureRootfs(ctx)
        ensurePackages(ctx)
    }

    private fun ensureProot(ctx: Context) {
        val f = prootBin(ctx)
        if (!(f.exists() && f.length() > 100_000)) {
            KaliState.log("Téléchargement de proot…")
            download(Config.PROOT_URL, f, "proot")
            val magic = ByteArray(4)
            f.inputStream().use { it.read(magic) }
            if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte()) {
                f.delete()
                throw IOException("Le fichier proot téléchargé n'est pas un exécutable (vérifie Config.PROOT_URL)")
            }
        }
        f.setExecutable(true, false)
    }

    private fun ensureRootfs(ctx: Context) {
        if (marker(ctx, "rootfs").exists()) return
        val arch = File(ctx.filesDir, "dl/rootfs.tar.xz")
        if (!arch.exists()) {
            KaliState.log("Téléchargement de Kali (plusieurs centaines de Mo)…")
            download(Config.ROOTFS_URL, arch, "Kali")
        }
        val root = rootfs(ctx)
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        KaliState.log("Extraction du système Kali (quelques minutes)…")
        extract(arch, root)
        arch.delete()
        marker(ctx, "rootfs").writeText("ok")
    }

    /** Réglages nécessaires pour que apt/dpkg fonctionnent sous proot. */
    private fun configureRootfs(ctx: Context) {
        val r = rootfs(ctx)
        File(r, "etc/resolv.conf").apply { delete(); parentFile?.mkdirs(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        val hosts = File(r, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(r, "etc/apt/apt.conf.d").mkdirs()
        File(r, "etc/apt/apt.conf.d/99-kalivnc").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
        val pol = File(r, "usr/sbin/policy-rc.d")
        pol.parentFile?.mkdirs()
        pol.writeText("#!/bin/sh\nexit 101\n")
        pol.setExecutable(true, false)
        File(r, "tmp").mkdirs()
        File(r, "root").mkdirs()
    }

    private fun ensurePackages(ctx: Context) {
        if (marker(ctx, "packages").exists()) return
        KaliState.log("Installation de XFCE + serveur VNC (10 à 30 min, connexion requise)…")
        KaliState.setProgress(-1)
        val script = readAsset(ctx, "install-packages.sh").replace("__PKGS__", Config.APT_PACKAGES)
        val p = ProotRunner.builder(ctx, script).start()
        p.inputStream.bufferedReader().forEachLine { KaliState.log(it) }
        val code = p.waitFor()
        if (code != 0) throw IOException("apt a échoué (code $code) — voir le journal ci-dessus")
        marker(ctx, "packages").writeText("ok")
    }

    /** Écrit le script de démarrage du bureau (mot de passe VNC + résolution). */
    fun prepareSession(ctx: Context, pw: String, w: Int, h: Int) {
        configureRootfs(ctx)
        val s = readAsset(ctx, "start-vnc.sh")
            .replace("__PW__", pw).replace("__W__", w.toString()).replace("__H__", h.toString())
            .replace("__PORT__", Config.VNC_PORT.toString())
        val f = File(rootfs(ctx), "root/start-vnc.sh")
        f.parentFile?.mkdirs()
        f.writeText(s)
        f.setExecutable(true, false)
    }

    private fun readAsset(ctx: Context, name: String) =
        ctx.assets.open(name).bufferedReader().use { it.readText() }

    // ---------------------------------------------------------------- téléchargement

    private fun download(url: String, dest: File, label: String) {
        val tmp = File(dest.path + ".part")
        var u = url
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "KaliVNC/1.0")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Redirection sans Location")
                u = URL(URL(u), loc).toString()
                conn.disconnect()
                if (++redirects > 8) throw IOException("Trop de redirections")
                continue
            }
            if (code != 200) throw IOException("HTTP $code pour $u")
            break
        }
        val total = conn.contentLengthLong
        var last = -1
        conn.inputStream.use { inp ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) {
                            last = pct
                            KaliState.setProgress(pct)
                            if (pct % 10 == 0) KaliState.log("$label : $pct %")
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Impossible de finaliser ${dest.name}")
        KaliState.setProgress(-1)
    }

    // ---------------------------------------------------------------- extraction tar.xz

    private class CountingStream(inp: InputStream) : FilterInputStream(inp) {
        var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len); if (r > 0) count += r; return r
        }
    }

    private fun stripTop(raw: String): String {
        val n = raw.removePrefix("./")
        val top = Config.ROOTFS_TOP_DIR
        return when {
            n == top || n == "$top/" -> ""
            n.startsWith("$top/") -> n.removePrefix("$top/")
            else -> n
        }
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (_: Exception) { }
    }

    private fun extract(archive: File, dest: File) {
        val total = archive.length()
        val counting = CountingStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))
        var last = -1
        TarArchiveInputStream(XZCompressorInputStream(counting)).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = stripTop(e.name).trimEnd('/')
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val out = File(dest, name)
                try {
                    when {
                        e.isDirectory -> {
                            out.mkdirs()
                            chmod(out, (e.mode and 0xFFF) or 0x1C0)
                        }
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            Os.symlink(e.linkName, out.path)
                        }
                        e.isLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            val target = File(dest, stripTop(e.linkName))
                            try { Os.link(target.path, out.path) } catch (_: Exception) { target.copyTo(out, true) }
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            FileOutputStream(out).use { tar.copyTo(it, 64 * 1024) }
                            chmod(out, (e.mode and 0x1FF) or 0x180)
                        }
                        else -> { /* périphériques, fifo : ignorés */ }
                    }
                } catch (ex: Exception) {
                    KaliState.log("Ignoré : $name (${ex.message})")
                }
                if (total > 0) {
                    val pct = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != last) { last = pct; KaliState.setProgress(pct) }
                }
            }
        }
        KaliState.setProgress(-1)
    }
}
