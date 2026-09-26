package com.kalivnc.app

import android.content.Context
import java.io.File

object ProotRunner {
    private const val PROOT_VERSION = "5.4.0-pr"
    private const val PROOT_BINARY = "libproot.so"
    private const val PROOT_LOADER = "libproot-loader.so"

    private fun nativeDir(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir)
    fun launcher(ctx: Context) = File(nativeDir(ctx), PROOT_BINARY)
    fun runtimeLog(ctx: Context) = File(ctx.filesDir, "proot-runtime.log")

    fun diagnostics(ctx: Context): String {
        val f = runtimeLog(ctx)
        if (!f.exists()) return "Aucun journal natif PRoot disponible."
        return try {
            f.readLines().takeLast(120).joinToString("\n")
        } catch (e: Exception) {
            "Lecture du journal PRoot impossible : ${e.message}"
        }
    }

    private fun requireRuntime(ctx: Context) {
        val dir = nativeDir(ctx)
        val required = listOf(PROOT_BINARY, PROOT_LOADER)
        val missing = required.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Moteur PRoot Android $PROOT_VERSION incomplet : ${missing.joinToString()}"
            )
        }
        if (!launcher(ctx).canExecute()) {
            throw IllegalStateException("Le moteur PRoot natif n'est pas exécutable depuis nativeLibraryDir.")
        }
        if (!File(dir, PROOT_LOADER).canExecute()) {
            throw IllegalStateException("Le loader PRoot natif n'est pas exécutable depuis nativeLibraryDir.")
        }
    }

    /**
     * PRoot Android patché, statiquement lié. Le loader est lui aussi
     * installé dans nativeLibraryDir pour éviter toute exécution depuis
     * un répertoire writable de l'application.
     */
    fun builder(ctx: Context, script: String): ProcessBuilder {
        requireRuntime(ctx)
        val files = ctx.filesDir
        val root = Installer.rootfs(ctx)
        val tmp = File(files, "tmp").apply { mkdirs() }
        val loader = File(nativeDir(ctx), PROOT_LOADER)

        val cmd = listOf(
            launcher(ctx).path,
            "-r", root.path,
            "-0",
            "--link2symlink",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/sh", "-c", script
        )

        val pb = ProcessBuilder(cmd)
        pb.directory(files)

        val env = pb.environment()
        env.clear()
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TMPDIR"] = tmp.path
        env["PROOT_TMP_DIR"] = tmp.path
        env["PROOT_LOADER"] = loader.path
        env["PROOT_NO_SECCOMP"] = "1"
        env["PROROOT_VERBOSE"] = "1"
        env["PROROOT_LOG_APPEND"] = runtimeLog(ctx).path

        pb.redirectErrorStream(true)
        return pb
    }
}
