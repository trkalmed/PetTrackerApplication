#!/usr/bin/env bash
# Installs the Pet Tracker service so it starts at boot and restarts if it dies.

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
# Install for the user who ran sudo, not for root.
SERVICE_USER="${SUDO_USER:-$(id -un)}"
UNIT=/etc/systemd/system/pettracker.service

if [ ! -f "$DIR/run.py" ]; then
    echo "run.py is not next to this script — run it from the pi/ directory" >&2
    exit 1
fi

echo "Installing pettracker.service"
echo "  user      : $SERVICE_USER"
echo "  directory : $DIR"

# Stop any copy started by hand, so two do not fight over port 8080.
if pgrep -f "python3 run.py" > /dev/null; then
    echo "  stopping the copy started by hand"
    pkill -f "python3 run.py" || true
fi

# Strip carriage returns: systemd would read them as part of each value.
sed "s|@USER@|$SERVICE_USER|g; s|@DIR@|$DIR|g; s/\r$//" "$DIR/pettracker.service" \
    | sudo tee "$UNIT" > /dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now pettracker

echo
sudo systemctl status pettracker --no-pager --lines=5 || true
echo
echo "Logs   : journalctl -u pettracker -f"
echo "Restart: sudo systemctl restart pettracker"
echo "Options: put any of these in $DIR/service.env, then restart."
echo "         The unit lists them with their defaults; README.md explains them."
echo
echo "         rooms and sensors  PETTRACKER_ROOMS PETTRACKER_PIR_PINS"
echo "                            PETTRACKER_CAMERA PETTRACKER_GPS_PORT"
echo "         load cell          PETTRACKER_SCALE_PINS PETTRACKER_SCALE_FACTOR"
echo "         feeder             PETTRACKER_FEEDER (gate or paddle)"
echo "                            PETTRACKER_SERVO_PIN"
echo "           gate             PETTRACKER_SERVO_OPEN PETTRACKER_SERVO_CLOSED"
echo "                            PETTRACKER_SERVO_MS_PER_GRAM"
echo "           paddle           PETTRACKER_PADDLE_PARK PETTRACKER_PADDLE_DUMP"
echo "                            PETTRACKER_PADDLE_GRAMS"
echo
echo "         The two worth measuring rather than guessing are"
echo "         PETTRACKER_SCALE_FACTOR (--calibrate-scale) and, for a paddle,"
echo "         PETTRACKER_PADDLE_GRAMS (ten cycles into a bowl, divided by ten)."
