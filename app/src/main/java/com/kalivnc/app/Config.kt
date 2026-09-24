package com.kalivnc.app

/** Réglages centraux de l'application. */
object Config {
    const val PROROOT_ENGINE_VERSION = "1.2.8"
    const val PROROOT_LIBRARY = "libproroot.so"

    /** Rootfs Kali NetHunter minimal ARM64. */
    const val ROOTFS_URL =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
    const val ROOTFS_SHA256_URL =
        "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS"
    const val ROOTFS_FILE_NAME = "kali-nethunter-rootfs-minimal-arm64.tar.xz"
    const val ROOTFS_TOP_DIR = "kali-arm64"

    const val VNC_PORT = 5901
    const val MAX_DESKTOP_WIDTH = 1280

    const val APT_PACKAGES =
        "tigervnc-standalone-server tigervnc-common x11-xkb-utils xkb-data xauth x11-xserver-utils " +
        "xfonts-base fonts-dejavu-core dbus-x11 xfce4 xfce4-terminal adwaita-icon-theme " +
        "nmap curl nano net-tools iputils-ping"
}