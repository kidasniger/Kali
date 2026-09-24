# Kali VNC (Android)

Appli Android **tout-en-un** : au lancement elle démarre Kali NetHunter (rootfs Kali sous **proot**, sans root)
et un serveur **VNC** en arrière-plan, puis n'affiche **que le bureau Kali en plein écran**.
Le client VNC est écrit en Kotlin et compilé dans l'APK : pas de Termux, pas d'appli VNC séparée.

## Compiler l'APK (GitHub Actions)

1. Crée un dépôt GitHub et pousse tout le contenu de ce dossier (branche `main`).
2. Onglet **Actions** → workflow *Build APK* (il se lance tout seul au push).
3. Quand il est vert : ouvre l'exécution → section **Artifacts** → télécharge `KaliVNC-debug-apk`.
4. Installe l'APK sur le téléphone (autoriser les sources inconnues).

## Premier lancement

- Connexion Internet + ~3 Go libres. L'appli télécharge proot et Kali, extrait le système, puis
  installe XFCE + TigerVNC via `apt` (10 à 30 min). Un journal s'affiche pendant ce temps.
- Ensuite chaque lancement va directement au bureau (10-20 s).
- Le service tourne au premier plan (notification avec bouton **Arrêter**).

## Utilisation

| Geste | Action |
|---|---|
| Toucher | Clic gauche |
| Appui long / 2 doigts (tap) | Clic droit |
| Glisser (1 doigt) | Glisser-déposer / sélection |
| Glisser (2 doigts) | Défilement |
| Tap à 3 doigts ou bouton Retour | Afficher/masquer le clavier + barre Esc/Tab/Ctrl/Alt/flèches |

## Réglages (`Config.kt`)

- `PROOT_URL` : binaire proot statique arm64. **Si le téléchargement échoue, change cette URL.**
- `ROOTFS_URL` / `ROOTFS_TOP_DIR` : rootfs Kali NetHunter (minimal par défaut).
- `MAX_DESKTOP_WIDTH` : résolution du bureau (plus petit = plus fluide).
- `APT_PACKAGES` : paquets installés au premier lancement (ajoute `kali-tools-top10` pour les outils Kali).

## Limites

- Sans root : pas de mode monitor Wi-Fi, pas d'injection de paquets ni d'attaques HID.
- `targetSdk = 28` volontaire (comme Termux) pour pouvoir exécuter proot depuis le stockage interne.
  Distribution par APK/GitHub uniquement, pas Google Play.
- Android 12+ : le « phantom process killer » peut tuer les processus enfants ; le service au premier plan aide,
  sinon désactive-le via ADB : `adb shell settings put global settings_enable_monitor_phantom_procs false`.
- Le serveur VNC n'écoute que sur `127.0.0.1` et est protégé par un mot de passe aléatoire.
- À utiliser uniquement sur des systèmes que tu possèdes ou pour lesquels tu as une autorisation.
