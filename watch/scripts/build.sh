#!/usr/bin/env bash
# Compile only (no simulator). Usage: scripts/build.sh [device-id ...]   (default: vivoactive5)
# Output: bin/TCS-<device>.prg. Uses the shared developer key; never starts or touches the simulator.
set -e
cd "$(dirname "$0")/.."
SDK="$(tr -d '\r\n' < "$APPDATA/Garmin/ConnectIQ/current-sdk.cfg")"
KEY="${CIQ_KEY:?set CIQ_KEY to the path of your Connect IQ developer key (.der)}"
mkdir -p bin
for DEV in "${@:-vivoactive5}"; do
  rm -f "bin/TCS-$DEV.prg"
  "$SDK/bin/monkeyc.bat" -f monkey.jungle -d "$DEV" -o "bin/TCS-$DEV.prg" -y "$KEY" -w 2>&1 | grep -v "^WARNING: .*launcher icon" || true
  [ -f "bin/TCS-$DEV.prg" ] && echo "OK $DEV $(stat -c %s "bin/TCS-$DEV.prg") bytes"
done
