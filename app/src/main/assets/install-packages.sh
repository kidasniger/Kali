export DEBIAN_FRONTEND=noninteractive
apt-get update -o Acquire::Check-Valid-Until=false || exit 1
if ! apt-get install -y --no-install-recommends __PKGS__; then
  dpkg --configure -a
  apt-get install -f -y || exit 2
fi
echo "Installation terminée."
