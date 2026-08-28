#!/usr/bin/env bash
#
# Installs the Pet Tracker service so it starts at boot and restarts if it dies.
#
#     ./install-service.sh
#
# Run it from the directory it lives in, as the user the service should run as
# — that user's home holds the event database, and it is the one already in the
# gpio and video groups. It will ask for sudo when it writes the unit file.
#
# Safe to run again after pulling new code: it rewrites the unit and restarts.

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
# SUDO_USER first, so running this under sudo installs the service for the
# person who typed it rather than for root, whose home has none of this in it.
SERVICE_USER="${SUDO_USER:-$(id -un)}"
UNIT=/etc/systemd/system/pettracker.service

if [ ! -f "$DIR/run.py" ]; then
    echo "run.py is not next to this script — run it from the pi/ directory" >&2
    exit 1
fi

echo "Installing pettracker.service"
echo "  user      : $SERVICE_USER"
echo "  directory : $DIR"

# Anything started by hand is about to be replaced by a copy that restarts
# itself, and two of them would fight over port 8080.
if pgrep -f "python3 run.py" > /dev/null; then
    echo "  stopping the copy started by hand"
    pkill -f "python3 run.py" || true
fi

sed "s|@USER@|$SERVICE_USER|g; s|@DIR@|$DIR|g" "$DIR/pettracker.service" \
    | sudo tee "$UNIT" > /dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now pettracker

echo
sudo systemctl status pettracker --no-pager --lines=5 || true
echo
echo "Logs   : journalctl -u pettracker -f"
echo "Restart: sudo systemctl restart pettracker"
echo "Options: put PETTRACKER_ROOMS / PETTRACKER_PIR_PINS / PETTRACKER_CAMERA"
echo "         in $DIR/service.env, then restart"
