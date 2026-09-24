package com.kalivnc.app

import android.content.Context
import java.io.File

object ProotRunner {
    private const val PROROOT_ENGINE_VERSION = "1.2.8"
    private const val PROROOT_LIBRARY = "libproroot.so"
    private fun nativeDir(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir)
    fun launcher(ctx: Context) = File(nativeDir(ctx), PROROOT_LIBRARY)
    fun runtimeLog(ctx: Context) = File(ctx.filesDir, "proroot-runtime.log")

    fun diagnostics(ctx: Context): String {
        val f = runtimeLog(ctx)
        if (!f.exists()) return "Aucun journal natif PRoot disponible."
        return try {
            f.readLines().takeLast(80).joinToString("\n")
        } catch (e: Exception) {
            "Lecture du journal PRoot impossible : ${e.message}"
        }
    }

    private fun requireRuntime(ctx: Context) {
        val required = listOf(
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-linker.so",
            "libproroot-bridge.so",
            "libproroot-stub-loader.so"
        )
        val dir = nativeDir(ctx)
        val missing = required.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Moteur PRoot Android ${PROROOT_ENGINE_VERSION} incomplet : ${missing.joinToString()}"
            )
        }
        if (!launcher(ctx).canExecute()) {
            throw IllegalStateException("Le moteur PRoot natif n'est pas exécutable depuis nativeLibraryDir.")
        }
    }

    fun builder(ctx: Context, script: String): ProcessBuilder {
        requireRuntime(ctx)
        val files = ctx.filesDir
        val root = Installer.rootfs(ctx)
        val tmp = File(files, "tmp").apply { mkdirs() }

        val cmd = mutableListOf(
            launcher(ctx).path,
            "-0",
            "--link2symlink",
            "--static-loader",
            "-r", root.path,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/bash", "-c",
            script
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
        env["PROROOT_TMP_DIR"] = tmp.path
        env["PROROOT_VERBOSE"] = "1"
        env["PROROOT_LOG_APPEND"] = runtimeLog(ctx).path
        env["PROOT_NO_SECCOMP"] = "1"
        pb.redirectErrorStream(true)
        return pb
    }
}