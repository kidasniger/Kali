export DEBIAN_FRONTEND=noninteractive

echo "== diagnostic =="
echo "whoami: $(whoami)"
cat /etc/resolv.conf
getent hosts http.kali.org || echo "DNS: echec de resolution"

echo "== apt-get update =="
stdbuf -oL -eL apt-get update -o Acquire::Check-Valid-Until=false
RC=$?
echo "apt-get update -> code $RC"
[ $RC -ne 0 ] && { echo "ERREUR_UPDATE"; exit $RC; }

echo "== apt-get install =="
stdbuf -oL -eL apt-get install -y --no-install-recommends __PKGS__
RC=$?
echo "apt-get install -> code $RC"
if [ $RC -ne 0 ]; then
  echo "== reparation dpkg =="
  dpkg --configure -a
  stdbuf -oL -eL apt-get install -f -y
  RC=$?
  echo "apt-get -f install -> code $RC"
fi
[ $RC -ne 0 ] && { echo "ERREUR_INSTALL"; exit $RC; }
echo "Installation terminee."
