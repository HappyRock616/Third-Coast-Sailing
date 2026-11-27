#!/usr/bin/env bash
# SIMULATOR (needs the Connect IQ simulator, so only when it's free). Build, load and optionally screenshot.
#   scripts/sim.sh [device]            normal debug build (no sample data), loaded into the simulator
#   REL=1 scripts/sim.sh --tour ...   the same as a release (optimised, no debug info) build, for memory checks
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
"$SDK/bin/monkeyc.bat" -f $JUNGLE -d "$DEV" -o "bin/sim-$DEV.prg" -y "$KEY" ${REL:+-r} -w 2>&1 | grep -v "launcher icon" || true
if ! tasklist //FI "IMAGENAME eq simulator.exe" | grep -q simulator.exe; then
  cmd.exe //c start "" "$SDK\bin\simulator.exe"; sleep 8
fi
nohup "$SDK/bin/monkeydo.bat" "bin/sim-$DEV.prg" "$DEV" > "bin/sim-$DEV.log" 2>&1 &
sleep 6
echo "Loaded on $DEV"
if [ $TOUR = 1 ]; then
  OUT="docs/sim/$DEV"; mkdir -p "$OUT"; rm -f "$OUT"/*.png
  # Names come from Tour.NAMES; each shot is taken 1.5 s after the app logs "TOUR NN name"
  NAMES=($(grep -A4 "const NAMES" source/Tour.mc | grep -o "\"[a-z-]*\"" | tr -d "\""))
  for i in "${!NAMES[@]}"; do
    n=$(printf %02d $i)
    for k in $(seq 1 60); do grep -q "TOUR $n " "bin/sim-$DEV.log" 2>/dev/null && break; sleep 0.5; done
    sleep 3                                   # mid-step (steps are 8 s; the log can lag up to ~4 s)
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/simshot.ps1 -Out "$(cygpath -w "$PWD/$OUT")\\$n-${NAMES[$i]}.png" > /dev/null
  done
  echo "Screenshots in $OUT"
fi
