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
import java.security.MessageDigest
import java.security.SecureRandom

object Installer {
    private object ProotRuntimeVersion { const val VALUE = "5.4.0-pr" }
    private const val APP_RUNTIME_VERSION = "1.7"

    fun rootfs(ctx: Context) = File(ctx.filesDir, "rootfs")
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

    fun ensure(ctx: Context) {
        for (d in listOf("bin", "tmp", "markers", "dl")) File(ctx.filesDir, d).mkdirs()
        ensureNativeRuntime(ctx)
        ensureRootfs(ctx)
        configureRootfs(ctx)
        verifyProot(ctx)
        ensurePackages(ctx)
    }

    private fun ensureNativeRuntime(ctx: Context) {
        listOf(
            File(ctx.filesDir, "bin/proot"),
            File(ctx.filesDir, "bin/proot.part"),
            File(ctx.filesDir, "bin/libproroot.so")
        ).forEach { if (it.exists()) it.delete() }

        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val required = listOf("libproot.so", "libproot-loader.so")
        val missing = required.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException(
                "Moteur PRoot Android ${ProotRuntimeVersion.VALUE} absent de l'APK : ${missing.joinToString()}"
            )
        }
        if (!ProotRunner.launcher(ctx).canExecute()) {
            throw IOException("Le moteur PRoot Android n'est pas exécutable depuis nativeLibraryDir.")
        }
        if (!File(dir, "libproot-loader.so").canExecute()) {
            throw IOException("Le loader PRoot Android n'est pas exécutable depuis nativeLibraryDir.")
        }
    }

    private fun ensureRootfs(ctx: Context) {
        if (marker(ctx, "rootfs").exists()) return
        val arch = File(ctx.filesDir, "dl/rootfs.tar.xz")
        val sums = File(ctx.filesDir, "dl/SHA256SUMS")
        try {
            if (!arch.exists()) {
                KaliState.log("Téléchargement de Kali…")
                download(Config.ROOTFS_URL, arch, "Kali")
            }
            KaliState.log("Vérification SHA-256 du rootfs…")
            download(Config.ROOTFS_SHA256_URL, sums, "SHA256SUMS")
            verifySha256(arch, sums, Config.ROOTFS_FILE_NAME)

            val root = rootfs(ctx)
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            KaliState.log("Extraction du système Kali…")
            extract(arch, root)
            requireGuestLayout(root)
            marker(ctx, "rootfs").writeText("ok")
            arch.delete()
            sums.delete()
        } catch (e: Exception) {
            marker(ctx, "rootfs").delete()
            rootfs(ctx).deleteRecursively()
            throw e
        }
    }

    private fun requireGuestLayout(root: File) {
        val required = listOf(
            File(root, "bin/sh"),
            File(root, "bin/bash"),
            File(root, "usr/bin/env"),
            File(root, "etc/apt"),
            File(root, "root")
        )
        val missing = required.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            throw IOException("Rootfs incomplet : ${missing.joinToString { it.path }}")
        }
    }

    private fun configureRootfs(ctx: Context) {
        val r = rootfs(ctx)
        File(r, "etc/resolv.conf").apply {
            delete()
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        val hosts = File(r, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(r, "etc/apt/apt.conf.d").mkdirs()
        File(r, "etc/apt/apt.conf.d/99-kalivnc").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"0\";\n"
        )
        val pol = File(r, "usr/sbin/policy-rc.d")
        pol.parentFile?.mkdirs()
        pol.writeText("#!/bin/sh\nexit 101\n")
        pol.setExecutable(true, false)
        File(r, "tmp").mkdirs()
        File(r, "root").mkdirs()
    }

    private fun verifyProot(ctx: Context) {
        val probeMarker = marker(ctx, "proot-probe-$APP_RUNTIME_VERSION")
        if (probeMarker.exists()) return

        KaliState.log("Diagnostic PRoot Android ${ProotRuntimeVersion.VALUE} : test du shell Kali…")
        val p = ProotRunner.builder(
            ctx,
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            echo "PRoot probe: start"
            id
            pwd
            uname -m
            test -x /bin/sh
            test -x /bin/bash
            /bin/true
            /bin/sh -c 'echo PRoot probe: sh OK'
            echo "PRoot probe: success"
            """.trimIndent()
        ).start()

        val output = buildString {
            p.inputStream.bufferedReader().forEachLine {
                KaliState.log(it)
                appendLine(it)
            }
        }
        val code = p.waitFor()
        if (code != 0) {
            throw IOException(
                "PRoot Android a quitté avec le code $code. " +
                    (output.lineSequence().lastOrNull { it.isNotBlank() } ?: "aucun message PRoot") +
                    "\n" + ProotRunner.diagnostics(ctx)
            )
        }

        probeMarker.writeText("ok")
        KaliState.log("PRoot : backend Android validé.")
    }

    private fun ensurePackages(ctx: Context) {
        if (marker(ctx, "packages").exists()) return
        KaliState.log("Installation de XFCE + serveur VNC…")
        KaliState.setProgress(-1)

        val script = readAsset(ctx, "install-packages.sh").replace("__PKGS__", Config.APT_PACKAGES)
        val p = ProotRunner.builder(ctx, script).start()
        p.inputStream.bufferedReader().forEachLine { KaliState.log(it) }
        val code = p.waitFor()

        if (code != 0) {
            throw IOException(
                "Installation Kali interrompue : moteur PRoot/shell a quitté avec le code $code" +
                    "\n" + ProotRunner.diagnostics(ctx)
            )
        }
        marker(ctx, "packages").writeText("ok")
    }

    fun prepareSession(ctx: Context, pw: String, w: Int, h: Int) {
        configureRootfs(ctx)
        val s = readAsset(ctx, "start-vnc.sh")
            .replace("__PW__", pw)
            .replace("__W__", w.toString())
            .replace("__H__", h.toString())
            .replace("__PORT__", Config.VNC_PORT.toString())
        val f = File(rootfs(ctx), "root/start-vnc.sh")
        f.parentFile?.mkdirs()
        f.writeText(s)
        f.setExecutable(true, false)
    }

    private fun readAsset(ctx: Context, name: String) =
        ctx.assets.open(name).bufferedReader().use { it.readText() }

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
            conn.setRequestProperty("User-Agent", "KaliVNC/1.6")
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
                    if (n == 0) continue
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
        if (tmp.length() == 0L) throw IOException("Téléchargement vide pour $label")
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Impossible de finaliser ${dest.name}")
        KaliState.setProgress(-1)
    }

    private fun verifySha256(file: File, sums: File, fileName: String) {
        val expected = sums.readLines()
            .map { it.trim() }
            .firstOrNull { it.endsWith(" $fileName") || it.endsWith("*$fileName") }
            ?.substringBefore(' ')
            ?.lowercase()

        if (expected.isNullOrBlank()) {
            throw IOException("SHA-256 introuvable pour $fileName")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("SHA-256 invalide pour $fileName")
        }
    }

    private class CountingStream(inp: InputStream) : FilterInputStream(inp) {
        var count = 0L
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
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
        var failures = 0

        TarArchiveInputStream(XZCompressorInputStream(counting)).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = stripTop(e.name).trimEnd('/')
                if (name.isEmpty()) continue
                if (name.split('/').any { it == ".." }) throw IOException("Entrée tar dangereuse : $name")

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
                            try { Os.link(target.path, out.path) }
                            catch (_: Exception) { target.copyTo(out, true) }
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            FileOutputStream(out).use { tar.copyTo(it, 64 * 1024) }
                            chmod(out, (e.mode and 0x1FF) or 0x180)
                        }
                        else -> {
                        }
                    }
                } catch (ex: Exception) {
                    failures++
                    KaliState.log("Extraction impossible : $name (${ex.message})")
                }

                if (total > 0) {
                    val pct = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                    if (pct != last) {
                        last = pct
                        KaliState.setProgress(pct)
                    }
                }
            }
        }
        KaliState.setProgress(-1)
        if (failures > 0) throw IOException("Extraction du rootfs incomplète : $failures entrée(s) en erreur")
    }
}
