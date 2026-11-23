#!/usr/bin/env bash
# SIMULATOR (needs the Connect IQ simulator, so only when it's free). Build, load and optionally screenshot.
#   scripts/sim.sh [device]            normal debug build with demo data, loaded into the simulator
#   scripts/sim.sh --tour [device]     demo tour build: every page and state, one screenshot each, into
#                                      docs/sim/<device>/NN-name.png (needs scripts/simshot.ps1)
set -e
TOUR=0; if [ "$1" = "--tour" ]; then TOUR=1; shift; fi
DEV="${1:-vivoactive5}"
cd "$(dirname "$0")/.."
SDK="$(tr -d '\r\n' < "$APPDATA/Garmin/ConnectIQ/current-sdk.cfg")"
KEY="${CIQ_KEY:?set CIQ_KEY to the path of your Connect IQ developer key (.der)}"
JUNGLE=monkey.jungle; [ $TOUR = 1 ] && JUNGLE=test.jungle
mkdir -p bin
"$SDK/bin/monkeyc.bat" -f $JUNGLE -d "$DEV" -o "bin/sim-$DEV.prg" -y "$KEY" -w 2>&1 | grep -v "launcher icon" || true
if ! tasklist //FI "IMAGENAME eq simulator.exe" | grep -q simulator.exe; then
  cmd.exe //c start "" "$SDK\bin\simulator.exe"; sleep 8
fi
nohup "$SDK/bin/monkeydo.bat" "bin/sim-$DEV.prg" "$DEV" > "bin/sim-$DEV.log" 2>&1 &
sleep 6
echo "Loaded on $DEV"
if [ $TOUR = 1 ]; then
  OUT="docs/sim/$DEV"; mkdir -p "$OUT"
  NAMES=(gauge gauge-outside mark mark-approach anchor anchor-alarm set-anchor set-anchor-drop conditions conditions-sca sight sight-angle sight-review mob mob-boat-up mob-watch-up mob-north-up gauge-nolink link-pick link-code link-connected)
  sleep 2
  for i in "${!NAMES[@]}"; do
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/simshot.ps1 -Out "$(cygpath -w "$PWD/$OUT")\$(printf %02d $i)-${NAMES[$i]}.png" > /dev/null
    sleep 4
  done
  echo "Screenshots in $OUT"
fi
