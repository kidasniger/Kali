#!/bin/sh
export HOME=/root USER=root LANG=C.UTF-8
mkdir -p /root/.vnc /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix

rm -f /tmp/.X1-lock /tmp/.X11-unix/X1

Xtigervnc :1 -geometry __W__x__H__ -depth 24 \
  -rfbport __PORT__ -localhost -UseIPv4=1 \
  -SecurityTypes None -AlwaysShared -ac -desktop Kali &
XPID=$!

for i in $(seq 1 40); do
  [ -S /tmp/.X11-unix/X1 ] && break
  sleep 0.5
done

export DISPLAY=:1

# xrdb (appelé par startxfce4) invoque le vrai "cpp" de gcc, qui segfault (cc1)
# dans cet environnement PRoot. On le remplace par un pseudo-préprocesseur
# minimal qui se contente de recopier le fichier, sans macros ni #include.
cat > /tmp/kalivnc-cpp <<'CPPEOF'
#!/bin/sh
eval last="\${$#}"
exec cat "$last"
CPPEOF
chmod +x /tmp/kalivnc-cpp
export CPP=/tmp/kalivnc-cpp

dbus-launch --exit-with-session startxfce4 &

wait $XPID
