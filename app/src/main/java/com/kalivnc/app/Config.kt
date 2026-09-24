package com.kalivnc.app

/** Réglages centraux : modifie ici les URLs si un lien change. */
object Config {
    /** proot statique arm64 (téléchargé au premier lancement). */
    const val PROOT_URL =
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"

    /** Rootfs Kali NetHunter (arm64, version minimale). */
    const val ROOTFS_URL =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"

    /** Dossier racine contenu dans l'archive (retiré à l'extraction). */
    const val ROOTFS_TOP_DIR = "kali-arm64"

    const val VNC_PORT = 5901

    /** Largeur maximale du bureau VNC (plus petit = plus fluide). */
    const val MAX_DESKTOP_WIDTH = 1280

    /** Paquets installés dans Kali au premier lancement. */
    const val APT_PACKAGES =
        "tigervnc-standalone-server tigervnc-common x11-xkb-utils xkb-data xauth x11-xserver-utils " +
        "xfonts-base fonts-dejavu-core dbus-x11 xfce4 xfce4-terminal adwaita-icon-theme " +
        "nmap curl nano net-tools iputils-ping"
}
