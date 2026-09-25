#!/bin/sh
set -u

export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

echo "== diagnostic Kali =="
echo "uid: $(id -u)"
echo "pwd: $(pwd)"
echo "uname: $(uname -a)"
echo "resolv.conf:"
cat /etc/resolv.conf

if command -v getent >/dev/null 2>&1; then
  getent hosts http.kali.org || echo "DNS: échec de résolution"
else
  echo "getent absent, test DNS ignoré"
fi

disable_kali_apt_hook() {
  for cfg in /etc/apt/apt.conf.d/*; do
    [ -f "$cfg" ] || continue
    if grep -q 'kali-check-apt-sources' "$cfg" 2>/dev/null; then
      cp -f "$cfg" "$cfg.kalivnc-original"
      sed -i '/kali-check-apt-sources/d' "$cfg"
      echo "Hook Kali incompatible PRoot neutralise : $cfg"
    fi
  done
}

echo "== préparation APT pour PRoot =="
disable_kali_apt_hook

echo "Hooks APT actifs :"
apt-config dump 2>/dev/null | grep -i 'Update::Post-Invoke' || true

echo "== apt-get update =="
apt-get update -o Acquire::Check-Valid-Until=false -o Dpkg::Use-Pty=0
RC=$?
echo "apt-get update -> code $RC"
if [ "$RC" -ne 0 ]; then
  echo "ERREUR_UPDATE"
  exit "$RC"
fi

echo "== apt-get install =="
apt-get install -y --no-install-recommends -o Dpkg::Use-Pty=0 __PKGS__
RC=$?
echo "apt-get install -> code $RC"

if [ "$RC" -ne 0 ]; then
  echo "== réparation dpkg =="
  dpkg --configure -a || true
  apt-get install -f -y -o Dpkg::Use-Pty=0 || true

  echo "== nouvelle tentative des paquets demandés =="
  apt-get install -y --no-install-recommends -o Dpkg::Use-Pty=0 __PKGS__
  RC=$?
  echo "deuxième apt-get install -> code $RC"
fi

if [ "$RC" -ne 0 ]; then
  echo "ERREUR_INSTALL"
  dpkg --audit || true
  exit "$RC"
fi

echo "Installation terminee."
