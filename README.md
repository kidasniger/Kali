# Kali VNC (Android)

Kali VNC installe un rootfs Kali NetHunter minimal ARM64, puis démarre XFCE +
TigerVNC dans un environnement Linux sans root.

## Backend Linux Android

À partir de la v1.7, l'application utilise un PRoot Android statique compilé
à partir du fork Android de la chaîne Termux/PRoot.

Le launcher configure :

- `PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`
- `PROOT_NO_SECCOMP=1`
- `PROOT_TMP_DIR=<filesDir>/tmp`
- `TMPDIR=<filesDir>/tmp`

Puis exécute :

`proot -r <rootfs> -0 --link2symlink -w /root /bin/sh -c <commande>`

Le loader séparé évite d'exécuter un ELF du rootfs depuis un répertoire
writable de l'application.

Source du backend :
https://github.com/oonid/pr/tree/fcf25cb2396361f0be2edfc96fdd61a6e738c9d9

## Premier lancement

- Internet + plusieurs Go libres.
- Le rootfs Kali est téléchargé puis vérifié par SHA-256 avant extraction.
- Un probe PRoot vérifie le shell, l'architecture ARM64 et le chemin de travail
  avant `apt`.
- Le serveur VNC reste limité à `127.0.0.1`.

## Attribution

PRoot est distribué sous GPL-2.0-or-later ; voir `THIRD_PARTY_NOTICES.md`.
