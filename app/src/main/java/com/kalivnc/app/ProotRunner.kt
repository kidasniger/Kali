package com.kalivnc.app

import android.content.Context
import java.io.File

object ProotRunner {
    /**
     * Exécute une commande dans le rootfs Kali via PRoot.
     *
     * On évite -w /root et /usr/bin/env comme premier programme invité :
     * les anciennes versions de PRoot ont eu des problèmes Android liés au cwd.
     * Le changement de répertoire est fait depuis bash à l'intérieur du rootfs.
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
            "-b", "/dev",
            "-b", "/proc",
            "/bin/bash", "-c",
            "cd /root && $script"
        )

        val pb = ProcessBuilder(cmd)
        pb.directory(files)

        // Ne pas transmettre les variables Android pouvant perturber un ELF
        // glibc lancé dans le rootfs.
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
