package com.kalivnc.app

import android.content.Context
import java.io.File

object ProotRunner {
    /**
     * Exécute une commande dans le rootfs Kali via PRoot.
     *
     * PRoot utilise le répertoire courant du processus hôte lorsque aucun
     * répertoire invité absolu n'est fourni. Sur Android cela donne souvent
     * /data/user/0/<package>/files/./. et provoque :
     * "can't chdir(...): No such file or directory".
     *
     * On force donc le cwd invité avec -w /root, qui existe dans le rootfs,
     * et on lance directement bash sans dépendre du cwd Android.
     */
    fun builder(ctx: Context, script: String): ProcessBuilder {
        val files = ctx.filesDir
        val root = Installer.rootfs(ctx)
        val proot = File(files, "bin/proot")

        val cmd = mutableListOf(
            proot.path,
            "--link2symlink",
            "--kill-on-exit",
            "-0",
            "-r", root.path,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "/bin/bash", "-c",
            script
        )

        val pb = ProcessBuilder(cmd)

        // Le cwd hôte n'est plus utilisé pour déterminer le cwd invité :
        // PRoot reçoit explicitement -w /root.
        pb.directory(files)

        // Environnement minimal et déterministe pour les programmes glibc
        // exécutés dans le rootfs Kali.
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
        env["TMPDIR"] = File(files, "tmp").path
        env["PROOT_TMP_DIR"] = File(files, "tmp").path
        env["PROOT_NO_SECCOMP"] = "1"

        pb.redirectErrorStream(true)
        return pb
    }
}
