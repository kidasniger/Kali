package com.kalivnc.app

import android.content.Context
import java.io.File

object ProotRunner {
    /** Prépare une commande exécutée dans le rootfs Kali via proot (faux root). */
    fun builder(ctx: Context, script: String): ProcessBuilder {
        val files = ctx.filesDir
        val cmd = listOf(
            File(files, "bin/proot").path, "--link2symlink", "--kill-on-exit", "-0",
            "-r", Installer.rootfs(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
            "/bin/bash", "-c", script
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(files)
        pb.environment()["PROOT_TMP_DIR"] = File(files, "tmp").path
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        pb.redirectErrorStream(true)
        return pb
    }
}
