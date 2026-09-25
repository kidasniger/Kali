# Kali VNC (Android)

Kali VNC installe un rootfs Kali NetHunter minimal ARM64, puis démarre XFCE +
TigerVNC dans un environnement Linux sans root et affiche le bureau en plein écran.

## Moteur Android

La version actuelle utilise les bibliothèques officielles **proroot 1.2.8** intégrées
à l'APK et exécutées depuis `nativeLibraryDir`.

L'invocation Android suit le modèle documenté par proroot : rootfs + uid 0 + link2symlink +
working directory, sans bind global de `/dev`, `/proc` ou `/sys`. Le shell de lancement est
`/bin/sh`. Si un appareil provoque un SIGSEGV avec le mode static-loader adaptatif, le probe
retente automatiquement avec `--no-static-loader` et mémorise le mode validé pour les lancements suivants.

Source : https://github.com/coderredlab/proroot

## Premier lancement

- Internet + plusieurs Go libres.
- Le rootfs Kali est téléchargé puis vérifié par SHA-256 avant extraction.
- Un probe PRoot vérifie `/bin/sh`, `/bin/bash`, `/bin/true` et le répertoire `/root` avant `apt`.
- Le serveur VNC reste limité à `127.0.0.1`.

## Diagnostic

Le journal natif PRoot est conservé dans `filesDir/proroot-runtime.log`.
En cas d'échec, l'application affiche les dernières lignes de ce journal.

## Attribution

Kali VNC utilise **proroot 1.2.8**, bibliothèques ARM64 officielles non modifiées.
Voir `THIRD_PARTY_NOTICES.md`.

## Limites

Sans root Android, certaines fonctions matérielles de Kali/NetHunter ne sont pas disponibles.
