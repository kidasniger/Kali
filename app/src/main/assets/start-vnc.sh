#!/bin/sh
export HOME=/root USER=root LANG=C.UTF-8
mkdir -p /root/.vnc /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix

PASSWD_BIN="$(command -v tigervncpasswd || command -v vncpasswd)"
if [ -z "$PASSWD_BIN" ]; then
  echo "tigervncpasswd/vncpasswd introuvable"
  exit 1
fi

printf '%s\n' '__PW__' | "$PASSWD_BIN" -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd

rm -f /tmp/.X1-lock /tmp/.X11-unix/X1

Xtigervnc :1 -geometry __W__x__H__ -depth 24 \
  -rfbauth /root/.vnc/passwd -rfbport __PORT__ -localhost \
  -SecurityTypes VncAuth -AlwaysShared -ac -desktop Kali &
XPID=$!

for i in $(seq 1 40); do
  [ -S /tmp/.X11-unix/X1 ] && break
  sleep 0.5
done

export DISPLAY=:1
dbus-launch --exit-with-session startxfce4 &

wait $XPID
